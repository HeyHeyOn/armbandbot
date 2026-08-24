package com.heyheyon.armbandbot

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.util.LinkedHashMap

internal enum class DashboardSearchMatchCode(val label: String) {
    POST_NUMBER("글 번호"),
    POST_AUTHOR("글 작성자"),
    POST_TITLE("글 제목"),
    POST_CONTENT("글 내용"),
    COMMENT_AUTHOR("댓글 작성자"),
    COMMENT_CONTENT("댓글 내용")
}

internal fun shouldPublishDashboardSearchResult(
    requestVersion: Int,
    currentVersion: Int,
    requestQuery: String,
    currentInputQuery: String,
    requestDataEpoch: Int,
    currentDataEpoch: Int,
    requestScopes: Set<DashboardSearchMatchCode>,
    currentScopes: Set<DashboardSearchMatchCode>,
    isClearing: Boolean,
): Boolean =
    requestVersion == currentVersion &&
        requestQuery == currentInputQuery &&
        requestDataEpoch == currentDataEpoch &&
        requestScopes == currentScopes &&
        !isClearing

internal val ALL_DASHBOARD_SEARCH_MATCH_CODES: Set<DashboardSearchMatchCode> =
    DashboardSearchMatchCode.entries.toSet()

internal fun decodeDashboardSearchScopes(stored: Set<String>?): Set<DashboardSearchMatchCode> {
    if (stored.isNullOrEmpty()) return ALL_DASHBOARD_SEARCH_MATCH_CODES
    return DashboardSearchMatchCode.entries
        .filterTo(linkedSetOf()) { it.name in stored }
        .ifEmpty { ALL_DASHBOARD_SEARCH_MATCH_CODES }
}

internal fun encodeDashboardSearchScopes(
    scopes: Set<DashboardSearchMatchCode>,
): Set<String> = if (scopes == ALL_DASHBOARD_SEARCH_MATCH_CODES) {
    emptySet()
} else {
    DashboardSearchMatchCode.entries
        .filter(scopes::contains)
        .mapTo(linkedSetOf(), DashboardSearchMatchCode::name)
}

/** Empty scopes are an inapplicable UI state and, like the default all-scopes state, have no summary. */
internal fun dashboardSearchScopeSummary(scopes: Set<DashboardSearchMatchCode>): String? =
    scopes
        .takeUnless { it.isEmpty() || it == ALL_DASHBOARD_SEARCH_MATCH_CODES }
        ?.let { selected ->
            DashboardSearchMatchCode.entries
                .filter(selected::contains)
                .joinToString(prefix = "검색 범위: ", separator = ", ") { it.label }
        }

internal data class DashboardSearchDocument(
    val postNumbers: List<String> = emptyList(),
    val postAuthors: List<String> = emptyList(),
    val postTitles: List<String> = emptyList(),
    val postContents: List<String> = emptyList(),
    val commentAuthors: List<String> = emptyList(),
    val commentContents: List<String> = emptyList()
)

internal data class DashboardMatched<T>(
    val row: T,
    val matches: List<DashboardSearchMatchCode>,
)

private data class DashboardRowDocumentEntry(
    val freshness: Any?,
    val document: DashboardSearchDocument,
)

/**
 * Bounded session index that keeps the first deterministic resident set rather than cycling an LRU
 * during scans larger than [capacity]. A changed freshness token replaces an existing identity, but
 * writers that replace snapshot content while preserving path, timestamp, and length must still
 * explicitly [clear] the index.
 */
internal class DashboardRowDocumentIndex(
    private val capacity: Int = DEFAULT_CAPACITY,
) {
    init {
        require(capacity > 0) { "Dashboard row document index capacity must be greater than zero" }
    }

    private val documents = mutableMapOf<Any, DashboardRowDocumentEntry>()
    private var generation = 0L

    internal val size: Int
        @Synchronized get() = documents.size

    @Synchronized
    fun clear() {
        generation++
        documents.clear()
    }

    fun getOrBuild(
        identity: Any,
        freshness: Any?,
        build: () -> DashboardSearchDocument,
    ): DashboardSearchDocument {
        val buildGeneration = synchronized(this) {
            documents[identity]
                ?.takeIf { it.freshness == freshness }
                ?.let { return it.document }
            generation
        }
        val document = build()
        synchronized(this) {
            if (generation != buildGeneration) return document

            documents[identity]
                ?.takeIf { it.freshness == freshness }
                ?.let { return it.document }

            // Existing identities may always be refreshed. New identities are retained only while
            // capacity remains, preserving residents across repeated full sequential scans.
            if (documents.containsKey(identity) || documents.size < capacity) {
                documents[identity] = DashboardRowDocumentEntry(freshness, document)
            }
            return document
        }
    }

    private companion object {
        const val DEFAULT_CAPACITY = 256
    }
}

/**
 * Expanded-search pipeline. Filtering precedes document construction, then every included candidate
 * is matched before sort/limit. The caller owns the execution context and must call this from IO when
 * document providers perform file work. Candidate boundaries are cooperative cancellation points.
 */
internal suspend fun <T> searchDashboardRows(
    candidates: List<T>,
    query: String,
    includeRow: (T) -> Boolean,
    comparator: Comparator<T>,
    limit: Int,
    directDocument: (T) -> DashboardSearchDocument,
    snapshotDocument: (T) -> DashboardSearchDocument,
    snapshotFreshness: (T) -> Any? = { Unit },
    documentIndex: DashboardRowDocumentIndex? = null,
    rowIdentity: (T) -> Any = { it as Any },
    enabledCodes: Set<DashboardSearchMatchCode> = ALL_DASHBOARD_SEARCH_MATCH_CODES,
): List<DashboardMatched<T>> {
    require(limit >= 0) { "Dashboard search limit must not be negative" }
    if (query.isBlank()) {
        return candidates
            .asSequence()
            .filter(includeRow)
            .sortedWith(comparator)
            .take(limit)
            .map { DashboardMatched(it, emptyList()) }
            .toList()
    }
    currentCoroutineContext().ensureActive()
    if (enabledCodes.isEmpty()) return emptyList()

    val matched = ArrayList<DashboardMatched<T>>()
    for (row in candidates) {
        currentCoroutineContext().ensureActive()
        if (!includeRow(row)) continue
        val direct = directDocument(row)
        val document = documentIndex?.getOrBuild(
            identity = rowIdentity(row),
            freshness = DashboardRowDocumentFreshness(direct, snapshotFreshness(row)),
        ) {
            mergeDashboardSearchDocuments(direct, snapshotDocument(row))
        } ?: mergeDashboardSearchDocuments(direct, snapshotDocument(row))
        val matches = dashboardSearchMatchCodes(document, query, enabledCodes)
        if (matches.isNotEmpty()) matched += DashboardMatched(row, matches)
    }
    currentCoroutineContext().ensureActive()
    return matched
        .asSequence()
        .sortedWith { first, second -> comparator.compare(first.row, second.row) }
        .take(limit)
        .toList()
}

private data class DashboardRowDocumentFreshness(
    val directDocument: DashboardSearchDocument,
    val snapshotToken: Any?,
)

internal fun checkedPostDashboardComparator(sortField: String, ascending: Boolean): Comparator<CheckedPost> =
    dashboardComparator(
        sortField = sortField,
        ascending = ascending,
        checkValue = CheckedPost::checkTime,
        creationValue = CheckedPost::creationDate,
    )

internal fun blockHistoryDashboardComparator(sortField: String, ascending: Boolean): Comparator<BlockHistory> =
    dashboardComparator(
        sortField = sortField,
        ascending = ascending,
        checkValue = BlockHistory::blockTime,
        creationValue = BlockHistory::creationDate,
    )

internal fun holdHistoryDashboardComparator(sortField: String, ascending: Boolean): Comparator<HoldHistory> =
    dashboardComparator(
        sortField = sortField,
        ascending = ascending,
        checkValue = HoldHistory::holdTime,
        creationValue = HoldHistory::creationDate,
    )

private fun <T> dashboardComparator(
    sortField: String,
    ascending: Boolean,
    checkValue: (T) -> Long,
    creationValue: (T) -> String?,
): Comparator<T> {
    val comparator = if (sortField == "CREATE") {
        compareBy<T, String?>(nullsFirst()) { creationValue(it) }
    } else {
        compareBy(checkValue)
    }
    return if (ascending) comparator else comparator.reversed()
}

internal fun mergeDashboardSearchDocuments(
    first: DashboardSearchDocument,
    second: DashboardSearchDocument,
): DashboardSearchDocument = DashboardSearchDocument(
    postNumbers = first.postNumbers + second.postNumbers,
    postAuthors = first.postAuthors + second.postAuthors,
    postTitles = first.postTitles + second.postTitles,
    postContents = first.postContents + second.postContents,
    commentAuthors = first.commentAuthors + second.commentAuthors,
    commentContents = first.commentContents + second.commentContents,
)

private data class SnapshotSearchFileSignature(
    val lastModified: Long,
    val length: Long,
)

private data class SnapshotSearchVersionSignature(
    val canonicalPath: String,
    val signature: SnapshotSearchFileSignature?,
)

private data class SnapshotSearchCacheEntry(
    val signature: SnapshotSearchFileSignature,
    val document: DashboardSearchDocument,
)

/**
 * Memory-only cache intended to be owned by one dashboard session. Snapshot parsing and file I/O
 * are deliberately synchronous; callers integrating this cache must invoke [loadBlocking] on
 * Dispatchers.IO.
 *
 * Entries are access-ordered and bounded to [capacity] (128 by default). Writers that can replace
 * a file while preserving both its timestamp and length must call [invalidate] (or [clear] for a
 * full dashboard refresh), because those two metadata fields cannot identify that replacement.
 */
internal class SnapshotSearchDocumentCache(
    private val allowedRoots: List<File>,
    private val capacity: Int = DEFAULT_CAPACITY,
    private val parser: (String) -> SnapshotData = ::parseSnapshot,
) {
    init {
        require(capacity > 0) { "Snapshot search cache capacity must be greater than zero" }
    }

    private val entriesByCanonicalPath = LinkedHashMap<String, SnapshotSearchCacheEntry>(
        capacity,
        0.75f,
        true,
    )
    private var generation = 0L

    internal val cacheSize: Int
        get() = synchronized(entriesByCanonicalPath) { entriesByCanonicalPath.size }

    fun clear() {
        synchronized(entriesByCanonicalPath) {
            generation++
            entriesByCanonicalPath.clear()
        }
    }

    fun invalidate(snapshotPath: String?) {
        if (snapshotPath.isNullOrBlank()) return
        val canonicalPath = try {
            File(snapshotPath).canonicalPath
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            return
        }
        synchronized(entriesByCanonicalPath) {
            // A global generation keeps invalidation simple and prevents any parse already in
            // flight from publishing after an explicit invalidation boundary.
            generation++
            entriesByCanonicalPath.remove(canonicalPath)
        }
    }

    fun loadBlocking(snapshotPath: String?): DashboardSearchDocument {
        if (snapshotPath.isNullOrBlank()) return DashboardSearchDocument()
        return snapshotSearchFiles(snapshotPath, allowedRoots).fold(DashboardSearchDocument()) { merged, file ->
            mergeDashboardSearchDocuments(merged, loadFileBlocking(file))
        }
    }

    private fun loadFileBlocking(file: File): DashboardSearchDocument {
        val canonicalFile = try {
            file.canonicalFile
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            return DashboardSearchDocument()
        }
        val canonicalPath = canonicalFile.path
        val signatureBeforeParse = canonicalFile.signatureOrNull() ?: run {
            synchronized(entriesByCanonicalPath) { entriesByCanonicalPath.remove(canonicalPath) }
            return DashboardSearchDocument()
        }
        val parseGeneration = synchronized(entriesByCanonicalPath) {
            entriesByCanonicalPath[canonicalPath]
                ?.takeIf { it.signature == signatureBeforeParse }
                ?.let { return it.document }
            // A failed refresh must not leave an older same-path entry eligible for a later hit.
            entriesByCanonicalPath.remove(canonicalPath)
            generation
        }

        // Parsing is intentionally outside the map monitor. Unrelated paths must not serialize.
        val document = try {
            parser(canonicalPath).toDashboardSearchDocument()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            return DashboardSearchDocument()
        }

        // Return the parse result to this caller, but publish it only when it represents the same
        // still-existing file observed immediately before parsing.
        if (canonicalFile.signatureOrNull() == signatureBeforeParse) {
            synchronized(entriesByCanonicalPath) {
                if (generation != parseGeneration) return document
                entriesByCanonicalPath[canonicalPath] = SnapshotSearchCacheEntry(signatureBeforeParse, document)
                if (entriesByCanonicalPath.size > capacity) {
                    val eldestKey = entriesByCanonicalPath.entries.iterator().next().key
                    entriesByCanonicalPath.remove(eldestKey)
                }
            }
        }
        return document
    }

    private fun File.signatureOrNull(): SnapshotSearchFileSignature? = try {
        takeIf { it.isFile }?.let {
            SnapshotSearchFileSignature(lastModified = it.lastModified(), length = it.length())
        }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        null
    }

    private companion object {
        const val DEFAULT_CAPACITY = 128
    }
}

/**
 * Includes canonical path identity and metadata for the same initial/latest/legacy candidates used
 * by snapshot loading. Missing or malformed paths safely produce absent/fallback signatures.
 */
internal fun snapshotSearchFreshnessToken(snapshotPath: String?, allowedRoots: List<File>): Any {
    if (snapshotPath.isNullOrBlank()) return emptyList<SnapshotSearchVersionSignature>()
    return snapshotSearchCandidatePaths(snapshotPath)
        .mapNotNull { path ->
            trustedSnapshotSearchFile(path, allowedRoots)?.let { file ->
                SnapshotSearchVersionSignature(file.path, file.signatureOrNullForFreshness())
            }
        }
        .distinctBy(SnapshotSearchVersionSignature::canonicalPath)
}

private fun snapshotSearchCandidatePaths(snapshotPath: String): List<String> {
    val versionPaths = try {
        deriveSnapshotVersionPaths(snapshotPath)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        return listOf(snapshotPath)
    }
    return if (versionPaths != null) {
        listOf(versionPaths.initialPath, versionPaths.latestPath)
    } else {
        listOf(snapshotPath)
    }
}

private fun File.signatureOrNullForFreshness(): SnapshotSearchFileSignature? = try {
    takeIf { it.isFile }?.let {
        SnapshotSearchFileSignature(lastModified = it.lastModified(), length = it.length())
    }
} catch (cancellation: CancellationException) {
    throw cancellation
} catch (_: Exception) {
    null
}

private fun snapshotSearchFiles(snapshotPath: String, allowedRoots: List<File>): List<File> {
    return snapshotSearchCandidatePaths(snapshotPath)
        .mapNotNull { path -> trustedSnapshotSearchFile(path, allowedRoots) }
        .distinctBy { it.path }
}

/**
 * Trusts only readable, nonempty HTML files directly inside a snapshots_* directory and
 * canonically below an explicitly supplied application-owned root. Candidate derivation happens
 * before this check, so initial/latest siblings are each validated independently.
 */
private fun trustedSnapshotSearchFile(path: String, allowedRoots: List<File>): File? = try {
    val canonicalFile = File(path)
        .takeUnless(::isSymbolicLinkWithoutFollowing)
        ?.canonicalFile
    canonicalFile?.takeIf { file ->
        file.isFile &&
            file.canRead() &&
            file.length() > 0L &&
            file.name.endsWith(".html", ignoreCase = true) &&
            file.parentFile?.name?.startsWith("snapshots_") == true &&
            isCanonicalFileStrictlyInside(file, allowedRoots)
    }
} catch (cancellation: CancellationException) {
    throw cancellation
} catch (_: Exception) {
    null
}

private fun SnapshotData.toDashboardSearchDocument(): DashboardSearchDocument =
    DashboardSearchDocument(
        postAuthors = listOfNotBlank(author),
        postTitles = listOfNotBlank(title),
        postContents = bodyElements
            .filterIsInstance<BodyElement.TextElement>()
            .mapNotNull { it.text.takeIf(String::isNotBlank) },
        commentAuthors = comments.mapNotNull { it.author.takeIf(String::isNotBlank) },
        commentContents = comments.mapNotNull { it.content.takeIf(String::isNotBlank) },
    )

internal fun dashboardSearchMatchCodes(
    document: DashboardSearchDocument,
    query: String,
    enabledCodes: Set<DashboardSearchMatchCode> = ALL_DASHBOARD_SEARCH_MATCH_CODES,
): List<DashboardSearchMatchCode> {
    val trimmedQuery = query.trim()
    if (trimmedQuery.isBlank()) return emptyList()

    fun List<String>.containsQuery(): Boolean =
        any { it.contains(trimmedQuery, ignoreCase = true) }

    return DashboardSearchMatchCode.entries.filter { code ->
        if (code !in enabledCodes) return@filter false
        when (code) {
            DashboardSearchMatchCode.POST_NUMBER -> document.postNumbers.containsQuery()
            DashboardSearchMatchCode.POST_AUTHOR -> document.postAuthors.containsQuery()
            DashboardSearchMatchCode.POST_TITLE -> document.postTitles.containsQuery()
            DashboardSearchMatchCode.POST_CONTENT -> document.postContents.containsQuery()
            DashboardSearchMatchCode.COMMENT_AUTHOR -> document.commentAuthors.containsQuery()
            DashboardSearchMatchCode.COMMENT_CONTENT -> document.commentContents.containsQuery()
        }
    }
}

internal fun dashboardSearchMatchLabel(
    document: DashboardSearchDocument,
    query: String
): String? {
    return dashboardSearchMatchLabel(dashboardSearchMatchCodes(document, query))
}

internal fun dashboardSearchMatchLabel(matches: List<DashboardSearchMatchCode>): String? =
    matches.distinct()
        .sortedBy(DashboardSearchMatchCode.entries::indexOf)
        .takeIf { it.isNotEmpty() }
        ?.joinToString(prefix = "일치: ", separator = ", ") { it.label }

internal fun CheckedPost.toDashboardSearchDocument(): DashboardSearchDocument =
    DashboardSearchDocument(
        postNumbers = listOfNotBlank(postNum),
        postAuthors = listOfNotBlank(author),
        postTitles = listOfNotBlank(title)
    )

internal fun BlockHistory.toDashboardSearchDocument(): DashboardSearchDocument =
    historyDashboardSearchDocument(
        postNum = postNum,
        targetType = targetType,
        targetAuthor = targetAuthor,
        targetContent = targetContent
    )

internal fun HoldHistory.toDashboardSearchDocument(): DashboardSearchDocument =
    historyDashboardSearchDocument(
        postNum = postNum,
        targetType = targetType,
        targetAuthor = targetAuthor,
        targetContent = targetContent
    )

private fun historyDashboardSearchDocument(
    postNum: String?,
    targetType: String,
    targetAuthor: String?,
    targetContent: String?
): DashboardSearchDocument = when {
    targetType.equals("POST", ignoreCase = true) -> DashboardSearchDocument(
        postNumbers = listOfNotBlank(postNum),
        postAuthors = listOfNotBlank(targetAuthor),
        postTitles = listOfNotBlank(targetContent)
    )

    targetType.equals("COMMENT", ignoreCase = true) -> DashboardSearchDocument(
        postNumbers = listOfNotBlank(postNum),
        commentAuthors = listOfNotBlank(targetAuthor),
        commentContents = listOfNotBlank(targetContent)
    )

    else -> DashboardSearchDocument(postNumbers = listOfNotBlank(postNum))
}

private fun listOfNotBlank(value: String?): List<String> =
    if (value.isNullOrBlank()) emptyList() else listOf(value)

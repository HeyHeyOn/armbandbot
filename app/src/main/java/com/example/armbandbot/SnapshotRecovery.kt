package com.heyheyon.armbandbot

import java.io.File

internal data class SnapshotIdentity(val gallId: String, val postNum: String)
internal typealias SnapshotFileIndex = Map<String, List<File>>

internal fun mergeCheckedPostPreservingSnapshot(
    existing: CheckedPost?,
    incoming: CheckedPost
): CheckedPost {
    if (!incoming.snapshotPath.isNullOrBlank() || existing?.snapshotPath.isNullOrBlank()) {
        return incoming
    }
    return incoming.copy(snapshotPath = existing.snapshotPath)
}

internal fun findAmbiguousSnapshotIdentities(posts: List<CheckedPost>): Set<SnapshotIdentity> = posts
    .groupBy { SnapshotIdentity(it.gallId, it.postNum) }
    .filterValues { matches -> matches.map { it.gallType }.distinct().size > 1 }
    .keys

internal fun buildSnapshotFileIndex(cacheRoot: File): SnapshotFileIndex {
    if (!cacheRoot.isDirectory) return emptyMap()

    val index = mutableMapOf<String, MutableList<File>>()
    cacheRoot.listFiles()
        ?.asSequence()
        ?.filter { it.isDirectory && it.name.startsWith("snapshots_") }
        ?.flatMap { directory -> directory.listFiles()?.asSequence() ?: emptySequence() }
        ?.filter { it.isFile && it.name.endsWith(".html", ignoreCase = true) }
        ?.forEach { candidate -> index.getOrPut(candidate.name) { mutableListOf() }.add(candidate) }
    return index
}

internal fun findRecoverableSnapshotPath(
    snapshotFilesByName: SnapshotFileIndex,
    gallId: String,
    postNum: String
): String? {
    if (gallId.isBlank() || postNum.isBlank()) return null

    val latestCandidates = snapshotFilesByName["${gallId}_${postNum}_latest.html"]
        .orEmpty()
        .filter { it.isFile }
    if (latestCandidates.size > 1) return null
    latestCandidates.singleOrNull()?.let { return it.absolutePath }

    val initialCandidates = snapshotFilesByName["${gallId}_${postNum}_initial.html"]
        .orEmpty()
        .filter { it.isFile }
    if (initialCandidates.size > 1) return null
    return initialCandidates.singleOrNull()?.absolutePath
}

internal fun findRecoverableSnapshotPath(
    cacheRoot: File,
    gallId: String,
    postNum: String
): String? = findRecoverableSnapshotPath(buildSnapshotFileIndex(cacheRoot), gallId, postNum)

internal fun saveGeneralSnapshotPreservingExistingInitial(
    initialFile: File,
    latestFile: File,
    existingSnapshotPath: String?,
    html: String,
    allowedSnapshotRoots: List<File> = listOfNotNull(initialFile.parentFile?.parentFile),
): String {
    val existingInitial = existingSnapshotPath
        ?.takeIf { it.isNotBlank() }
        ?.let(::File)
        ?.asInitialSnapshotCandidate()
        ?.takeIf { candidate -> candidate.isTrustedReadableSnapshot(initialFile, allowedSnapshotRoots) }

    // A pre-1.4.5 DB path is itself the baseline identity. Keep both its bytes and its path;
    // copying it into the current bot folder would cause the DB row to be replaced on recheck.
    if (existingInitial != null) {
        latestFile.parentFile?.mkdirs()
        latestFile.writeText(html)
        return existingInitial.absolutePath
    }

    return if (!initialFile.isFile) {
        initialFile.parentFile?.mkdirs()
        initialFile.writeText(html)
        if (latestFile.exists()) latestFile.delete()
        initialFile.absolutePath
    } else {
        latestFile.writeText(html)
        latestFile.absolutePath
    }
}

private fun File.isTrustedReadableSnapshot(expectedInitial: File, allowedRoots: List<File>): Boolean {
    val canonicalCandidate = runCatching { canonicalFile }.getOrNull() ?: return false
    if (!canonicalCandidate.isFile || !canonicalCandidate.canRead() || canonicalCandidate.length() <= 0L) return false
    val expectedPrefix = expectedInitial.name.removeSuffix("_initial.html")
    if (!canonicalCandidate.name.startsWith("${expectedPrefix}_initial") ||
        !canonicalCandidate.name.endsWith(".html", ignoreCase = true)) return false
    val snapshotDirectory = canonicalCandidate.parentFile ?: return false
    if (!snapshotDirectory.name.startsWith("snapshots_")) return false
    val inAllowedRoot = allowedRoots.any { root ->
        val canonicalRoot = runCatching { root.canonicalFile }.getOrNull() ?: return@any false
        canonicalCandidate.toPath().startsWith(canonicalRoot.toPath())
    }
    if (!inAllowedRoot) return false
    return runCatching { canonicalCandidate.inputStream().use { it.read() >= 0 } }.getOrDefault(false)
}

private fun File.asInitialSnapshotCandidate(): File? {
    val htmlSuffix = ".html"
    if (!name.endsWith(htmlSuffix, ignoreCase = true)) return null

    val stem = name.dropLast(htmlSuffix.length)
    val lowerStem = stem.lowercase()
    fun numberedSuffixAfter(marker: String): Pair<Int, String>? {
        val markerIndex = lowerStem.lastIndexOf(marker)
        if (markerIndex < 0) return null
        val suffix = stem.substring(markerIndex + marker.length)
        val isRestoreSuffix = suffix.isEmpty() ||
            (suffix.startsWith('_') && suffix.length > 1 && suffix.drop(1).all(Char::isDigit))
        return if (isRestoreSuffix) markerIndex to suffix else null
    }

    numberedSuffixAfter("_initial")?.let { return this }
    val (latestIndex, restoreSuffix) = numberedSuffixAfter("_latest") ?: return null
    val initialSibling = File(
        parentFile,
        stem.substring(0, latestIndex) + "_initial" + restoreSuffix + htmlSuffix
    )
    return initialSibling.takeIf { it.isFile } ?: this
}

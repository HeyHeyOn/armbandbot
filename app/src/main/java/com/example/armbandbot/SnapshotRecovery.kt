package com.heyheyon.armbandbot

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileOutputStream

internal data class SnapshotIdentity(
    val scopeId: String,
    val gallType: String,
    val gallId: String,
    val postNum: String,
)
internal typealias SnapshotFileIndex = Map<String, List<File>>

internal fun snapshotLockKey(
    scopeId: String,
    actorBotId: String,
    gallType: String,
    gallId: String,
    postNum: String,
): String {
    require(scopeId.isNotBlank()) { "scopeId가 필요합니다." }
    require(actorBotId.isNotBlank()) { "actorBotId가 필요합니다." }
    return listOf(scopeId, actorBotId, gallType, gallId, postNum)
        .joinToString("|") { "${it.length}:$it" }
}

/** Fixed-size opaque components prevent traversal, delimiter collisions and overlong file names. */
private fun snapshotNamespaceToken(value: String): String {
    require(value.isNotBlank()) { "Snapshot namespace identity must not be blank" }
    return java.security.MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}

/** Does not create directories; the trusted writer validates containment and symlinks before mkdir. */
internal fun snapshotDirectoryForScope(cacheRoot: File, actorBotId: String, scopeId: String): File =
    File(cacheRoot, "snapshots_v2_${snapshotNamespaceToken(actorBotId)}_${snapshotNamespaceToken(scopeId)}")

/** Existing legacy and scoped directories for one actor; never follows directory symlinks. */
internal fun snapshotDirectoriesForBot(
    cacheRoot: File,
    actorBotId: String,
    symlinkPredicate: (File) -> Boolean = ::isSymbolicLinkWithoutFollowing,
): List<File> {
    val scopedPrefix = "snapshots_v2_${snapshotNamespaceToken(actorBotId)}_"
    val scopedName = Regex("${Regex.escape(scopedPrefix)}[0-9a-f]{64}")
    return cacheRoot.listFiles().orEmpty().filter { directory ->
        (directory.name == "snapshots_$actorBotId" || scopedName.matches(directory.name)) &&
            directory.isDirectory && !symlinkPredicate(directory) &&
            isCanonicalFileStrictlyInside(directory, listOf(cacheRoot)) &&
            !directory.hasSymbolicLinkBelowAllowedRoot(listOf(cacheRoot), symlinkPredicate)
    }.sortedBy { it.name }
}

internal data class SnapshotVersionPaths(val initialPath: String, val latestPath: String)

internal fun interface SnapshotFileOperations {
    fun rename(source: File, destination: File): Boolean
}

private val systemSnapshotFileOperations = SnapshotFileOperations { source, destination ->
    source.renameTo(destination)
}

private val snapshotVersionName = Regex("^(.+)_(initial|latest)(_[0-9]+)?\\.html$")
private val blockedSnapshotName = Regex("^.+_blocked_[0-9]+\\.html$")

/** Pairs plain and explicitly supported numbered restore snapshots. */
internal fun deriveSnapshotVersionPaths(snapshotPath: String): SnapshotVersionPaths? {
    val file = File(snapshotPath)
    val match = snapshotVersionName.matchEntire(file.name) ?: return null
    val prefix = match.groupValues[1]
    val suffix = match.groupValues[3]
    val parent = file.parentFile
    return SnapshotVersionPaths(
        File(parent, "${prefix}_initial${suffix}.html").path,
        File(parent, "${prefix}_latest${suffix}.html").path,
    )
}

/** The maintenance lock covers row removal, the global reference inventory, and file cleanup. */
internal fun deleteSnapshotRecordsAndFiles(
    postDao: PostDao?,
    allowedSnapshotRoots: List<File>,
    deleteRecords: (PostDao) -> List<String?>,
): Int = GlobalBotState.withDatabaseMaintenanceLock {
    val dao = checkNotNull(postDao) { "DB를 사용할 수 없습니다." }
    val candidates = deleteRecords(dao)
    // Do not catch DAO errors and treat them as an empty inventory: that would erase evidence.
    val survivors = dao.getAllSnapshotPaths()
    candidates.distinct().sumOf { path ->
        deleteSnapshotFiles(path, allowedSnapshotRoots, survivingSnapshotPaths = survivors)
    }
}

/** Protect both versions: a row naming either version owns the whole recoverable pair. */
private fun survivingSnapshotFiles(paths: List<String>?): Set<String>? {
    if (paths == null) return null
    return runCatching {
        paths.filter { it.isNotBlank() }.flatMap { path ->
            val pair = deriveSnapshotVersionPaths(path)
            if (pair == null) listOf(path) else listOf(path, pair.initialPath, pair.latestPath)
        }.map { File(it).canonicalPath }.toSet()
    }.getOrNull()
}

internal fun deleteSnapshotFiles(
    path: String?,
    allowedSnapshotRoots: List<File>,
    symlinkPredicate: (File) -> Boolean = ::isSymbolicLinkWithoutFollowing,
    survivingSnapshotPaths: List<String>? = emptyList(),
): Int {
    if (path.isNullOrBlank() || allowedSnapshotRoots.isEmpty()) return 0
    val protectedFiles = survivingSnapshotFiles(survivingSnapshotPaths) ?: return 0
    val requested = File(path)
    val candidates = when {
        snapshotVersionName.matches(requested.name) -> {
            val pair = deriveSnapshotVersionPaths(requested.path) ?: return 0
            listOf(File(pair.initialPath), File(pair.latestPath))
        }
        blockedSnapshotName.matches(requested.name) -> listOf(requested)
        else -> return 0
    }
    if (candidates.any { candidate ->
            candidate.canonicalPath in protectedFiles ||
            candidate.parentFile?.name?.startsWith("snapshots_") != true ||
                !isCanonicalFileStrictlyInside(candidate, allowedSnapshotRoots) ||
                candidate.hasSymbolicLinkBelowAllowedRoot(allowedSnapshotRoots, symlinkPredicate)
        }) return 0

    var deleted = 0
    candidates.forEach { candidate ->
        if (candidate.exists() && candidate.isFile && !symlinkPredicate(candidate) && candidate.delete()) deleted++
    }
    return deleted
}

internal fun deleteTrustedSnapshotDirectoryFiles(
    cacheRoot: File,
    snapshotDirectory: File,
    olderThanMillis: Long? = null,
    symlinkPredicate: (File) -> Boolean = ::isSymbolicLinkWithoutFollowing,
    survivingSnapshotPaths: List<String>? = emptyList(),
): Int {
    val protectedFiles = survivingSnapshotFiles(survivingSnapshotPaths) ?: return 0
    if (!snapshotDirectory.name.startsWith("snapshots_") ||
        !snapshotDirectory.isDirectory ||
        symlinkPredicate(snapshotDirectory) ||
        !isCanonicalFileStrictlyInside(snapshotDirectory, listOf(cacheRoot)) ||
        snapshotDirectory.hasSymbolicLinkBelowAllowedRoot(listOf(cacheRoot), symlinkPredicate)
    ) return 0

    var deleted = 0
    snapshotDirectory.listFiles().orEmpty().forEach { candidate ->
        val trustedHtml = candidate.extension.equals("html", ignoreCase = true) &&
            candidate.isFile &&
            !symlinkPredicate(candidate) &&
            isCanonicalFileStrictlyInside(candidate, listOf(cacheRoot)) &&
            !candidate.hasSymbolicLinkBelowAllowedRoot(listOf(cacheRoot), symlinkPredicate)
        val oldEnough = olderThanMillis == null || candidate.lastModified() < olderThanMillis
        if (trustedHtml && oldEnough && candidate.canonicalPath !in protectedFiles && candidate.delete()) deleted++
    }
    if (snapshotDirectory.listFiles()?.isEmpty() == true) snapshotDirectory.delete()
    return deleted
}

internal fun copySnapshotPathToScope(
    snapshotPath: String?,
    cacheRoot: File,
    targetScopeId: String,
    symlinkPredicate: (File) -> Boolean = ::isSymbolicLinkWithoutFollowing,
): String? {
    if (snapshotPath.isNullOrBlank()) return null
    require(Regex("[A-Za-z0-9._-]{1,96}").matches(targetScopeId)) { "안전하지 않은 검사 범위 ID입니다." }
    val requested = File(snapshotPath)
    val sources = when {
        snapshotVersionName.matches(requested.name) -> {
            val pair = deriveSnapshotVersionPaths(requested.path) ?: return null
            listOf(File(pair.initialPath), File(pair.latestPath))
        }
        blockedSnapshotName.matches(requested.name) -> listOf(requested)
        else -> return null
    }
    val existingSources = sources.filter { source ->
        source.exists() && source.isFile &&
            source.parentFile?.name?.startsWith("snapshots_") == true &&
            !symlinkPredicate(source) &&
            isCanonicalFileStrictlyInside(source, listOf(cacheRoot)) &&
            !source.hasSymbolicLinkBelowAllowedRoot(listOf(cacheRoot), symlinkPredicate)
    }
    if (existingSources.none { it.canonicalFile == requested.canonicalFile }) return null

    val targetDirectory = File(cacheRoot, "snapshots_$targetScopeId")
    if (!targetDirectory.exists()) check(targetDirectory.mkdirs()) { "검사 범위 스냅샷 폴더를 만들 수 없습니다." }
    check(targetDirectory.isDirectory && !symlinkPredicate(targetDirectory)) { "검사 범위 스냅샷 폴더가 안전하지 않습니다." }
    check(isCanonicalFileStrictlyInside(targetDirectory, listOf(cacheRoot))) { "검사 범위 스냅샷 폴더가 캐시 밖에 있습니다." }

    var selectedDestination: File? = null
    existingSources.forEach { source ->
        val destination = File(targetDirectory, source.name)
        check(!symlinkPredicate(destination) && isCanonicalFileStrictlyInside(destination, listOf(cacheRoot))) {
            "검사 범위 스냅샷 대상이 안전하지 않습니다."
        }
        source.copyTo(destination, overwrite = true)
        if (source.canonicalFile == requested.canonicalFile) selectedDestination = destination
    }
    return selectedDestination?.absolutePath
}

internal fun collectTrustedDatabaseBackupSnapshotFiles(
    cacheRoot: File,
    databaseSnapshotPaths: Iterable<String?>,
    symlinkPredicate: (File) -> Boolean = ::isSymbolicLinkWithoutFollowing,
): List<File> {
    val candidates = linkedSetOf<File>()
    databaseSnapshotPaths.forEach { path ->
        if (path.isNullOrBlank()) return@forEach
        val requested = File(path)
        candidates.add(requested)
        deriveSnapshotVersionPaths(requested.path)?.let { pair ->
            candidates.add(File(pair.initialPath))
            candidates.add(File(pair.latestPath))
        }
    }
    cacheRoot.listFiles().orEmpty()
        .filter { directory ->
            directory.name.startsWith("snapshots_") && directory.isDirectory &&
                !symlinkPredicate(directory) &&
                isCanonicalFileStrictlyInside(directory, listOf(cacheRoot)) &&
                !directory.hasSymbolicLinkBelowAllowedRoot(listOf(cacheRoot), symlinkPredicate)
        }
        .forEach { directory -> candidates.addAll(directory.listFiles().orEmpty()) }

    return candidates.mapNotNull { candidate ->
        val trusted = candidate.parentFile?.name?.startsWith("snapshots_") == true &&
            candidate.extension.equals("html", ignoreCase = true) &&
            candidate.isFile &&
            !symlinkPredicate(candidate) &&
            isCanonicalFileStrictlyInside(candidate, listOf(cacheRoot)) &&
            !candidate.hasSymbolicLinkBelowAllowedRoot(listOf(cacheRoot), symlinkPredicate)
        if (trusted) runCatching { candidate.canonicalFile }.getOrNull() else null
    }.distinctBy { it.path }
}

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
    .groupBy { it.gallId to it.postNum }
    .filterValues { matches ->
        matches.map { SnapshotIdentity(it.scopeId, it.gallType, it.gallId, it.postNum) }
            .distinct().size > 1
    }
    .values
    .flatten()
    .map { SnapshotIdentity(it.scopeId, it.gallType, it.gallId, it.postNum) }
    .toSet()

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
    symlinkPredicate: (File) -> Boolean = ::isSymbolicLinkWithoutFollowing,
    relocateToRequestedDirectory: Boolean = false,
): String {
    val expectedPrefix = initialFile.name.removeSuffix("_initial.html").takeIf { prefix ->
        prefix.isNotBlank() && initialFile.name == "${prefix}_initial.html" &&
            latestFile.name == "${prefix}_latest.html"
    } ?: error("Snapshot destinations do not have the expected initial/latest names")
    validateSnapshotWriteTarget(initialFile, expectedPrefix, allowedSnapshotRoots, symlinkPredicate)
    validateSnapshotWriteTarget(latestFile, expectedPrefix, allowedSnapshotRoots, symlinkPredicate)
    val trustedExisting = existingSnapshotPath
        ?.takeIf { it.isNotBlank() }
        ?.let(::File)
        ?.validatedLegacySnapshot(expectedPrefix, allowedSnapshotRoots, symlinkPredicate)

    if (relocateToRequestedDirectory) {
        require(initialFile.parentFile?.canonicalFile == latestFile.parentFile?.canonicalFile) {
            "Relocated snapshot versions must share the requested directory"
        }
        if (trustedExisting != null) {
            // A scope's already-established baseline wins when returning to that scope. Otherwise
            // copy the oldest trusted bytes without creating or updating anything beside the source.
            if (!initialFile.exists()) {
                val sourcePair = deriveSnapshotVersionPaths(trustedExisting.path)
                    ?: error("Validated snapshot did not have a supported version name")
                val sourceInitial = File(sourcePair.initialPath)
                val baseline = sourceInitial.validatedLegacySnapshot(
                    expectedPrefix, allowedSnapshotRoots, symlinkPredicate,
                ) ?: run {
                    check(!sourceInitial.exists() && !symlinkPredicate(sourceInitial)) {
                        "Refusing to copy an untrusted initial sibling"
                    }
                    trustedExisting
                }
                writeSnapshotFileSafely(
                    initialFile, baseline.readBytes(), allowedSnapshotRoots,
                    replaceExisting = false, symlinkPredicate = symlinkPredicate,
                )
            } else {
                check(initialFile.validatedLegacySnapshot(
                    expectedPrefix, allowedSnapshotRoots, symlinkPredicate,
                ) != null) { "Requested initial snapshot is not a trusted baseline" }
            }
            writeSnapshotFileSafely(
                latestFile, html.toByteArray(Charsets.UTF_8), allowedSnapshotRoots,
                replaceExisting = true, symlinkPredicate = symlinkPredicate,
            )
            return initialFile.canonicalPath
        }
    }

    if (trustedExisting != null) {
        val pair = deriveSnapshotVersionPaths(trustedExisting.path)
            ?: error("Validated snapshot did not have a supported version name")
        // Derive both destinations from the canonical trusted parent, but never follow a sibling
        // symlink while selecting a write target.
        val initialCandidate = File(pair.initialPath)
        val latestCandidate = File(pair.latestPath)
        val trustedName = snapshotVersionName.matchEntire(trustedExisting.name)
            ?: error("Validated snapshot did not have a supported version name")
        val baseline = if (trustedName.groupValues[2] == "initial") {
            trustedExisting
        } else {
            initialCandidate.validatedLegacySnapshot(expectedPrefix, allowedSnapshotRoots, symlinkPredicate)
                ?: run {
                    if (initialCandidate.exists() || symlinkPredicate(initialCandidate)) {
                        error("Refusing to overwrite an untrusted initial sibling")
                    }
                    // A latest-only legacy row still contains the oldest bytes we know about. Commit
                    // those bytes to a new baseline before attempting to update latest.
                    writeSnapshotFileSafely(
                        initialCandidate,
                        trustedExisting.readBytes(),
                        allowedSnapshotRoots,
                        replaceExisting = false,
                        symlinkPredicate = symlinkPredicate,
                    )
                    initialCandidate.validatedLegacySnapshot(expectedPrefix, allowedSnapshotRoots, symlinkPredicate)
                        ?: error("Could not preserve legacy latest as initial baseline")
                }
        }

        // Always update beside the canonical baseline (including numbered restores), so the viewer
        // can discover both versions. The API-24-safe writer preserves the old latest for rollback.
        if (symlinkPredicate(latestCandidate)) {
            error("Refusing to write latest through a symbolic link")
        }
        writeSnapshotFileSafely(
            latestCandidate,
            html.toByteArray(Charsets.UTF_8),
            allowedSnapshotRoots,
            replaceExisting = true,
            symlinkPredicate = symlinkPredicate,
        )
        return baseline.canonicalPath
    }

    return if (!initialFile.exists()) {
        writeSnapshotFileSafely(
            initialFile,
            html.toByteArray(Charsets.UTF_8),
            allowedSnapshotRoots,
            replaceExisting = false,
            symlinkPredicate = symlinkPredicate,
        )
        if (latestFile.exists() && !latestFile.delete()) error("Could not remove stale latest snapshot")
        initialFile.absolutePath
    } else {
        if (!initialFile.isFile) error("Initial snapshot is not a regular file")
        writeSnapshotFileSafely(
            latestFile,
            html.toByteArray(Charsets.UTF_8),
            allowedSnapshotRoots,
            replaceExisting = true,
            symlinkPredicate = symlinkPredicate,
        )
        latestFile.absolutePath
    }
}

/** Returns the canonical file, never the caller's traversal or symlink alias. */
private fun File.validatedLegacySnapshot(
    expectedPrefix: String,
    allowedRoots: List<File>,
    symlinkPredicate: (File) -> Boolean,
): File? {
    if (hasSymbolicLinkBelowAllowedRoot(allowedRoots, symlinkPredicate)) return null
    val canonicalCandidate = runCatching { canonicalFile }.getOrNull() ?: return null
    val exactAllowedName = Regex(
        "^${Regex.escape(expectedPrefix)}_(?:initial|latest)(?:_[0-9]+)?\\.html$"
    )
    if (!exactAllowedName.matches(canonicalCandidate.name)) return null
    if (!canonicalCandidate.isFile || !canonicalCandidate.canRead() || canonicalCandidate.length() <= 0L) return null
    val snapshotDirectory = canonicalCandidate.parentFile ?: return null
    if (!snapshotDirectory.name.startsWith("snapshots_")) return null
    if (!isCanonicalFileStrictlyInside(canonicalCandidate, allowedRoots)) return null
    return canonicalCandidate.takeIf {
        runCatching { it.inputStream().use { stream -> stream.read() >= 0 } }.getOrDefault(false)
    }
}

/** File-only canonical containment, avoiding java.nio.file (Android API 26). */
internal fun isCanonicalFileStrictlyInside(candidate: File, allowedRoots: List<File>): Boolean {
    val canonicalCandidate = runCatching { candidate.canonicalFile }.getOrNull() ?: return false
    return allowedRoots.any { root ->
        val canonicalRoot = runCatching { root.canonicalFile }.getOrNull() ?: return@any false
        generateSequence(canonicalCandidate.parentFile) { it.parentFile }.any { it == canonicalRoot }
    }
}

/**
 * Checks only path components controlled below a trusted root. The configured root and its system
 * ancestors may themselves be Android storage aliases (for example /data/user/0 vs /data/data).
 */
private fun File.hasSymbolicLinkBelowAllowedRoot(
    allowedRoots: List<File>,
    symlinkPredicate: (File) -> Boolean,
): Boolean {
    val canonicalCandidate = runCatching { canonicalFile }.getOrNull() ?: return true
    val absoluteCandidate = absoluteFile
    val anchor = allowedRoots.firstNotNullOfOrNull { root ->
        val canonicalRoot = runCatching { root.canonicalFile }.getOrNull()
            ?: return@firstNotNullOfOrNull null
        val canonicallyInside = generateSequence(canonicalCandidate.parentFile) { it.parentFile }
            .any { it == canonicalRoot }
        if (!canonicallyInside) return@firstNotNullOfOrNull null

        sequenceOf(root.absoluteFile, canonicalRoot.absoluteFile)
            .distinct()
            .firstOrNull { possibleAnchor ->
                generateSequence(absoluteCandidate.parentFile) { it.parentFile }
                    .any { parent -> parent == possibleAnchor }
            }
    } ?: return true

    return generateSequence(absoluteCandidate) { it.parentFile }
        .takeWhile { it != anchor }
        .any(symlinkPredicate)
}

/**
 * Uses lstat, available since API 21, so dangling links are rejected without following them.
 * The canonical-path fallback keeps local JVM tests useful when Android's Os stub is unavailable.
 */
internal fun isSymbolicLinkWithoutFollowing(file: File): Boolean {
    try {
        return OsConstants.S_ISLNK(Os.lstat(file.absolutePath).st_mode)
    } catch (error: ErrnoException) {
        if (error.errno == OsConstants.ENOENT || error.errno == OsConstants.ENOTDIR) return false
        return true
    } catch (error: Throwable) {
        val androidOsUnavailable = error is LinkageError || error is NullPointerException ||
            (error is RuntimeException &&
                (error.message == "Stub!" || error.message?.contains("not mocked") == true))
        if (!androidOsUnavailable) return true
    }
    // java.nio.file is unavailable on Android API 24-25, so use reflection only as a local-JVM
    // fallback after Android's API-21 lstat path proved unavailable.
    return runCatching {
        val path = File::class.java.getMethod("toPath").invoke(file)
        val filesClass = Class.forName("java.nio.file.Files")
        val pathClass = Class.forName("java.nio.file.Path")
        filesClass.getMethod("isSymbolicLink", pathClass).invoke(null, path) as Boolean
    }.getOrElse {
        runCatching { file.absoluteFile.path != file.canonicalFile.path }.getOrDefault(true)
    }
}

private fun validateSnapshotWriteTarget(
    target: File,
    expectedPrefix: String,
    allowedRoots: List<File>,
    symlinkPredicate: (File) -> Boolean,
) {
    val exactAllowedName = Regex(
        "^${Regex.escape(expectedPrefix)}_(?:initial|latest)(?:_[0-9]+)?\\.html$"
    )
    require(exactAllowedName.matches(target.name)) { "Snapshot target has an unexpected name" }
    require(target.parentFile?.name?.startsWith("snapshots_") == true) {
        "Snapshot target is not in a snapshot directory"
    }
    require(isCanonicalFileStrictlyInside(target, allowedRoots)) { "Snapshot target is outside trusted roots" }
    require(!target.hasSymbolicLinkBelowAllowedRoot(allowedRoots, symlinkPredicate)) {
        "Snapshot target path contains a symbolic link"
    }
}

/**
 * Writes and fsyncs a same-directory temporary file. java.io has no API-24 atomic replace primitive,
 * so latest files use a same-directory rename backup and rollback. Initial baselines are create-only
 * and are never intentionally replaced. All rename destinations are revalidated trusted siblings.
 */
internal fun writeSnapshotFileSafely(
    target: File,
    bytes: ByteArray,
    allowedRoots: List<File>,
    replaceExisting: Boolean,
    symlinkPredicate: (File) -> Boolean = ::isSymbolicLinkWithoutFollowing,
    fileOperations: SnapshotFileOperations = systemSnapshotFileOperations,
) {
    val match = snapshotVersionName.matchEntire(target.name)
        ?: error("Snapshot target has an unsupported name")
    val expectedPrefix = match.groupValues[1]
    validateSnapshotWriteTarget(target, expectedPrefix, allowedRoots, symlinkPredicate)
    val parent = target.parentFile ?: error("Snapshot has no parent directory")
    if (!parent.exists() && !parent.mkdirs()) error("Could not create snapshot directory")
    validateSnapshotWriteTarget(target, expectedPrefix, allowedRoots, symlinkPredicate)
    val temp = File.createTempFile(".snapshot-", ".tmp", parent)
    var backup: File? = null
    var preserveBackup = false
    try {
        FileOutputStream(temp).use { output ->
            output.write(bytes)
            output.flush()
            output.fd.sync()
        }
        validateSnapshotWriteTarget(target, expectedPrefix, allowedRoots, symlinkPredicate)

        if (target.exists()) {
            if (!replaceExisting) error("Refusing to replace immutable initial snapshot")
            if (!target.isFile || symlinkPredicate(target)) error("Refusing to replace an untrusted snapshot target")
            backup = File.createTempFile(".snapshot-backup-", ".tmp", parent)
            if (!backup.delete()) error("Could not prepare snapshot rollback path")
            if (!fileOperations.rename(target, backup)) error("Could not preserve previous latest snapshot")
            if (!fileOperations.rename(temp, target)) {
                if (!fileOperations.rename(backup, target)) {
                    preserveBackup = true
                    error("Latest replacement and rollback failed; previous bytes remain at ${backup.absolutePath}")
                }
                backup = null
                error("Could not replace latest snapshot; previous bytes were restored")
            }
            if (!backup.delete()) error("Latest was replaced but its rollback file could not be removed")
            backup = null
        } else {
            if (symlinkPredicate(target)) error("Refusing to write through a dangling symbolic link")
            if (!fileOperations.rename(temp, target)) error("Could not install snapshot")
        }
    } finally {
        if (temp.exists()) temp.delete()
        // Keep a backup when rollback itself failed; deleting it would lose the previous latest.
        if (!preserveBackup) backup?.delete()
    }
}

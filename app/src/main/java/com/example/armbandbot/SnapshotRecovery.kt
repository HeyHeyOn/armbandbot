package com.heyheyon.armbandbot

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileOutputStream

internal data class SnapshotIdentity(val gallId: String, val postNum: String)
internal typealias SnapshotFileIndex = Map<String, List<File>>

internal data class SnapshotVersionPaths(val initialPath: String, val latestPath: String)

internal fun interface SnapshotFileOperations {
    fun rename(source: File, destination: File): Boolean
}

private val systemSnapshotFileOperations = SnapshotFileOperations { source, destination ->
    source.renameTo(destination)
}

private val snapshotVersionName = Regex("^(.+)_(initial|latest)(_[0-9]+)?\\.html$")

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
    symlinkPredicate: (File) -> Boolean = ::isSymbolicLinkWithoutFollowing,
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
private fun isSymbolicLinkWithoutFollowing(file: File): Boolean {
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
    if (File.separatorChar == '\\') return false
    return runCatching { file.absoluteFile.path != file.canonicalFile.path }.getOrDefault(true)
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

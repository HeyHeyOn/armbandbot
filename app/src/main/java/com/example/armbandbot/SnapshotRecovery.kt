package com.heyheyon.armbandbot

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal data class SnapshotIdentity(val gallId: String, val postNum: String)
internal typealias SnapshotFileIndex = Map<String, List<File>>

internal data class SnapshotVersionPaths(val initialPath: String, val latestPath: String)

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
): String {
    val expectedPrefix = initialFile.name.removeSuffix("_initial.html")
    val trustedExisting = existingSnapshotPath
        ?.takeIf { it.isNotBlank() }
        ?.let(::File)
        ?.validatedLegacySnapshot(expectedPrefix, allowedSnapshotRoots)

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
            initialCandidate.validatedLegacySnapshot(expectedPrefix, allowedSnapshotRoots)
                ?: run {
                    if (initialCandidate.exists() || Files.isSymbolicLink(initialCandidate.toPath())) {
                        error("Refusing to overwrite an untrusted initial sibling")
                    }
                    // A latest-only legacy row still contains the oldest bytes we know about. Commit
                    // those bytes to a new baseline before attempting to update latest.
                    atomicWrite(initialCandidate, trustedExisting.readBytes())
                    initialCandidate.validatedLegacySnapshot(expectedPrefix, allowedSnapshotRoots)
                        ?: error("Could not preserve legacy latest as initial baseline")
                }
        }

        // Always update beside the canonical baseline (including numbered restores), so the viewer
        // can discover both versions. Atomic replacement cannot partially overwrite the old latest.
        if (Files.isSymbolicLink(latestCandidate.toPath())) {
            error("Refusing to write latest through a symbolic link")
        }
        atomicWrite(latestCandidate, html.toByteArray(Charsets.UTF_8))
        return baseline.canonicalPath
    }

    return if (!initialFile.isFile) {
        atomicWrite(initialFile, html.toByteArray(Charsets.UTF_8))
        if (latestFile.exists()) latestFile.delete()
        initialFile.absolutePath
    } else {
        atomicWrite(latestFile, html.toByteArray(Charsets.UTF_8))
        latestFile.absolutePath
    }
}

/** Returns the canonical file, never the caller's traversal or symlink alias. */
private fun File.validatedLegacySnapshot(expectedPrefix: String, allowedRoots: List<File>): File? {
    val canonicalCandidate = runCatching { canonicalFile }.getOrNull() ?: return null
    val exactAllowedName = Regex(
        "^${Regex.escape(expectedPrefix)}_(?:initial|latest)(?:_[0-9]+)?\\.html$"
    )
    if (!exactAllowedName.matches(canonicalCandidate.name)) return null
    if (!canonicalCandidate.isFile || !canonicalCandidate.canRead() || canonicalCandidate.length() <= 0L) return null
    val snapshotDirectory = canonicalCandidate.parentFile ?: return null
    if (!snapshotDirectory.name.startsWith("snapshots_")) return null
    val inAllowedRoot = allowedRoots.any { root ->
        val canonicalRoot = runCatching { root.canonicalFile }.getOrNull() ?: return@any false
        canonicalCandidate.toPath().startsWith(canonicalRoot.toPath()) && canonicalCandidate != canonicalRoot
    }
    if (!inAllowedRoot) return null
    return canonicalCandidate.takeIf {
        runCatching { it.inputStream().use { stream -> stream.read() >= 0 } }.getOrDefault(false)
    }
}

private fun atomicWrite(target: File, bytes: ByteArray) {
    val parent = target.parentFile ?: error("Snapshot has no parent directory")
    if (!parent.exists() && !parent.mkdirs()) error("Could not create snapshot directory")
    val temp = Files.createTempFile(parent.toPath(), ".snapshot-", ".tmp")
    try {
        Files.write(temp, bytes)
        try {
            Files.move(temp, target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(temp, target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    } finally {
        Files.deleteIfExists(temp)
    }
}

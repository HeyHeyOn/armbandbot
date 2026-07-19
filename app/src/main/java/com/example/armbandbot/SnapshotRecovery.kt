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

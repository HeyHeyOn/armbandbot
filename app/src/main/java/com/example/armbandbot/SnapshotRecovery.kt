package com.heyheyon.armbandbot

import java.io.File
import java.io.FileOutputStream
import java.io.IOException

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
    html: String
): String {
    if (!initialFile.isFile) {
        val existingSnapshot = existingSnapshotPath
            ?.takeIf { it.isNotBlank() }
            ?.let(::File)
            ?.takeIf { it.isFile }
        val existingInitial = existingSnapshot?.asInitialSnapshotCandidate()
        existingInitial?.let { copySnapshotAtomically(it, initialFile) }
    }

    return if (!initialFile.isFile) {
        initialFile.writeText(html)
        if (latestFile.exists()) latestFile.delete()
        initialFile.absolutePath
    } else {
        latestFile.writeText(html)
        latestFile.absolutePath
    }
}

private fun copySnapshotAtomically(source: File, target: File) {
    if (target.isFile) return
    val parent = target.parentFile ?: throw IOException("스냅샷 저장 폴더가 없습니다.")
    parent.mkdirs()
    val temporary = File.createTempFile("${target.name}.", ".tmp", parent)
    try {
        source.inputStream().use { input ->
            FileOutputStream(temporary).use { output ->
                input.copyTo(output)
                output.fd.sync()
            }
        }
        if (!target.exists() && !temporary.renameTo(target)) {
            throw IOException("최초 스냅샷 승계 파일을 확정하지 못했습니다.")
        }
    } finally {
        if (temporary.exists()) temporary.delete()
    }
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

package com.heyheyon.armbandbot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SnapshotRecoveryTest {
    @Test
    fun mergeCheckedPostKeepsExistingSnapshotWhenIncomingPathIsMissing() {
        val existing = checkedPost(snapshotPath = "/cache/gallery_244_initial.html", title = "기존 제목")
        val incoming = checkedPost(snapshotPath = null, title = "삭제된 글")

        val merged = mergeCheckedPostPreservingSnapshot(existing, incoming)

        assertEquals("/cache/gallery_244_initial.html", merged.snapshotPath)
        assertEquals("삭제된 글", merged.title)
    }

    @Test
    fun mergeCheckedPostUsesNewSnapshotWhenIncomingPathExists() {
        val existing = checkedPost(snapshotPath = "/cache/gallery_244_initial.html")
        val incoming = checkedPost(snapshotPath = "/cache/gallery_244_latest.html")

        val merged = mergeCheckedPostPreservingSnapshot(existing, incoming)

        assertEquals("/cache/gallery_244_latest.html", merged.snapshotPath)
    }

    @Test
    fun findRecoverableSnapshotPathPrefersLatestSnapshot() {
        val cacheRoot = Files.createTempDirectory("snapshot_recovery").toFile()
        val botDir = File(cacheRoot, "snapshots_botA").apply { mkdirs() }
        File(botDir, "armbandbot_244_initial.html").writeText("initial")
        val latest = File(botDir, "armbandbot_244_latest.html").apply { writeText("latest") }

        val recovered = findRecoverableSnapshotPath(cacheRoot, "armbandbot", "244")

        assertEquals(latest.absolutePath, recovered)
        cacheRoot.deleteRecursively()
    }

    @Test
    fun findRecoverableSnapshotPathFallsBackToInitialAndIgnoresOtherPostNumbers() {
        val cacheRoot = Files.createTempDirectory("snapshot_recovery").toFile()
        val botDir = File(cacheRoot, "snapshots_botA").apply { mkdirs() }
        File(botDir, "armbandbot_24_latest.html").writeText("other")
        val initial = File(botDir, "armbandbot_244_initial.html").apply { writeText("initial") }

        val recovered = findRecoverableSnapshotPath(cacheRoot, "armbandbot", "244")

        assertEquals(initial.absolutePath, recovered)
        cacheRoot.deleteRecursively()
    }

    @Test
    fun findRecoverableSnapshotPathReturnsNullWhenNoSnapshotExists() {
        val cacheRoot = Files.createTempDirectory("snapshot_recovery").toFile()

        val recovered = findRecoverableSnapshotPath(cacheRoot, "armbandbot", "244")

        assertNull(recovered)
        cacheRoot.deleteRecursively()
    }

    @Test
    fun findRecoverableSnapshotPathReturnsNullWhenSameNameExistsInMultipleDirectories() {
        val cacheRoot = Files.createTempDirectory("snapshot_recovery").toFile()
        val firstDir = File(cacheRoot, "snapshots_botA").apply { mkdirs() }
        val secondDir = File(cacheRoot, "snapshots_botB").apply { mkdirs() }
        File(firstDir, "armbandbot_244_latest.html").writeText("first")
        File(secondDir, "armbandbot_244_latest.html").writeText("second")

        val recovered = findRecoverableSnapshotPath(cacheRoot, "armbandbot", "244")

        assertNull(recovered)
        cacheRoot.deleteRecursively()
    }

    @Test
    fun findRecoverableSnapshotPathFallsBackWhenIndexedLatestWasDeleted() {
        val cacheRoot = Files.createTempDirectory("snapshot_recovery").toFile()
        val botDir = File(cacheRoot, "snapshots_botA").apply { mkdirs() }
        val initial = File(botDir, "armbandbot_244_initial.html").apply { writeText("initial") }
        val latest = File(botDir, "armbandbot_244_latest.html").apply { writeText("latest") }
        val index = buildSnapshotFileIndex(cacheRoot)
        latest.delete()

        val recovered = findRecoverableSnapshotPath(index, "armbandbot", "244")

        assertEquals(initial.absolutePath, recovered)
        cacheRoot.deleteRecursively()
    }

    @Test
    fun firstRecheckAfterRestoreKeepsImportedInitialAndWritesCurrentAsLatest() {
        val cacheRoot = Files.createTempDirectory("snapshot_recheck").toFile()
        val importedDir = File(cacheRoot, "snapshots_imported").apply { mkdirs() }
        val activeDir = File(cacheRoot, "snapshots_botA").apply { mkdirs() }
        val importedInitial = File(importedDir, "armbandbot_244_initial.html").apply {
            writeText("original snapshot")
        }
        val activeInitial = File(activeDir, "armbandbot_244_initial.html")
        val activeLatest = File(activeDir, "armbandbot_244_latest.html")

        val savedPath = saveGeneralSnapshotPreservingExistingInitial(
            initialFile = activeInitial,
            latestFile = activeLatest,
            existingSnapshotPath = importedInitial.absolutePath,
            html = "rechecked snapshot"
        )

        assertEquals(activeLatest.absolutePath, savedPath)
        assertEquals("original snapshot", activeInitial.readText())
        assertEquals("rechecked snapshot", activeLatest.readText())
        cacheRoot.deleteRecursively()
    }

    @Test
    fun firstRecheckUsesInitialSiblingWhenRestoredPathPointsToLatest() {
        val cacheRoot = Files.createTempDirectory("snapshot_recheck_latest").toFile()
        val importedDir = File(cacheRoot, "snapshots_imported").apply { mkdirs() }
        File(importedDir, "armbandbot_244_initial.html").writeText("original snapshot")
        val importedLatest = File(importedDir, "armbandbot_244_latest.html").apply {
            writeText("previous latest snapshot")
        }
        val activeDir = File(cacheRoot, "snapshots_botA").apply { mkdirs() }
        val activeInitial = File(activeDir, "armbandbot_244_initial.html")
        val activeLatest = File(activeDir, "armbandbot_244_latest.html")

        saveGeneralSnapshotPreservingExistingInitial(
            initialFile = activeInitial,
            latestFile = activeLatest,
            existingSnapshotPath = importedLatest.absolutePath,
            html = "rechecked snapshot"
        )

        assertEquals("original snapshot", activeInitial.readText())
        assertEquals("rechecked snapshot", activeLatest.readText())
        cacheRoot.deleteRecursively()
    }

    @Test
    fun firstRecheckFindsNumberedInitialSiblingForRepeatedRestore() {
        val cacheRoot = Files.createTempDirectory("snapshot_recheck_numbered").toFile()
        val importedDir = File(cacheRoot, "snapshots_imported").apply { mkdirs() }
        File(importedDir, "armbandbot_244_initial_2.html").writeText("numbered original snapshot")
        val importedLatest = File(importedDir, "armbandbot_244_latest_2.html").apply {
            writeText("numbered previous latest")
        }
        val activeDir = File(cacheRoot, "snapshots_botA").apply { mkdirs() }
        val activeInitial = File(activeDir, "armbandbot_244_initial.html")
        val activeLatest = File(activeDir, "armbandbot_244_latest.html")

        saveGeneralSnapshotPreservingExistingInitial(
            initialFile = activeInitial,
            latestFile = activeLatest,
            existingSnapshotPath = importedLatest.absolutePath,
            html = "rechecked snapshot"
        )

        assertEquals("numbered original snapshot", activeInitial.readText())
        assertEquals("rechecked snapshot", activeLatest.readText())
        cacheRoot.deleteRecursively()
    }

    @Test
    fun newPostWithoutExistingSnapshotCreatesInitialAndClearsStaleLatest() {
        val cacheRoot = Files.createTempDirectory("snapshot_new_post").toFile()
        val activeDir = File(cacheRoot, "snapshots_botA").apply { mkdirs() }
        val activeInitial = File(activeDir, "armbandbot_244_initial.html")
        val activeLatest = File(activeDir, "armbandbot_244_latest.html").apply { writeText("stale") }

        val savedPath = saveGeneralSnapshotPreservingExistingInitial(
            initialFile = activeInitial,
            latestFile = activeLatest,
            existingSnapshotPath = null,
            html = "first snapshot"
        )

        assertEquals(activeInitial.absolutePath, savedPath)
        assertEquals("first snapshot", activeInitial.readText())
        assertTrue(!activeLatest.exists())
        cacheRoot.deleteRecursively()
    }

    @Test
    fun activeInitialIsNeverOverwrittenByLaterRecheck() {
        val cacheRoot = Files.createTempDirectory("snapshot_existing_active").toFile()
        val activeDir = File(cacheRoot, "snapshots_botA").apply { mkdirs() }
        val activeInitial = File(activeDir, "armbandbot_244_initial.html").apply {
            writeText("active original")
        }
        val activeLatest = File(activeDir, "armbandbot_244_latest.html")

        val savedPath = saveGeneralSnapshotPreservingExistingInitial(
            initialFile = activeInitial,
            latestFile = activeLatest,
            existingSnapshotPath = null,
            html = "later snapshot"
        )

        assertEquals(activeLatest.absolutePath, savedPath)
        assertEquals("active original", activeInitial.readText())
        assertEquals("later snapshot", activeLatest.readText())
        cacheRoot.deleteRecursively()
    }

    @Test
    fun ambiguousGalleryTypesAreExcludedFromAutomaticRecovery() {
        val normal = checkedPost(snapshotPath = null).copy(gallType = "G")
        val minor = checkedPost(snapshotPath = null).copy(gallType = "MI")

        val ambiguous = findAmbiguousSnapshotIdentities(listOf(normal, minor))

        assertTrue(SnapshotIdentity("armbandbot", "244") in ambiguous)
    }

    private fun checkedPost(snapshotPath: String?, title: String = "제목") = CheckedPost(
        gallType = "MI",
        gallId = "armbandbot",
        postNum = "244",
        commentCount = 0,
        title = title,
        snapshotPath = snapshotPath
    )
}

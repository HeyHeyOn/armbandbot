package com.heyheyon.armbandbot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SnapshotRecoveryTest {
    @Test
    fun canonicalContainmentIsStrictAndDoesNotAcceptPrefixCollisions() {
        val parent = Files.createTempDirectory("snapshot_containment").toFile()
        val trusted = File(parent, "cache").apply { mkdirs() }
        val inside = File(trusted, "snapshots_bot/armbandbot_244_initial.html")
        val prefixCollision = File(parent, "cache-evil/snapshots_bot/armbandbot_244_initial.html")

        assertTrue(isCanonicalFileStrictlyInside(inside, listOf(trusted)))
        assertFalse(isCanonicalFileStrictlyInside(trusted, listOf(trusted)))
        assertFalse(isCanonicalFileStrictlyInside(prefixCollision, listOf(trusted)))
        parent.deleteRecursively()
    }

    @Test
    fun injectedSymlinkPredicateRejectsExistingAndDanglingLegacyTargets() {
        listOf(true, false).forEach { linkHasTarget ->
            val cacheRoot = Files.createTempDirectory("snapshot_symlink_rejection").toFile()
            val importedDir = File(cacheRoot, "snapshots_imported").apply { mkdirs() }
            val suspicious = File(importedDir, "armbandbot_244_initial.html")
            if (linkHasTarget) suspicious.writeText("must not be trusted")
            val activeDir = File(cacheRoot, "snapshots_current").apply { mkdirs() }
            val activeInitial = File(activeDir, "armbandbot_244_initial.html")

            val saved = saveGeneralSnapshotPreservingExistingInitial(
                activeInitial,
                File(activeDir, "armbandbot_244_latest.html"),
                suspicious.absolutePath,
                "safe baseline",
                listOf(cacheRoot),
                symlinkPredicate = { it.absolutePath == suspicious.absolutePath },
            )

            assertEquals(activeInitial.absolutePath, saved)
            assertEquals("safe baseline", activeInitial.readText())
            if (linkHasTarget) assertEquals("must not be trusted", suspicious.readText())
            cacheRoot.deleteRecursively()
        }
    }

    @Test
    fun failedLatestReplacementRollsBackOldBytesAndCleansTemporaryFiles() {
        val cacheRoot = Files.createTempDirectory("snapshot_rollback").toFile()
        val directory = File(cacheRoot, "snapshots_bot").apply { mkdirs() }
        val latest = File(directory, "armbandbot_244_latest.html").apply { writeText("old latest") }
        var renameCalls = 0
        val operations = object : SnapshotFileOperations {
            override fun rename(source: File, destination: File): Boolean {
                renameCalls++
                return if (renameCalls == 2) false else source.renameTo(destination)
            }
        }

        val failure = runCatching {
            writeSnapshotFileSafely(
                latest,
                "new latest".toByteArray(),
                listOf(cacheRoot),
                replaceExisting = true,
                symlinkPredicate = { false },
                fileOperations = operations,
            )
        }.exceptionOrNull()

        assertTrue(failure != null)
        assertEquals("old latest", latest.readText())
        assertTrue(directory.listFiles().orEmpty().none { it.name.startsWith(".snapshot-") })
        cacheRoot.deleteRecursively()
    }

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

        assertEquals(importedInitial.canonicalPath, savedPath)
        assertTrue(!activeInitial.exists())
        assertEquals("original snapshot", importedInitial.readText())
        assertEquals("rechecked snapshot", File(importedDir, "armbandbot_244_latest.html").readText())
        assertTrue(!activeLatest.exists())
        cacheRoot.deleteRecursively()
    }

    @Test
    fun firstRecheckUsesInitialSiblingWhenRestoredPathPointsToLatest() {
        val cacheRoot = Files.createTempDirectory("snapshot_recheck_latest").toFile()
        val importedDir = File(cacheRoot, "snapshots_imported").apply { mkdirs() }
        val importedInitial = File(importedDir, "armbandbot_244_initial.html").apply { writeText("original snapshot") }
        val importedLatest = File(importedDir, "armbandbot_244_latest.html").apply {
            writeText("previous latest snapshot")
        }
        val activeDir = File(cacheRoot, "snapshots_botA").apply { mkdirs() }
        val activeInitial = File(activeDir, "armbandbot_244_initial.html")
        val activeLatest = File(activeDir, "armbandbot_244_latest.html")

        val savedPath = saveGeneralSnapshotPreservingExistingInitial(
            initialFile = activeInitial,
            latestFile = activeLatest,
            existingSnapshotPath = importedLatest.absolutePath,
            html = "rechecked snapshot"
        )

        assertTrue(!activeInitial.exists())
        assertEquals(importedInitial.canonicalPath, savedPath)
        assertEquals("original snapshot", importedInitial.readText())
        assertEquals("rechecked snapshot", importedLatest.readText())
        assertTrue(!activeLatest.exists())
        cacheRoot.deleteRecursively()
    }

    @Test
    fun firstRecheckFindsNumberedInitialSiblingForRepeatedRestore() {
        val cacheRoot = Files.createTempDirectory("snapshot_recheck_numbered").toFile()
        val importedDir = File(cacheRoot, "snapshots_imported").apply { mkdirs() }
        val importedInitial = File(importedDir, "armbandbot_244_initial_2.html").apply { writeText("numbered original snapshot") }
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

        assertTrue(!activeInitial.exists())
        assertEquals("numbered original snapshot", importedInitial.readText())
        assertEquals("rechecked snapshot", importedLatest.readText())
        assertTrue(!activeLatest.exists())
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
    fun legacyActiveInitialOutsideCurrentBotFolderKeepsItsBytesAndDbPath() {
        val cacheRoot = Files.createTempDirectory("snapshot_legacy_active").toFile()
        val legacyDir = File(cacheRoot, "snapshots_legacyBot").apply { mkdirs() }
        val legacyInitial = File(legacyDir, "armbandbot_244_initial.html").apply {
            writeBytes("legacy baseline".toByteArray())
        }
        val originalBytes = legacyInitial.readBytes()
        val activeDir = File(cacheRoot, "snapshots_currentBot").apply { mkdirs() }
        val activeInitial = File(activeDir, "armbandbot_244_initial.html")
        val activeLatest = File(activeDir, "armbandbot_244_latest.html")

        val savedPath = saveGeneralSnapshotPreservingExistingInitial(
            initialFile = activeInitial,
            latestFile = activeLatest,
            existingSnapshotPath = legacyInitial.absolutePath,
            html = "PUM source B",
            allowedSnapshotRoots = listOf(cacheRoot)
        )

        assertEquals(legacyInitial.canonicalPath, savedPath)
        assertTrue(!activeInitial.exists())
        assertTrue(originalBytes.contentEquals(legacyInitial.readBytes()))
        assertEquals("PUM source B", File(legacyDir, "armbandbot_244_latest.html").readText())
        assertTrue(!activeLatest.exists())
        cacheRoot.deleteRecursively()
    }

    @Test
    fun trustedLegacyLatestOnlyBecomesBaselineBeforeLatestIsUpdated() {
        val cacheRoot = Files.createTempDirectory("snapshot_legacy_latest_only").toFile()
        val legacyDir = File(cacheRoot, "snapshots_legacy").apply { mkdirs() }
        val legacyLatest = File(legacyDir, "armbandbot_244_latest.html").apply { writeBytes("old bytes".toByteArray()) }
        val activeDir = File(cacheRoot, "snapshots_current").apply { mkdirs() }

        val savedPath = saveGeneralSnapshotPreservingExistingInitial(
            File(activeDir, "armbandbot_244_initial.html"),
            File(activeDir, "armbandbot_244_latest.html"),
            legacyLatest.absolutePath,
            "current latest",
            listOf(cacheRoot),
        )

        val baseline = File(legacyDir, "armbandbot_244_initial.html")
        assertEquals(baseline.canonicalPath, savedPath)
        assertEquals("old bytes", baseline.readText())
        assertEquals("current latest", legacyLatest.readText())
        cacheRoot.deleteRecursively()
    }

    @Test
    fun exactLegacyFilenameAllowlistRejectsBackupAndForeignPrefix() {
        listOf("armbandbot_244_initial_backup_initial.html", "foreign_244_initial.html").forEach { name ->
            val cacheRoot = Files.createTempDirectory("snapshot_bad_name").toFile()
            val importedDir = File(cacheRoot, "snapshots_imported").apply { mkdirs() }
            val candidate = File(importedDir, name).apply { writeText("untrusted") }
            val activeDir = File(cacheRoot, "snapshots_current").apply { mkdirs() }
            val activeInitial = File(activeDir, "armbandbot_244_initial.html")

            val savedPath = saveGeneralSnapshotPreservingExistingInitial(
                activeInitial, File(activeDir, "armbandbot_244_latest.html"), candidate.absolutePath,
                "safe baseline", listOf(cacheRoot),
            )

            assertEquals(activeInitial.absolutePath, savedPath)
            assertEquals("safe baseline", activeInitial.readText())
            assertEquals("untrusted", candidate.readText())
            cacheRoot.deleteRecursively()
        }
    }

    @Test
    fun untrustedOutsideOrTraversedSnapshotPathCannotSuppressNewInitial() {
        val cacheRoot = Files.createTempDirectory("snapshot_traversal_root").toFile()
        val outsideRoot = Files.createTempDirectory("snapshot_traversal_outside").toFile()
        val outsideDir = File(outsideRoot, "snapshots_fake").apply { mkdirs() }
        val outsideInitial = File(outsideDir, "armbandbot_244_initial.html").apply { writeText("attacker") }
        val activeDir = File(cacheRoot, "snapshots_currentBot").apply { mkdirs() }
        val activeInitial = File(activeDir, "armbandbot_244_initial.html")
        val activeLatest = File(activeDir, "armbandbot_244_latest.html")
        val traversed = File(activeDir, "../../${outsideRoot.name}/snapshots_fake/${outsideInitial.name}").path

        val savedPath = saveGeneralSnapshotPreservingExistingInitial(
            initialFile = activeInitial,
            latestFile = activeLatest,
            existingSnapshotPath = traversed,
            html = "safe new baseline",
            allowedSnapshotRoots = listOf(cacheRoot)
        )

        assertEquals(activeInitial.absolutePath, savedPath)
        assertEquals("safe new baseline", activeInitial.readText())
        assertTrue(!activeLatest.exists())
        assertEquals("attacker", outsideInitial.readText())
        cacheRoot.deleteRecursively()
        outsideRoot.deleteRecursively()
    }

    @Test
    fun latestWriteFailureNeverOverwritesPreservedBaseline() {
        val cacheRoot = Files.createTempDirectory("snapshot_write_failure").toFile()
        val importedDir = File(cacheRoot, "snapshots_imported").apply { mkdirs() }
        val baseline = File(importedDir, "armbandbot_244_initial.html").apply { writeText("baseline bytes") }
        val latestAsDirectory = File(importedDir, "armbandbot_244_latest.html").apply {
            mkdirs()
            File(this, "keep").writeText("force non-empty directory")
        }
        val activeDir = File(cacheRoot, "snapshots_current").apply { mkdirs() }

        var failed = false
        try {
            saveGeneralSnapshotPreservingExistingInitial(
                File(activeDir, "armbandbot_244_initial.html"),
                File(activeDir, "armbandbot_244_latest.html"),
                baseline.absolutePath,
                "must not replace baseline",
                listOf(cacheRoot),
            )
        } catch (_: Exception) {
            failed = true
        }

        assertTrue(failed)
        assertEquals("baseline bytes", baseline.readText())
        assertTrue(latestAsDirectory.isDirectory)
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

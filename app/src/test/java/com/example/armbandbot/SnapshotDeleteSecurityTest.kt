package com.heyheyon.armbandbot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SnapshotDeleteSecurityTest {
    @Test
    fun deletionRejectsOutsideAndPrefixCollisionAndDeletesOnlyTrustedPair() {
        val parent = Files.createTempDirectory("snapshot_delete").toFile()
        try {
            val cacheRoot = File(parent, "cache").apply { mkdirs() }
            val trustedDir = File(cacheRoot, "snapshots_bot").apply { mkdirs() }
            val trustedInitial = File(trustedDir, "gall_10_initial.html").apply { writeText("initial") }
            val trustedLatest = File(trustedDir, "gall_10_latest.html").apply { writeText("latest") }
            val outsideDir = File(parent, "cache-evil/snapshots_bot").apply { mkdirs() }
            val outside = File(outsideDir, "gall_10_initial.html").apply { writeText("outside") }
            val unrelated = File(trustedDir, "notes.html").apply { writeText("keep") }

            assertEquals(0, deleteSnapshotFiles(outside.absolutePath, listOf(cacheRoot)))
            assertTrue(outside.exists())
            assertEquals(0, deleteSnapshotFiles(unrelated.absolutePath, listOf(cacheRoot)))
            assertTrue(unrelated.exists())
            assertEquals(2, deleteSnapshotFiles(trustedLatest.absolutePath, listOf(cacheRoot)))
            assertFalse(trustedInitial.exists())
            assertFalse(trustedLatest.exists())
        } finally {
            parent.deleteRecursively()
        }
    }

    @Test
    fun directoryCleanupDeletesOnlyCanonicalHtmlFilesAndRemovesOnlyEmptyDirectory() {
        val parent = Files.createTempDirectory("snapshot_directory_cleanup").toFile()
        try {
            val cacheRoot = File(parent, "cache").apply { mkdirs() }
            val mixedDir = File(cacheRoot, "snapshots_mixed").apply { mkdirs() }
            val html = File(mixedDir, "gall_10_initial.html").apply { writeText("html") }
            val note = File(mixedDir, "keep.txt").apply { writeText("keep") }
            val nested = File(mixedDir, "nested").apply { mkdirs() }
            val emptyableDir = File(cacheRoot, "snapshots_emptyable").apply { mkdirs() }
            File(emptyableDir, "gall_11_blocked_1.html").writeText("html")

            assertEquals(1, deleteTrustedSnapshotDirectoryFiles(cacheRoot, mixedDir))
            assertFalse(html.exists())
            assertTrue(note.exists())
            assertTrue(nested.exists())
            assertTrue(mixedDir.exists())
            assertEquals(1, deleteTrustedSnapshotDirectoryFiles(cacheRoot, emptyableDir))
            assertFalse(emptyableDir.exists())
        } finally {
            parent.deleteRecursively()
        }
    }

    @Test
    fun expirationCleanupRejectsSymlinksAndDeletesOnlyOldHtml() {
        val parent = Files.createTempDirectory("snapshot_expiration_cleanup").toFile()
        try {
            val cacheRoot = File(parent, "cache").apply { mkdirs() }
            val snapshotDir = File(cacheRoot, "snapshots_bot").apply { mkdirs() }
            val oldHtml = File(snapshotDir, "gall_1_initial.html").apply { writeText("old"); setLastModified(100L) }
            val recentHtml = File(snapshotDir, "gall_2_initial.html").apply { writeText("recent"); setLastModified(300L) }
            val oldText = File(snapshotDir, "old.txt").apply { writeText("keep"); setLastModified(100L) }
            val outside = File(parent, "outside.html").apply { writeText("outside"); setLastModified(100L) }
            val link = File(snapshotDir, "linked.html")
            Files.createSymbolicLink(link.toPath(), outside.toPath())

            assertEquals(1, deleteTrustedSnapshotDirectoryFiles(cacheRoot, snapshotDir, olderThanMillis = 200L))
            assertFalse(oldHtml.exists())
            assertTrue(recentHtml.exists())
            assertTrue(oldText.exists())
            assertTrue(link.exists())
            assertEquals("outside", outside.readText())
        } finally {
            parent.deleteRecursively()
        }
    }

    @Test
    fun backupSnapshotCollectionRejectsOutsideSymlinkAndNonHtmlDatabasePaths() {
        val parent = Files.createTempDirectory("snapshot_backup_collection").toFile()
        try {
            val cacheRoot = File(parent, "cache").apply { mkdirs() }
            val snapshotDir = File(cacheRoot, "snapshots_bot").apply { mkdirs() }
            val initial = File(snapshotDir, "gall_1_initial.html").apply { writeText("initial") }
            val latest = File(snapshotDir, "gall_1_latest.html").apply { writeText("latest") }
            val nonHtml = File(snapshotDir, "secrets.db").apply { writeText("secret") }
            val outside = File(parent, "outside.html").apply { writeText("outside") }
            val linked = File(snapshotDir, "linked.html")
            Files.createSymbolicLink(linked.toPath(), outside.toPath())

            val collected = collectTrustedDatabaseBackupSnapshotFiles(
                cacheRoot,
                listOf(initial.path, outside.path, nonHtml.path, linked.path),
            )

            assertEquals(setOf(initial.canonicalPath, latest.canonicalPath), collected.map { it.canonicalPath }.toSet())
        } finally {
            parent.deleteRecursively()
        }
    }

    @Test
    fun deletionRejectsSymlinkEscape() {
        val parent = Files.createTempDirectory("snapshot_delete_symlink").toFile()
        try {
            val cacheRoot = File(parent, "cache").apply { mkdirs() }
            val outsideDir = File(parent, "outside/snapshots_bot").apply { mkdirs() }
            val outside = File(outsideDir, "gall_10_initial.html").apply { writeText("outside") }
            val link = File(cacheRoot, "snapshots_link")
            Files.createSymbolicLink(link.toPath(), outsideDir.toPath())

            assertEquals(0, deleteSnapshotFiles(File(link, outside.name).path, listOf(cacheRoot)))
            assertEquals("outside", outside.readText())
        } finally {
            parent.deleteRecursively()
        }
    }
}

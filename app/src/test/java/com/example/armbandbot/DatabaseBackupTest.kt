package com.heyheyon.armbandbot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream

class DatabaseBackupTest {
    @Test
    fun safeRestoreFileNameDoesNotUseAndroidFragileRegex() {
        val safeName = safeRestoreFileName("한글 제목 [수정본]?.html")

        assertTrue(safeName.endsWith(".html"))
        assertFalse(safeName.contains("?"))
        assertFalse(safeName.contains("["))
        assertFalse(safeName.contains("]"))
        assertTrue(safeName.contains("한글"))
    }

    @Test
    fun blockedSnapshotRecordedAtDoesNotUseRegex() {
        assertEquals(1700000000000L, blockedSnapshotRecordedAtFromName("gall_10_blocked_1700000000000.html"))
        assertEquals(null, blockedSnapshotRecordedAtFromName("gall_10_blocked_bad.html"))
        assertEquals(null, blockedSnapshotRecordedAtFromName("gall_10_latest.html"))
    }

    @Test
    fun defaultBackupFileNameUsesZipExtension() {
        val dir = createTempDir(prefix = "armbandbot-db-backup-test")
        try {
            val db = File(dir, "bot_database").apply { writeText("main-db") }
            val wal = File(dir, "bot_database-wal").apply { writeText("wal-db") }
            val missingShm = File(dir, "bot_database-shm")
            val output = ByteArrayOutputStream()

            val count = writeDatabaseBackupZip(listOf(db, wal, missingShm), output)
            val entries = readZipEntries(output)

            assertEquals(2, count)
            assertEquals("main-db", entries["bot_database"])
            assertEquals("wal-db", entries["bot_database-wal"])
            assertTrue("missing shm should not be included", "bot_database-shm" !in entries)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun writeDatabaseBackupZipIncludesSnapshotsUnderRelativePathsAndManifest() {
        val dir = createTempDir(prefix = "armbandbot-db-backup-snapshot-test")
        try {
            val db = File(dir, "bot_database").apply { writeText("main-db") }
            val snapshotDir = File(dir, "snapshots_botA").apply { mkdirs() }
            val initial = File(snapshotDir, "gall_10_initial.html").apply { writeText("initial-html") }
            val latest = File(snapshotDir, "gall_10_latest.html").apply { writeText("latest-html") }
            val output = ByteArrayOutputStream()

            val count = writeDatabaseBackupZip(
                databaseFiles = listOf(db),
                snapshotFiles = listOf(
                    BackupSnapshotFile(initial, "snapshots/snapshots_botA/gall_10_initial.html", initial.absolutePath, "M", "gall", "10", "initial", 100L),
                    BackupSnapshotFile(latest, "snapshots/snapshots_botA/gall_10_latest.html", latest.absolutePath, "M", "gall", "10", "latest", 200L)
                ),
                outputStream = output
            )
            val entries = readZipEntries(output)

            assertEquals(3, count)
            assertEquals("main-db", entries["bot_database"])
            assertEquals("initial-html", entries["snapshots/snapshots_botA/gall_10_initial.html"])
            assertEquals("latest-html", entries["snapshots/snapshots_botA/gall_10_latest.html"])
            assertTrue(entries["manifest.json"]!!.contains("\"formatVersion\":2"))
            assertTrue(entries["manifest.json"]!!.contains("snapshots/snapshots_botA/gall_10_initial.html"))
            assertFalse("ZIP should not expose absolute paths as entry names", entries.keys.any { it.contains(dir.absolutePath.replace('\\', '/')) })
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun chooseSnapshotCandidateKeepsOlderInitialAndNewerLatest() {
        val currentInitial = SnapshotCandidate("current_initial.html", 200L)
        val backupInitial = SnapshotCandidate("backup_initial.html", 100L)
        val currentLatest = SnapshotCandidate("current_latest.html", 300L)
        val backupLatest = SnapshotCandidate("backup_latest.html", 500L)

        assertEquals("backup_initial.html", chooseSnapshotCandidate(currentInitial, backupInitial, preferOlder = true)?.path)
        assertEquals("backup_latest.html", chooseSnapshotCandidate(currentLatest, backupLatest, preferOlder = false)?.path)
    }

    @Test
    fun checkedPostFromBackupColumnsToleratesOldSchemasWithMissingOptionalColumns() {
        val row = checkedPostFromBackupColumns(
            mapOf(
                "gallType" to "M",
                "gallId" to "oldgall",
                "postNum" to "123",
                "commentCount" to 7,
                "checkTime" to 111L
            )
        )

        assertEquals("M", row.gallType)
        assertEquals("oldgall", row.gallId)
        assertEquals("123", row.postNum)
        assertEquals(7, row.commentCount)
        assertEquals(111L, row.checkTime)
        assertEquals(null, row.snapshotPath)
        assertEquals(null, row.creationDate)
    }

    @Test
    fun blockHistoryFromBackupColumnsToleratesPreTargetNoSchema() {
        val row = blockHistoryFromBackupColumns(
            mapOf(
                "gallType" to "M",
                "gallId" to "oldgall",
                "postNum" to "123",
                "targetType" to "COMMENT",
                "targetAuthor" to "작성자",
                "targetContent" to "내용",
                "blockReason" to "사유",
                "blockTime" to 222L
            )
        )

        assertEquals("", row.targetNo)
        assertEquals("COMMENT", row.targetType)
        assertEquals(222L, row.blockTime)
    }

    @Test
    fun migrationSqlAddsTargetNoWithoutDroppingBlockHistory() {
        assertEquals(
            "ALTER TABLE `block_history` ADD COLUMN `targetNo` TEXT NOT NULL DEFAULT ''",
            AppDatabase.ADD_BLOCK_HISTORY_TARGET_NO_SQL
        )
        assertTrue(AppDatabase.CREATE_HOLD_HISTORY_SQL.contains("CREATE TABLE IF NOT EXISTS `hold_history`"))
        assertTrue(AppDatabase.CREATE_HOLD_HISTORY_INDEX_SQL.contains("CREATE UNIQUE INDEX IF NOT EXISTS"))
    }

    private fun readZipEntries(output: ByteArrayOutputStream): Map<String, String> {
        val entries = mutableMapOf<String, String>()
        ZipInputStream(output.toByteArray().inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries[entry.name] = zip.readBytes().decodeToString()
                zip.closeEntry()
            }
        }
        return entries
    }
}

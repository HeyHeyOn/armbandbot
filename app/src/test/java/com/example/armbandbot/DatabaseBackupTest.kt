package com.heyheyon.armbandbot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
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
    fun checkpointMustBeTruncatedAndNotBusyBeforeExport() {
        assertEquals(Unit, requireSuccessfulWalCheckpoint(listOf(0, 0, 0)))
        assertThrows(IllegalStateException::class.java) {
            requireSuccessfulWalCheckpoint(listOf(1, 4, 4))
        }
        assertThrows(IllegalStateException::class.java) {
            requireSuccessfulWalCheckpoint(emptyList())
        }
        listOf(
            listOf(0, 1, 1),
            listOf(0, 0, 1),
            listOf(0, 1, 0),
            listOf(0, 0),
            listOf(0, 0, 0, 0),
        ).forEach { nonTruncatedState ->
            assertThrows(IllegalStateException::class.java) {
                requireSuccessfulWalCheckpoint(nonTruncatedState)
            }
        }
    }

    @Test
    fun fairMaintenanceLockExcludesDaoWorkUntilBackupWorkReleasesIt() {
        val lock = DatabaseMaintenanceLock()
        val backupEntered = CountDownLatch(1)
        val releaseBackup = CountDownLatch(1)
        val daoEntered = CountDownLatch(1)
        val daoRanBeforeRelease = AtomicBoolean(false)

        val backup = Thread {
            lock.withLock {
                backupEntered.countDown()
                releaseBackup.await(5, TimeUnit.SECONDS)
            }
        }
        val dao = Thread {
            check(backupEntered.await(5, TimeUnit.SECONDS))
            lock.withLock {
                daoRanBeforeRelease.set(releaseBackup.count > 0)
                daoEntered.countDown()
            }
        }

        backup.start()
        dao.start()
        assertTrue(backupEntered.await(5, TimeUnit.SECONDS))
        assertFalse(daoEntered.await(150, TimeUnit.MILLISECONDS))
        releaseBackup.countDown()
        assertTrue(daoEntered.await(5, TimeUnit.SECONDS))
        backup.join(5_000)
        dao.join(5_000)
        assertFalse(daoRanBeforeRelease.get())
        assertTrue(lock.isFair)
    }

    @Test
    fun consistentExportIncludesOnlyMainDatabaseFile() {
        val dir = createTempDir(prefix = "armbandbot-db-main-only")
        try {
            val db = File(dir, "bot_database").apply { writeText("main") }
            File(dir, "bot_database-wal").writeText("wal")
            File(dir, "bot_database-shm").writeText("shm")

            assertEquals(listOf(db), consistentDatabaseExportFiles(db))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun unchangedCheckedPostCountsAsSkippedForItsScope() {
        val counts = incrementCheckedPostRestoreCounts(
            CheckedPostRestoreCounts(),
            CheckedPostRestoreOutcome.SKIPPED,
        )

        assertEquals(CheckedPostRestoreCounts(skipped = 1), counts)
    }

    @Test
    fun restoreResultReportsCheckedPostCountsByScope() {
        val result = DatabaseRestoreResult(
            insertedPosts = 3,
            updatedPosts = 2,
            skippedRows = 4,
            checkedPostsByScopeId = mapOf(
                "bot-a" to CheckedPostRestoreCounts(inserted = 2, updated = 1, skipped = 3),
                "bot-b" to CheckedPostRestoreCounts(inserted = 1, updated = 1, skipped = 1),
            ),
        )

        assertEquals(CheckedPostRestoreCounts(2, 1, 3), result.checkedPostsByScopeId["bot-a"])
        assertEquals(CheckedPostRestoreCounts(1, 1, 1), result.checkedPostsByScopeId["bot-b"])
    }

    @Test
    fun writerIncludesOnlyMainDatabaseFile() {
        val dir = createTempDir(prefix = "armbandbot-db-backup-test")
        try {
            val db = File(dir, "bot_database").apply { writeText("main-db") }
            val wal = File(dir, "bot_database-wal").apply { writeText("wal-db") }
            val missingShm = File(dir, "bot_database-shm")
            val output = ByteArrayOutputStream()

            val count = writeDatabaseBackupZip(listOf(db, wal, missingShm), output)
            val entries = readZipEntries(output)

            assertEquals(1, count)
            assertEquals("main-db", entries["bot_database"])
            assertTrue("WAL must not be included after a complete checkpoint", "bot_database-wal" !in entries)
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
            assertTrue(entries["manifest.json"]!!.contains("\"formatVersion\":4"))
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

        assertEquals(GLOBAL_SCAN_SCOPE, row.scopeId)
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
    fun scopedAndActorColumnsSurviveBackupProjectionWhileLegacyDefaultsAreSafe() {
        val scoped = checkedPostFromBackupColumns(
            mapOf(
                "scopeId" to "bot-c",
                "gallType" to "M",
                "gallId" to "gall",
                "postNum" to "1",
            )
        )
        val block = blockHistoryFromBackupColumns(
            mapOf("gallType" to "M", "gallId" to "gall", "postNum" to "1")
        )
        val hold = holdHistoryFromBackupColumns(
            mapOf("gallType" to "M", "gallId" to "gall", "postNum" to "1", "actorBotId" to "bot-c")
        )

        assertEquals("bot-c", scoped.scopeId)
        assertEquals(LEGACY_ACTOR_BOT_ID, block.actorBotId)
        assertEquals("bot-c", hold.actorBotId)
    }

    @Test
    fun historyMergeKeysPreserveActorAttribution() {
        val blockBase = BlockHistory(
            gallType = "M",
            gallId = "gall",
            postNum = "1",
            targetType = "POST",
            targetNo = "",
            targetAuthor = "writer",
            targetContent = "content",
            blockReason = "reason",
            blockTime = 10L,
            actorBotId = "bot-a",
        )
        val holdBase = HoldHistory(
            gallType = "M",
            gallId = "gall",
            postNum = "1",
            targetType = "POST",
            targetNo = "",
            targetAuthor = "writer",
            targetContent = "content",
            holdReason = "reason",
            holdTime = 10L,
            actorBotId = "bot-a",
        )

        assertTrue(blockHistoryMergeKey(blockBase) != blockHistoryMergeKey(blockBase.copy(actorBotId = "bot-b")))
        assertTrue(holdHistoryMergeKey(holdBase) != holdHistoryMergeKey(holdBase.copy(actorBotId = "bot-b")))
    }

    @Test
    fun claimBackupRestorePreservesFailClosedStatesAndDropsExplicitFailures() {
        val base = mapOf<String, Any?>(
            "gallType" to "M", "gallId" to "g", "postNum" to "7",
            "targetType" to "POST", "targetNo" to "7", "actionKind" to "DELETE_POST",
            "actorBotId" to "bot-a", "claimedAt" to 10L, "finishedAt" to 20L,
        )
        val succeeded = moderationClaimFromBackupColumns(base + ("status" to "SUCCEEDED")) { "restored-owner" }
        val pending = moderationClaimFromBackupColumns(base + ("status" to "PENDING")) { "restored-owner" }
        val failed = moderationClaimFromBackupColumns(base + ("status" to "FAILED")) { "restored-owner" }

        assertEquals(ClaimStatus.SUCCEEDED.name, succeeded?.status)
        assertEquals(ClaimStatus.UNKNOWN.name, pending?.status)
        assertEquals("restored-owner", pending?.ownerToken)
        assertEquals(null, failed)
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

package com.heyheyon.armbandbot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DatabaseBackupSourceContractTest {
    private fun source(name: String): String {
        val candidates = listOf(
            File("app/src/main/java/com/example/armbandbot/$name"),
            File("src/main/java/com/example/armbandbot/$name"),
        )
        return candidates.firstOrNull(File::isFile)?.readText()?.replace("\r\n", "\n")
            ?: error("$name not found from ${File(".").absolutePath}")
    }

    @Test
    fun backupLocksBeforeCheckpointAndKeepsLockAcrossZipWithoutSqlTransaction() {
        val body = source("DatabaseBackup.kt").substringAfter("fun backupDatabaseToUri")
            .substringBefore("internal data class ExtractedDatabaseBackup")

        assertTrue(body.contains("GlobalBotState.withDatabaseMaintenanceLock"))
        assertTrue(body.indexOf("withDatabaseMaintenanceLock") < body.indexOf("wal_checkpoint(TRUNCATE)"))
        assertTrue(body.indexOf("wal_checkpoint(TRUNCATE)") < body.indexOf("writeDatabaseBackupZip"))
        assertFalse(body.contains("beginTransaction"))
    }

    @Test
    fun restoreAndDashboardDestructiveOperationsUseTheGlobalMaintenanceLock() {
        val backup = source("DatabaseBackup.kt")
        val restoreBody = backup.substringAfter("fun restoreDatabaseBackupFromUri")
            .substringBefore("fun chooseSnapshotCandidate")
        val dashboard = source("DbDashboardScreen.kt")

        assertTrue(restoreBody.contains("GlobalBotState.withDatabaseMaintenanceLock"))
        assertTrue(dashboard.contains("resetDashboardRecords(checkNotNull(database), context.cacheDir, resetTarget)"))
        val reset = source("DashboardResetUi.kt").substringAfter("internal fun resetDashboardRecords")
        assertTrue(reset.contains("GlobalBotState.withDatabaseMaintenanceLock"))
        assertTrue(reset.indexOf("withDatabaseMaintenanceLock") < reset.indexOf("check(mayReset())"))
        assertTrue(reset.contains("database.runInTransaction"))
        assertTrue(dashboard.contains("deleteSnapshotRecordsAndFiles("))
        val rowDelete = source("SnapshotRecovery.kt").substringAfter("fun deleteSnapshotRecordsAndFiles")
        assertTrue(rowDelete.contains("GlobalBotState.withDatabaseMaintenanceLock"))
    }

    @Test
    fun globalDaoEntryPointsOwnOneFairMaintenanceLock() {
        val state = source("MainActivity.kt").substringAfter("object GlobalBotState")
            .substringBefore("fun getCurrentTimeStr")

        assertTrue(state.contains("DatabaseMaintenanceLock()"))
        assertTrue(state.contains("fun <T> withDatabaseMaintenanceLock"))
        assertFalse(state.contains("requireDb().postDao()."))
        assertFalse(state.contains("db?.postDao()?."))
        assertFalse(state.contains("db?.moderationClaimDao()?."))
    }

    @Test
    fun restoreStagesAndCopiesInsideCleanupBoundaryAndAvoidsBroadSnapshotDeletion() {
        val body = source("DatabaseBackup.kt").substringAfter("fun restoreDatabaseBackupFromUri")
            .substringBefore("fun chooseSnapshotCandidate")

        val tryStart = body.indexOf("try {")
        val stagingStart = body.indexOf("createSnapshotImportStagingDirectory")
        assertTrue("The cleanup try block must exist", tryStart >= 0)
        assertTrue("The staging operation must exist", stagingStart >= 0)
        assertTrue("Staging must be inside the cleanup boundary", tryStart < stagingStart)
        assertTrue(body.contains("transactionCommitted"))
        assertFalse(body.contains("snapshotRestoreDir.deleteRecursively()"))
    }
}

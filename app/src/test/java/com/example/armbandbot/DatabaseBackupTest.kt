package com.heyheyon.armbandbot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream

class DatabaseBackupTest {
    @Test
    fun writeDatabaseBackupZipIncludesExistingDatabaseFilesOnly() {
        val dir = createTempDir(prefix = "armbandbot-db-backup-test")
        try {
            val db = File(dir, "bot_database").apply { writeText("main-db") }
            val wal = File(dir, "bot_database-wal").apply { writeText("wal-db") }
            val missingShm = File(dir, "bot_database-shm")
            val output = ByteArrayOutputStream()

            val count = writeDatabaseBackupZip(listOf(db, wal, missingShm), output)
            val entries = mutableMapOf<String, String>()
            ZipInputStream(output.toByteArray().inputStream()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    entries[entry.name] = zip.readBytes().decodeToString()
                    zip.closeEntry()
                }
            }

            assertEquals(2, count)
            assertEquals("main-db", entries["bot_database"])
            assertEquals("wal-db", entries["bot_database-wal"])
            assertTrue("missing shm should not be included", "bot_database-shm" !in entries)
        } finally {
            dir.deleteRecursively()
        }
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
}

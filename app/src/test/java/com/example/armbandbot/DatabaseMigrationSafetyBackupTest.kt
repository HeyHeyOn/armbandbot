package com.heyheyon.armbandbot

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DatabaseMigrationSafetyBackupTest {
    @Test
    fun copiesDatabaseWalAndShmBeforeMigration() {
        val root = Files.createTempDirectory("armbandbot-migration-backup").toFile()
        try {
            val source = root.resolve("bot_database").apply { writeText("db") }
            root.resolve("bot_database-wal").writeText("wal")
            root.resolve("bot_database-shm").writeText("shm")
            val destination = root.resolve("backup")

            val copied = copyDatabaseFilesForMigration(source, destination)

            assertEquals(listOf("bot_database", "bot_database-shm", "bot_database-wal"), copied.map { it.name }.sorted())
            assertEquals("db", destination.resolve("bot_database").readText())
            assertEquals("wal", destination.resolve("bot_database-wal").readText())
            assertEquals("shm", destination.resolve("bot_database-shm").readText())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun missingDatabaseCreatesNoMarkerOrEmptyBackup() {
        val root = Files.createTempDirectory("armbandbot-migration-backup-missing").toFile()
        try {
            val destination = root.resolve("backup")
            assertTrue(copyDatabaseFilesForMigration(root.resolve("bot_database"), destination).isEmpty())
            assertTrue(!destination.exists())
        } finally {
            root.deleteRecursively()
        }
    }
}

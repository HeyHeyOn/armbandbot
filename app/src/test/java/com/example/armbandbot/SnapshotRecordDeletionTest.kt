package com.heyheyon.armbandbot

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.lang.reflect.Proxy
import java.nio.file.Files

class SnapshotRecordDeletionTest {
    private fun dao(invoke: (String, Array<out Any?>) -> Any?): PostDao = Proxy.newProxyInstance(
        PostDao::class.java.classLoader, arrayOf(PostDao::class.java)
    ) { _, method, args -> invoke(method.name, args ?: emptyArray()) } as PostDao

    @Test fun recordsAreRemovedBeforeReadingAllSurvivorsAndDeletingFiles() {
        val root = Files.createTempDirectory("snapshot_records").toFile()
        try {
            val dir = File(root, "snapshots_bot").apply { mkdirs() }
            val initial = File(dir, "gall_1_initial.html").apply { writeText("initial") }
            val latest = File(dir, "gall_1_latest.html").apply { writeText("latest") }
            // Independently surviving checked, block, and hold rows must each preserve the pair.
            for (survivor in listOf("checked", "block", "hold")) {
                val rows = mutableMapOf("deleted" to initial.path, survivor to latest.path)
                val db = dao { name, _ ->
                    check(name == "getAllSnapshotPaths")
                    assertFalse(rows.containsKey("deleted"))
                    assertTrue(initial.exists())
                    rows.values.toList()
                }
                assertEquals(0, deleteSnapshotRecordsAndFiles(db, listOf(root)) {
                    listOf(rows.remove("deleted"))
                })
                assertEquals(mapOf(survivor to latest.path), rows)
                assertTrue(initial.exists())
                assertTrue(latest.exists())
            }
            assertEquals(2, deleteSnapshotRecordsAndFiles(dao { _, _ -> emptyList<String>() }, listOf(root)) {
                listOf(initial.path)
            })
            assertFalse(initial.exists())
            assertFalse(latest.exists())
        } finally { root.deleteRecursively() }
    }

    @Test fun missingDaoDoesNotRunDeletion() {
        assertThrows(IllegalStateException::class.java) {
            deleteSnapshotRecordsAndFiles(null, emptyList()) { error("Must not run") }
        }
    }

    @Test fun databaseFailuresNeverDeleteFiles() {
        val root = Files.createTempDirectory("snapshot_failure").toFile()
        try {
            val file = File(root, "snapshots_bot/gall_1_blocked_1.html").apply { parentFile.mkdirs(); writeText("history") }
            assertThrows(IllegalStateException::class.java) {
                deleteSnapshotRecordsAndFiles(dao { _, _ -> error("query failure") }, listOf(root)) { listOf(file.path) }
            }
            assertEquals("history", file.readText())
            assertThrows(IllegalStateException::class.java) {
                deleteSnapshotRecordsAndFiles(dao { _, _ -> error("must not query") }, listOf(root)) { error("delete failure") }
            }
            assertEquals("history", file.readText())
        } finally { root.deleteRecursively() }
    }

    @Test fun botCleanupPreservesRecordsByDefaultAndRequiresDao() {
        val root = Files.createTempDirectory("bot_cleanup").toFile()
        try {
            val file = File(root, "snapshots_bot/gall_1_initial.html").apply { parentFile.mkdirs(); writeText("saved") }
            cleanupDeletedBotSnapshots(dao { _, _ -> error("No DAO calls without opt-in") }, root, "bot", false)
            assertTrue(file.exists())
            assertThrows(IllegalStateException::class.java) { cleanupDeletedBotSnapshots(null, root, "bot", false) }
        } finally { root.deleteRecursively() }
    }

    @Test fun botCleanupDeletesOnlyIndependentRowsAndUnreferencedFiles() {
        val root = Files.createTempDirectory("bot_cleanup").toFile()
        try {
            val dir = File(root, "snapshots_bot").apply { mkdirs() }
            val initial = File(dir, "gall_1_initial.html").apply { writeText("initial") }
            val latest = File(dir, "gall_1_latest.html").apply { writeText("latest") }
            val orphan = File(dir, "orphan.html").apply { writeText("orphan") }
            val calls = mutableListOf<String>()
            val db = dao { name, args ->
                calls.add(name)
                when (name) {
                    "deletePostsForScope" -> { assertEquals("bot", args[0]); Unit }
                    "getAllSnapshotPaths" -> listOf(latest.path)
                    else -> error("Unexpected DAO call: $name")
                }
            }
            cleanupDeletedBotSnapshots(db, root, "bot", true)
            assertEquals(listOf("deletePostsForScope", "getAllSnapshotPaths"), calls)
            assertTrue(initial.exists())
            assertTrue(latest.exists())
            assertFalse(orphan.exists())
        } finally { root.deleteRecursively() }
    }

    @Test fun botCleanupFailsClosedOnInventoryOrDirectoryFailure() {
        val root = Files.createTempDirectory("bot_cleanup").toFile()
        try {
            val file = File(root, "snapshots_bot/gall_1_initial.html").apply { parentFile.mkdirs(); writeText("saved") }
            assertThrows(IllegalStateException::class.java) {
                cleanupDeletedBotSnapshots(dao { name, _ -> if (name == "deletePostsForScope") Unit else error("DB unavailable") }, root, "bot", true)
            }
            assertTrue(file.exists())
            val unreadableRoot = object : File(root.path) {
                override fun getCanonicalFile(): File = throw java.io.IOException("IO unavailable")
            }
            assertThrows(java.io.IOException::class.java) {
                cleanupDeletedBotSnapshots(dao { name, _ -> if (name == "deletePostsForScope") Unit else emptyList<String>() }, unreadableRoot, "bot", true)
            }
            assertTrue(file.exists())
        } finally { root.deleteRecursively() }
    }

    @Test fun botDirectoryCleanupRequiresDaoInventoryAndExplicitOptIn() {
        val source = sequenceOf(File("src/main/java/com/example/armbandbot/BotListScreen.kt"),
            File("app/src/main/java/com/example/armbandbot/BotListScreen.kt")).first { it.exists() }.readText()
        assertTrue(source.contains("if (deleteBotSnapshots)"))
        assertTrue(source.contains("survivingSnapshotPaths = dao.getAllSnapshotPaths()"))
        assertTrue(source.contains("var deleteBotSnapshots by remember(botToDelete) { mutableStateOf(false) }"))
        assertTrue(source.contains("GlobalBotState.withDatabaseMaintenanceLock"))
        assertTrue(source.contains("Checkbox(checked = deleteBotSnapshots"))
        assertTrue(source.contains("withContext(Dispatchers.IO)"))
        assertTrue(source.contains("Button(enabled = !isDeletingBot"))
        assertTrue(source.contains("if (!isDeletingBot) botToDelete = null"))
        val cleanup = source.indexOf("cleanupDeletedBotSnapshots(GlobalBotState.getDb()?.postDao()")
        assertTrue(cleanup >= 0)
        assertTrue(source.indexOf("delPref.edit().clear().apply()") > cleanup)
        assertTrue(source.indexOf("botIds.remove(deletingBotId)") > cleanup)
        assertFalse(source.contains("deleteRecursively"))
    }

    @Test fun dashboardUsesReferenceAwareDeletionAndWarnsAboutSharedHistory() {
        val source = sequenceOf(File("src/main/java/com/example/armbandbot/DbDashboardScreen.kt"),
            File("app/src/main/java/com/example/armbandbot/DbDashboardScreen.kt")).first { it.exists() }.readText()
        assertTrue(source.contains("deleteSnapshotRecordsAndFiles(postDao"))
        assertFalse(source.contains("deleteSnapshotFiles(target"))
        assertTrue(source.contains("공용 조치 이력에서도 삭제됩니다"))
    }
}

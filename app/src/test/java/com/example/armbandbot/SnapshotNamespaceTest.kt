package com.heyheyon.armbandbot

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SnapshotNamespaceTest {
    @Test fun serviceAndBotDeletionUseScopedStorage() {
        val base = File("src/main/java/com/example/armbandbot")
        val service = File(base, "BotService.kt").readText()
        val list = File(base, "BotListScreen.kt").readText()
        assertTrue(service.contains("snapshotDirectoryForScope(this@BotService.cacheDir, botId, config.scanScopeId)"))
        assertTrue(service.contains("relocateToRequestedDirectory = true"))
        assertTrue(service.contains("snapshotDirectoriesForBot(cacheDir, botId)"))
        assertTrue(list.contains("snapshotDirectoriesForBot(root, botId)"))
    }
    private fun initial(dir: File) = File(dir, "gallery_7_initial.html")
    private fun latest(dir: File) = File(dir, "gallery_7_latest.html")
    private fun save(root: File, dir: File, old: String?, html: String) =
        saveGeneralSnapshotPreservingExistingInitial(initial(dir), latest(dir), old, html,
            allowedSnapshotRoots = listOf(root), relocateToRequestedDirectory = true)

    @Test fun sameBotSamePostGlobalPrivateGlobalPreservesSeparatePairs() {
        val root = Files.createTempDirectory("snapshot_namespace").toFile()
        try {
            val global = snapshotDirectoryForScope(root, "bot-a", GLOBAL_SCAN_SCOPE)
            val privateDir = snapshotDirectoryForScope(root, "bot-a", "bot-a")
            assertNotEquals(global, privateDir)
            val globalPath = save(root, global, null, "<p>GLOBAL initial</p>")
            save(root, global, globalPath, "<p>GLOBAL latest</p>")
            val privatePath = save(root, privateDir, null, "<p>private initial</p>")
            save(root, privateDir, privatePath, "<p>private latest</p>")
            assertEquals(initial(global).canonicalPath, globalPath)
            assertEquals(initial(privateDir).canonicalPath, privatePath)
            assertEquals("<p>GLOBAL latest</p>", latest(global).readText())
            assertEquals(global, snapshotDirectoryForScope(root, "bot-a", GLOBAL_SCAN_SCOPE))
            // Even a stale cross-scope DB pointer must not replace GLOBAL's established baseline.
            save(root, global, privatePath, "<p>GLOBAL resumed</p>")
            assertArrayEquals("<p>GLOBAL initial</p>".toByteArray(), initial(global).readBytes())
            assertEquals("<p>GLOBAL resumed</p>", latest(global).readText())
            assertArrayEquals("<p>private initial</p>".toByteArray(), initial(privateDir).readBytes())
            assertEquals("<p>private latest</p>", latest(privateDir).readText())
        } finally { root.deleteRecursively() }
    }

    @Test fun relocationCopiesLegacyBaselineWithoutWritingEitherLegacyVersion() {
        listOf(false, true).forEach { latestOnly ->
            val root = Files.createTempDirectory("snapshot_relocation").toFile()
            try {
                val legacy = File(root, "snapshots_bot-a").apply { mkdirs() }
                if (!latestOnly) initial(legacy).writeBytes(byteArrayOf(0, 1, 127, -1))
                latest(legacy).writeText("legacy latest")
                val baseline = if (latestOnly) latest(legacy).readBytes() else initial(legacy).readBytes()
                val target = snapshotDirectoryForScope(root, "bot-a", GLOBAL_SCAN_SCOPE)
                val saved = save(root, target, latest(legacy).path, "new global latest")
                assertEquals(initial(target).canonicalPath, saved)
                assertNotEquals(initial(legacy).canonicalPath, saved)
                assertArrayEquals(baseline, initial(target).readBytes())
                assertEquals("new global latest", latest(target).readText())
                assertEquals("legacy latest", latest(legacy).readText())
                if (latestOnly) assertFalse(initial(legacy).exists())
                else assertArrayEquals(baseline, initial(legacy).readBytes())
                val privateDir = snapshotDirectoryForScope(root, "bot-a", "bot-a")
                save(root, privateDir, saved, "private latest")
                assertArrayEquals(baseline, initial(privateDir).readBytes())
                assertEquals("new global latest", latest(target).readText())
                save(root, target, initial(privateDir).path, "resumed")
                assertArrayEquals(baseline, initial(target).readBytes())
                assertEquals("private latest", latest(privateDir).readText())
            } finally { root.deleteRecursively() }
        }
    }

    @Test fun namespaceIsSafeDeterministicAndCleanupFindsLegacyAndAllScopes() {
        val root = Files.createTempDirectory("snapshot_directories").toFile()
        try {
            val bot = "bot-a"
            val legacy = File(root, "snapshots_$bot").apply { mkdirs() }
            val global = snapshotDirectoryForScope(root, bot, GLOBAL_SCAN_SCOPE).apply { mkdirs() }
            val privateDir = snapshotDirectoryForScope(root, bot, bot).apply { mkdirs() }
            snapshotDirectoryForScope(root, "other", GLOBAL_SCAN_SCOPE).mkdirs()
            val unsafe = snapshotDirectoryForScope(root, "../../escape", "../scope/한글")
            assertEquals(root.canonicalFile, unsafe.parentFile!!.canonicalFile)
            assertTrue(unsafe.name.startsWith("snapshots_"))
            assertNotEquals(snapshotDirectoryForScope(root, "a_b", "c"), snapshotDirectoryForScope(root, "a", "b_c"))
            assertEquals(setOf(legacy, global, privateDir), snapshotDirectoriesForBot(root, bot).toSet())
            val external = Files.createTempDirectory("snapshot_external").toFile()
            try {
                global.delete()
                Files.createSymbolicLink(global.toPath(), external.toPath())
                assertFalse(snapshotDirectoriesForBot(root, bot).contains(global))
                assertTrue(runCatching { save(root, global, null, "unsafe") }.isFailure)
            } finally { external.deleteRecursively() }
        } finally { root.deleteRecursively() }
    }
}

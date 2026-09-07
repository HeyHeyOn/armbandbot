package com.heyheyon.armbandbot

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

class DatabaseBackupCompanionTest {
    private fun manifest(text: String): Map<String, ManifestSnapshot> {
        val entries = JSONObject(text).getJSONArray("snapshotEntries")
        return (0 until entries.length()).associate { i ->
            val entry = entries.getJSONObject(i)
            val original = entry.getString("originalPath")
            original to ManifestSnapshot(entry.getString("zipPath"), original, null, null, null, null, null)
        }
    }

    @Test fun importedTerminalEvidenceDominatesRetryableLocalClaims() {
        listOf(ClaimStatus.SUCCEEDED, ClaimStatus.UNKNOWN).forEach { imported ->
            listOf(ClaimStatus.PENDING, ClaimStatus.FAILED).forEach { current ->
                assertTrue(shouldReplaceClaimForRestore(current.name, imported.name))
            }
            listOf(ClaimStatus.SUCCEEDED, ClaimStatus.UNKNOWN).forEach { current ->
                assertFalse(shouldReplaceClaimForRestore(current.name, imported.name))
            }
        }
        assertFalse(shouldReplaceClaimForRestore(ClaimStatus.PENDING.name, ClaimStatus.FAILED.name))
        assertFalse(shouldReplaceClaimForRestore(ClaimStatus.PENDING.name, ClaimStatus.PENDING.name))
    }

    @Test fun roundTripRestoresUnreferencedCompanion() {
        val root = kotlin.io.path.createTempDirectory().toFile()
        try {
            val db = File(root, "bot_database").apply { writeText("db") }
            val files = listOf("initial", "latest").map { kind ->
                File(root, "snapshots_bot/g_1_$kind.html").apply { parentFile.mkdirs(); writeText(kind) }
            }
            val output = ByteArrayOutputStream()
            writeDatabaseBackupZip(listOf(db), files.map { BackupSnapshotFile(it, "snapshots/snapshots_bot/${it.name}") }, output)
            val extracted = extractDatabaseBackupZip(output.toByteArray().inputStream(), File(root, "extract"))
            val restored = restoreSnapshotFiles(File(root, "restore"), manifest(extracted.manifestText!!), extracted.snapshotsByZipPath, setOf(files.last().absolutePath))
            assertEquals(2, restored.size)
            val latest = File(restored.getValue(files.last().absolutePath))
            assertEquals("latest", latest.readText())
            assertEquals("initial", File(latest.parentFile, "g_1_initial.html").readText())
        } finally { root.deleteRecursively() }
    }

    @Test fun numberedPairZipRoundTripPreservesLegacyManifestIdentifiers() {
        val root = kotlin.io.path.createTempDirectory().toFile()
        try {
            val db = File(root, "bot_database").apply { writeText("db") }
            val initial = File(root, "snapshots_bot/g_1_initial_2.html").apply { parentFile!!.mkdirs(); writeText("original numbered bytes") }
            val latest = File(root, "snapshots_bot/g_1_latest_2.html").apply { writeText("changed numbered bytes") }
            val collected = collectDatabaseBackupSnapshotFiles(root, listOf(latest.absolutePath))
            assertEquals(setOf(initial.absolutePath, latest.absolutePath), collected.map { it.originalPath }.toSet())
            listOf(root.absolutePath + "/", "legacy/", "legacy\\").forEachIndexed { index, prefix ->
                val snapshots = collected.map { it.copy(originalPath = prefix + it.file.name) }
                val output = ByteArrayOutputStream()
                writeDatabaseBackupZip(listOf(db), snapshots, output)
                val extracted = extractDatabaseBackupZip(output.toByteArray().inputStream(), File(root, "extract_$index"))
                val restored = restoreSnapshotFiles(File(root, "restore_$index"), manifest(extracted.manifestText!!), extracted.snapshotsByZipPath, setOf(prefix + latest.name))
                assertEquals(setOf(prefix + initial.name, prefix + latest.name), restored.keys)
                assertEquals(initial.readText(), File(restored.getValue(prefix + initial.name)).readText())
                assertEquals(latest.readText(), File(restored.getValue(prefix + latest.name)).readText())
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun committedCleanupRetainsNumberedCompanionButDeletesOtherVersions() {
        val root = kotlin.io.path.createTempDirectory().toFile()
        try {
            val initial = File(root, "g_1_initial_2.html").apply { writeText("original numbered bytes") }
            val latest = File(root, "g_1_latest_2.html").apply { writeText("changed numbered bytes") }
            val orphan = File(root, "g_1_initial_3.html").apply { writeText("orphan") }
            cleanupCreatedSnapshotFilesAfterImport(setOf(initial, latest, orphan), true, setOf(latest.canonicalPath))
            assertTrue(initial.exists())
            assertEquals("original numbered bytes", initial.readText())
            assertEquals("changed numbered bytes", latest.readText())
            assertFalse(orphan.exists())
        } finally { root.deleteRecursively() }
    }

    @Test fun committedCleanupRetainsCompanionButDeletesUnrelatedFile() {
        val root = kotlin.io.path.createTempDirectory().toFile()
        try {
            val initial = File(root, "g_1_initial.html").apply { writeText("initial") }
            val latest = File(root, "g_1_latest.html").apply { writeText("latest") }
            val orphan = File(root, "g_2_initial.html").apply { writeText("orphan") }
            cleanupCreatedSnapshotFilesAfterImport(setOf(initial, latest, orphan), true, setOf(latest.canonicalPath))
            assertTrue(initial.exists())
            assertTrue(latest.exists())
            assertFalse(orphan.exists())
        } finally { root.deleteRecursively() }
    }

    @Test fun duplicateBasenamesKeepEveryManifestMappingAcrossImports() {
        val root = kotlin.io.path.createTempDirectory().toFile()
        try {
            val db = File(root, "bot_database").apply { writeText("db") }
            val files = listOf("import_a", "import_b").flatMap { source ->
                listOf("initial", "latest", "blocked_123").map { kind ->
                    File(root, "snapshots_imported/$source/snapshots_bot/g_1_$kind.html").apply { parentFile.mkdirs(); writeText("$source:$kind") }
                }
            }
            val collected = collectDatabaseBackupSnapshotFiles(root, files.filterNot { it.name.endsWith("_initial.html") }.map { it.absolutePath })
            assertEquals(files.map { it.absolutePath }.toSet(), collected.map { it.originalPath }.toSet())
            assertEquals(6, collected.map { it.zipPath }.toSet().size)
            val snapshots = files.map { BackupSnapshotFile(it, "snapshots/snapshots_bot/${it.name}") }
            fun export(items: List<BackupSnapshotFile>, dir: String): ExtractedDatabaseBackup {
                val output = ByteArrayOutputStream()
                assertEquals(7, writeDatabaseBackupZip(listOf(db), items, output))
                return extractDatabaseBackupZip(output.toByteArray().inputStream(), File(root, dir))
            }
            val extracted = export(snapshots, "extract")
            val mappings = manifest(extracted.manifestText!!)
            assertEquals(6, mappings.values.map { it.zipPath }.toSet().size)
            files.forEach { file -> assertEquals(file.readText(), extracted.snapshotsByZipPath.getValue(mappings.getValue(file.absolutePath).zipPath).readText()) }
            assertEquals(mappings, manifest(export(snapshots.reversed(), "reverse").manifestText!!))
            val restored = restoreSnapshotFiles(File(root, "restore"), mappings, extracted.snapshotsByZipPath, files.filterNot { it.name.endsWith("_initial.html") }.map { it.absolutePath }.toSet())
            assertEquals(6, restored.size)
            files.forEach { file -> assertEquals(file.readText(), File(restored.getValue(file.absolutePath)).readText()) }
            files.filter { it.name.endsWith("_latest.html") }.forEach { file ->
                val latest = File(restored.getValue(file.absolutePath))
                assertEquals(file.readText().replace("latest", "initial"), File(latest.parentFile, "g_1_initial.html").readText())
            }
        } finally { root.deleteRecursively() }
    }
}

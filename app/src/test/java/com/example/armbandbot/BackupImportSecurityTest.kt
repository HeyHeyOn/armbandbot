package com.heyheyon.armbandbot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BackupImportSecurityTest {
    @Test
    fun unmappedImportedSnapshotPathIsNeverPersistedRaw() {
        val attackerPath = "/data/user/0/victim/files/credentials.json"

        assertEquals(null, resolveRestoredSnapshotPath(attackerPath, emptyMap()))
        assertEquals(null, importedSnapshotPath(attackerPath, emptyMap()))
        assertEquals(
            "/trusted/cache/snapshots_bot/g_1_initial.html",
            mergeImportedSnapshotPath("/trusted/cache/snapshots_bot/g_1_initial.html", null),
        )
    }

    @Test
    fun importedSnapshotsPreserveBotDirectoryHierarchyAndUseManifestMapping() {
        val root = createTempDir(prefix = "snapshot_restore_hierarchy")
        try {
            val extracted = File(root, "extract/snapshots/snapshots_botA/gall_10_initial.html").apply {
                parentFile.mkdirs()
                writeText("initial")
            }
            val restoreRoot = File(root, "cache/snapshots_imported")
            val manifest = ManifestSnapshot(
                "snapshots/snapshots_botA/gall_10_initial.html",
                "/old/private/path.html",
                "M", "gall", "10", "initial", 1L,
            )

            val restored = restoreSnapshotFiles(
                restoreRoot,
                mapOf(manifest.originalPath to manifest),
                mapOf(manifest.zipPath to extracted),
            )

            val output = File(restored.getValue(manifest.originalPath))
            assertEquals(File(restoreRoot, "snapshots_botA/gall_10_initial.html").canonicalPath, output.canonicalPath)
            assertEquals("initial", output.readText())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun importedSnapshotsRestoreOnlyExactManifestPathsReferencedByRows() {
        val root = createTempDir(prefix = "snapshot_restore_references")
        try {
            val referenced = File(root, "extract/snapshots/snapshots_botA/gall_10_initial.html").apply {
                parentFile.mkdirs()
                writeText("referenced")
            }
            val unreferenced = File(root, "extract/snapshots/snapshots_botA/gall_11_initial.html").apply {
                writeText("unreferenced")
            }
            val manifests = listOf(
                ManifestSnapshot("snapshots/snapshots_botA/gall_10_initial.html", "/old/ref.html", null, null, null, null, null),
                ManifestSnapshot("snapshots/snapshots_botA/gall_11_initial.html", "/old/unref.html", null, null, null, null, null),
            ).associateBy { it.originalPath }
            val restoreRoot = File(root, "restore")

            val restored = restoreSnapshotFiles(
                restoreRoot,
                manifests,
                mapOf(manifests.getValue("/old/ref.html").zipPath to referenced, manifests.getValue("/old/unref.html").zipPath to unreferenced),
                referencedOriginalPaths = setOf("/old/ref.html"),
            )

            assertEquals(setOf("/old/ref.html"), restored.keys)
            assertEquals("referenced", File(restored.getValue("/old/ref.html")).readText())
            assertTrue(!File(restoreRoot, "snapshots_botA/gall_11_initial.html").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun snapshotResolutionNeverGuessesCounterpartFileName() {
        val restored = mapOf("/old/g_1_initial.html" to "/new/g_1_initial.html")

        assertEquals(null, resolveRestoredSnapshotPath("/old/g_1_latest.html", restored))
    }

    @Test
    fun createdButUnreferencedSnapshotsAreRemovedWithoutTouchingExistingFiles() {
        val root = createTempDir(prefix = "snapshot_restore_cleanup")
        try {
            val existing = File(root, "existing.html").apply { writeText("keep") }
            val referenced = File(root, "import/referenced.html").apply { parentFile.mkdirs(); writeText("keep") }
            val unused = File(root, "import/unused.html").apply { writeText("delete") }

            cleanupCreatedSnapshotFiles(
                createdFiles = setOf(referenced, unused),
                referencedPaths = setOf(referenced.canonicalPath),
            )

            assertTrue(existing.exists())
            assertTrue(referenced.exists())
            assertTrue(!unused.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun committedImportNeverDeletesCreatedSnapshotsWhenReferenceQueryFails() {
        val root = createTempDir(prefix = "snapshot_committed_cleanup")
        try {
            val created = File(root, "created.html").apply { writeText("committed") }

            cleanupCreatedSnapshotFilesAfterImport(
                createdFiles = setOf(created),
                transactionCommitted = true,
                referencedPaths = null,
            )

            assertTrue(created.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun uncommittedImportDeletesOnlyFilesCreatedByThatImport() {
        val root = createTempDir(prefix = "snapshot_uncommitted_cleanup")
        try {
            val existing = File(root, "existing.html").apply { writeText("keep") }
            val created = File(root, "created.html").apply { writeText("remove") }

            cleanupCreatedSnapshotFilesAfterImport(
                createdFiles = setOf(created),
                transactionCommitted = false,
                referencedPaths = null,
            )

            assertTrue(existing.exists())
            assertTrue(!created.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun restoredSnapshotCreateNewNeverOverwritesCollisionAndCleansPartialCopy() {
        val root = createTempDir(prefix = "snapshot_atomic_create")
        try {
            val existing = File(root, "snapshot.html").apply { writeText("existing") }
            val created = copySnapshotToAtomicNewFile(root, "snapshot.html") { output ->
                output.write("restored".toByteArray())
            }

            assertEquals("existing", existing.readText())
            assertEquals("restored", created.readText())
            assertTrue(created.name != existing.name)

            val beforeFailure = root.listFiles().orEmpty().map { it.canonicalPath }.toSet()
            runCatching {
                copySnapshotToAtomicNewFile(root, "broken.html") { output ->
                    output.write("partial".toByteArray())
                    throw IOException("copy failed")
                }
            }
            val afterFailure = root.listFiles().orEmpty().map { it.canonicalPath }.toSet()
            assertEquals(beforeFailure, afterFailure)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun eachImportUsesCollisionSafeUniqueStagingDirectory() {
        val root = createTempDir(prefix = "snapshot_import_stage")
        try {
            val first = createSnapshotImportStagingDirectory(root, "same-import")
            val second = createSnapshotImportStagingDirectory(root, "same-import")

            assertTrue(first.isDirectory)
            assertTrue(second.isDirectory)
            assertTrue(first.canonicalPath != second.canonicalPath)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun databaseZipLimitsMatchSecurityContract() {
        assertEquals(64L * 1024 * 1024, DATABASE_BACKUP_MAX_ENTRY_BYTES)
        assertEquals(512L * 1024 * 1024, DATABASE_BACKUP_MAX_TOTAL_BYTES)
        assertEquals(2L * 1024 * 1024, DATABASE_BACKUP_MAX_MANIFEST_BYTES)
        assertEquals(4 * 1024, DATABASE_BACKUP_MAX_ENTRY_NAME_BYTES)
        assertEquals(1024 * 1024, DATABASE_BACKUP_MAX_TOTAL_ENTRY_NAME_BYTES)
        assertTrue(DATABASE_BACKUP_MAX_ENTRIES in 10..10_000)
    }

    @Test
    fun databaseZipCountsDirectoryPayloadTowardEntryLimit() {
        val root = createTempDir(prefix = "db_zip_directory_payload")
        try {
            val limits = DatabaseBackupImportLimits(maxEntryBytes = 4)
            val failure = runCatching {
                extractDatabaseBackupZip(
                    ByteArrayInputStream(zipBytes(Triple("padding/", "12345", true))),
                    File(root, "extract"),
                    limits,
                )
            }.exceptionOrNull()

            assertTrue(failure is IllegalArgumentException)
            assertTrue(failure?.message?.contains("항목 크기") == true)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun databaseZipBoundsIndividualAndCumulativeUtf8EntryNameBytes() {
        val root = createTempDir(prefix = "db_zip_name_limits")
        try {
            val individualFailure = runCatching {
                extractDatabaseBackupZip(
                    ByteArrayInputStream(zipBytes("가나" to "")),
                    File(root, "individual"),
                    DatabaseBackupImportLimits(maxEntryNameBytes = 5),
                )
            }.exceptionOrNull()
            assertTrue(individualFailure?.message?.contains("항목 이름") == true)

            val cumulativeFailure = runCatching {
                extractDatabaseBackupZip(
                    ByteArrayInputStream(zipBytes("가" to "", "나" to "")),
                    File(root, "cumulative"),
                    DatabaseBackupImportLimits(maxEntryNameBytes = 3, maxTotalEntryNameBytes = 5),
                )
            }.exceptionOrNull()
            assertTrue(cumulativeFailure?.message?.contains("이름 전체") == true)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun databaseZipRejectsUnsafeDuplicateAndBoundViolations() {
        val root = createTempDir(prefix = "db_zip_limits")
        try {
            assertTrue(runCatching {
                extractDatabaseBackupZip(ByteArrayInputStream(zipBytes("../bot_database" to "db")), File(root, "unsafe"))
            }.isFailure)
            assertTrue(runCatching {
                extractDatabaseBackupZip(
                    ByteArrayInputStream(zipBytes("bot_database" to "db", "./bot_database" to "other")),
                    File(root, "duplicate"),
                )
            }.isFailure)

            val tiny = DatabaseBackupImportLimits(2, 4, 6, 3)
            assertTrue(runCatching {
                extractDatabaseBackupZip(ByteArrayInputStream(zipBytes("bot_database" to "12345")), File(root, "entry"), tiny)
            }.isFailure)
            assertTrue(runCatching {
                extractDatabaseBackupZip(
                    ByteArrayInputStream(zipBytes("manifest.json" to "1234", "bot_database" to "1")),
                    File(root, "manifest"), tiny,
                )
            }.isFailure)
            assertTrue(runCatching {
                extractDatabaseBackupZip(
                    ByteArrayInputStream(zipBytes("bot_database" to "1234", "snapshots/snapshots_a/g_1_initial.html" to "1234")),
                    File(root, "total"), tiny,
                )
            }.isFailure)
            assertTrue(runCatching {
                extractDatabaseBackupZip(
                    ByteArrayInputStream(zipBytes("bot_database" to "1", "x" to "1", "y" to "1")),
                    File(root, "count"), tiny,
                )
            }.isFailure)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun zipBytes(vararg entries: Pair<String, String>): ByteArray =
        zipBytes(*entries.map { (name, content) -> Triple(name, content, false) }.toTypedArray())

    private fun zipBytes(vararg entries: Triple<String, String, Boolean>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, content, directory) ->
                zip.putNextEntry(ZipEntry(if (directory) name.trimEnd('/') + "/" else name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }
}

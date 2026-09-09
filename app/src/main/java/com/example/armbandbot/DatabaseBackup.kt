package com.heyheyon.armbandbot

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.util.Log
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

private const val APP_DATABASE_NAME = "bot_database"
private const val BACKUP_MANIFEST = "manifest.json"
private const val BACKUP_FORMAT_VERSION = 4
internal const val DATABASE_BACKUP_MAX_ENTRIES = 4096
internal const val DATABASE_BACKUP_MAX_ENTRY_BYTES = 64L * 1024 * 1024
internal const val DATABASE_BACKUP_MAX_TOTAL_BYTES = 512L * 1024 * 1024
internal const val DATABASE_BACKUP_MAX_MANIFEST_BYTES = 2L * 1024 * 1024
internal const val DATABASE_BACKUP_MAX_ENTRY_NAME_BYTES = 4 * 1024
internal const val DATABASE_BACKUP_MAX_TOTAL_ENTRY_NAME_BYTES = 1024 * 1024

internal data class DatabaseBackupImportLimits(
    val maxEntries: Int = DATABASE_BACKUP_MAX_ENTRIES,
    val maxEntryBytes: Long = DATABASE_BACKUP_MAX_ENTRY_BYTES,
    val maxTotalBytes: Long = DATABASE_BACKUP_MAX_TOTAL_BYTES,
    val maxManifestBytes: Long = DATABASE_BACKUP_MAX_MANIFEST_BYTES,
    val maxEntryNameBytes: Int = DATABASE_BACKUP_MAX_ENTRY_NAME_BYTES,
    val maxTotalEntryNameBytes: Int = DATABASE_BACKUP_MAX_TOTAL_ENTRY_NAME_BYTES,
)

data class BackupSnapshotFile(
    val file: File,
    val zipPath: String,
    val originalPath: String = file.absolutePath,
    val gallType: String? = null,
    val gallId: String? = null,
    val postNum: String? = null,
    val kind: String? = null,
    val recordedAt: Long? = null
)

data class SnapshotCandidate(val path: String, val recordedAt: Long?)

data class CheckedPostRestoreCounts(
    val inserted: Int = 0,
    val updated: Int = 0,
    val skipped: Int = 0,
)

internal enum class CheckedPostRestoreOutcome { INSERTED, UPDATED, SKIPPED }

internal fun incrementCheckedPostRestoreCounts(
    counts: CheckedPostRestoreCounts,
    outcome: CheckedPostRestoreOutcome,
): CheckedPostRestoreCounts = when (outcome) {
    CheckedPostRestoreOutcome.INSERTED -> counts.copy(inserted = counts.inserted + 1)
    CheckedPostRestoreOutcome.UPDATED -> counts.copy(updated = counts.updated + 1)
    CheckedPostRestoreOutcome.SKIPPED -> counts.copy(skipped = counts.skipped + 1)
}

data class DatabaseRestoreResult(
    val insertedPosts: Int = 0,
    val updatedPosts: Int = 0,
    val insertedBlockHistory: Int = 0,
    val insertedHoldHistory: Int = 0,
    val restoredSnapshots: Int = 0,
    val skippedRows: Int = 0,
    val checkedPostsByScopeId: Map<String, CheckedPostRestoreCounts> = emptyMap(),
) {
    val changedRows: Int get() = insertedPosts + updatedPosts + insertedBlockHistory + insertedHoldHistory
}

internal data class ManifestSnapshot(
    val zipPath: String,
    val originalPath: String,
    val gallType: String?,
    val gallId: String?,
    val postNum: String?,
    val kind: String?,
    val recordedAt: Long?
)

fun defaultDatabaseBackupFileName(now: Date = Date()): String {
    val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(now)
    return "완장봇_DB백업_${ARMBANDBOT_APP_VERSION}_$stamp.zip"
}

internal fun consistentDatabaseExportFiles(database: File): List<File> =
    listOf(database).filter { it.exists() && it.isFile }

fun databaseBackupSourceFiles(context: Context): List<File> =
    consistentDatabaseExportFiles(context.getDatabasePath(APP_DATABASE_NAME))

internal fun requireSuccessfulWalCheckpoint(result: List<Int>) {
    check(result == listOf(0, 0, 0)) { "DB WAL 체크포인트가 완전히 비워지지 않아 백업할 수 없습니다." }
}

fun collectDatabaseBackupSnapshotFiles(context: Context): List<BackupSnapshotFile> {
    val databasePaths = GlobalBotState.getDb()?.postDao()?.getAllSnapshotPaths().orEmpty()
    return collectDatabaseBackupSnapshotFiles(context.cacheDir, databasePaths)
}

internal fun collectDatabaseBackupSnapshotFiles(cacheDir: File, databasePaths: List<String>): List<BackupSnapshotFile> =
    assignSnapshotZipPaths(collectTrustedDatabaseBackupSnapshotFiles(cacheDir, databasePaths).map { file ->
        val parent = file.parentFile?.name?.takeIf { it.isNotBlank() } ?: "snapshots"
        val zipPath = sanitizeZipPath("snapshots/$parent/${file.name}")
        val parsed = parseSnapshotFileName(file.name)
        BackupSnapshotFile(
            file = file,
            zipPath = zipPath,
            originalPath = file.absolutePath,
            gallType = null,
            gallId = parsed?.first,
            postNum = parsed?.second,
            kind = snapshotKind(file.name),
            recordedAt = inferSnapshotTime(file.absolutePath, null)
        )
    })

// Operate on the suffix only: legacy manifest identifiers may be relative or use backslashes.
private fun snapshotCompanionIdentifier(path: String): String? {
    val suffix = Regex("_(initial|latest)(_[0-9]+)?\\.html$").find(path) ?: return null
    val companion = if (suffix.groupValues[1] == "latest") "initial" else "latest"
    return path.substring(0, suffix.range.first) + "_" + companion + suffix.groupValues[2] + ".html"
}

private fun addSnapshotPathAndPair(paths: MutableSet<String>, path: String?) {
    if (path.isNullOrBlank()) return
    paths.add(File(path).absolutePath)
    snapshotCompanionIdentifier(path)?.let { paths.add(File(it).absolutePath) }
}

fun writeDatabaseBackupZip(files: List<File>, outputStream: OutputStream): Int {
    return writeDatabaseBackupZip(databaseFiles = files, snapshotFiles = emptyList(), outputStream = outputStream)
}

fun writeDatabaseBackupZip(
    databaseFiles: List<File>,
    snapshotFiles: List<BackupSnapshotFile>,
    outputStream: OutputStream
): Int {
    val existingDbFiles = databaseFiles
        .filter { it.exists() && it.isFile && it.name == APP_DATABASE_NAME }
        .also { require(it.size == 1) { "백업할 메인 DB 파일이 정확히 하나여야 합니다." } }
    val existingSnapshots = assignSnapshotZipPaths(snapshotFiles.filter { it.file.exists() && it.file.isFile })

    val usedEntries = mutableSetOf<String>()
    ZipOutputStream(outputStream.buffered()).use { zip ->
        val manifest = buildManifestJson(existingSnapshots)
        zip.putNextEntry(ZipEntry(BACKUP_MANIFEST))
        zip.write(manifest.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
        usedEntries.add(BACKUP_MANIFEST)

        existingDbFiles.forEach { file ->
            val entryName = sanitizeZipPath(file.name)
            if (usedEntries.add(entryName)) {
                zip.putNextEntry(ZipEntry(entryName))
                file.inputStream().use { input -> input.copyTo(zip) }
                zip.closeEntry()
            }
        }
        existingSnapshots.distinctBy { it.zipPath }.forEach { snapshot ->
            val entryName = snapshot.zipPath
            check(usedEntries.add(entryName))
            zip.putNextEntry(ZipEntry(entryName))
            snapshot.file.inputStream().use { input -> input.copyTo(zip) }
            zip.closeEntry()
        }
    }
    return existingDbFiles.size + existingSnapshots.size
}

fun backupDatabaseToUri(context: Context, uri: Uri): Int =
    GlobalBotState.withDatabaseMaintenanceLock {
        val room = GlobalBotState.getDb() ?: error("현재 DB가 열려 있지 않습니다.")
        val sqlite = room.openHelper.writableDatabase
        val checkpoint = sqlite.query("PRAGMA wal_checkpoint(TRUNCATE)").use { cursor ->
            if (!cursor.moveToFirst()) emptyList() else (0 until cursor.columnCount).map(cursor::getInt)
        }
        requireSuccessfulWalCheckpoint(checkpoint)

        val files = databaseBackupSourceFiles(context)
        val snapshots = collectDatabaseBackupSnapshotFiles(context)
        val output = context.contentResolver.openOutputStream(uri)
            ?: error("백업 파일을 열 수 없습니다.")
        output.use { writeDatabaseBackupZip(files, snapshots, it) }
    }

internal data class ExtractedDatabaseBackup(
    val manifestText: String?,
    val snapshotsByZipPath: Map<String, File>,
)

internal fun extractDatabaseBackupZip(
    input: InputStream,
    destinationRoot: File,
    limits: DatabaseBackupImportLimits = DatabaseBackupImportLimits(),
): ExtractedDatabaseBackup {
    require(
        limits.maxEntries > 0 && limits.maxEntryBytes > 0 && limits.maxTotalBytes > 0 &&
            limits.maxManifestBytes > 0 && limits.maxEntryNameBytes > 0 && limits.maxTotalEntryNameBytes > 0
    ) {
        "백업 가져오기 제한이 올바르지 않습니다."
    }
    if (!destinationRoot.exists() && !destinationRoot.mkdirs()) error("백업 임시 폴더를 만들 수 없습니다.")
    val seen = mutableSetOf<String>()
    val snapshots = linkedMapOf<String, File>()
    var manifest: String? = null
    var entryCount = 0
    var totalBytes = 0L
    var totalEntryNameBytes = 0

    ZipInputStream(input.buffered()).use { zip ->
        while (true) {
            val entry = zip.nextEntry ?: break
            entryCount++
            require(entryCount <= limits.maxEntries) { "백업 ZIP 항목 수가 제한을 초과했습니다." }
            val entryNameBytes = entry.name.toByteArray(Charsets.UTF_8).size
            require(entryNameBytes <= limits.maxEntryNameBytes) { "백업 ZIP 항목 이름이 제한을 초과했습니다." }
            totalEntryNameBytes += entryNameBytes
            require(totalEntryNameBytes <= limits.maxTotalEntryNameBytes) { "백업 ZIP 항목 이름 전체가 제한을 초과했습니다." }
            val name = validateBackupEntryName(entry.name, entry.isDirectory)
            require(seen.add(name)) { "백업 ZIP에 중복 항목이 있습니다: $name" }
            if (entry.isDirectory) {
                totalBytes = copyBackupEntryBounded(zip, null, limits.maxEntryBytes, totalBytes, limits.maxTotalBytes)
                zip.closeEntry()
                continue
            }
            val acceptedSnapshot = isAcceptedSnapshotZipPath(name)
            when {
                name == BACKUP_MANIFEST -> {
                    val output = ByteArrayOutputStream()
                    totalBytes = copyBackupEntryBounded(zip, output, limits.maxManifestBytes, totalBytes, limits.maxTotalBytes)
                    manifest = output.toString(Charsets.UTF_8.name())
                }
                name == APP_DATABASE_NAME -> {
                    val out = File(destinationRoot, APP_DATABASE_NAME)
                    out.outputStream().use { output ->
                        totalBytes = copyBackupEntryBounded(zip, output, limits.maxEntryBytes, totalBytes, limits.maxTotalBytes)
                    }
                }
                acceptedSnapshot -> {
                    val out = File(destinationRoot, name)
                    require(isCanonicalFileStrictlyInside(out, listOf(destinationRoot))) { "백업 ZIP 경로가 안전하지 않습니다." }
                    if (!out.parentFile.exists() && !out.parentFile.mkdirs()) error("스냅샷 폴더를 만들 수 없습니다.")
                    out.outputStream().use { output ->
                        totalBytes = copyBackupEntryBounded(zip, output, limits.maxEntryBytes, totalBytes, limits.maxTotalBytes)
                    }
                    snapshots[name] = out
                }
                else -> {
                    totalBytes = copyBackupEntryBounded(zip, null, limits.maxEntryBytes, totalBytes, limits.maxTotalBytes)
                }
            }
            zip.closeEntry()
        }
    }
    return ExtractedDatabaseBackup(manifest, snapshots)
}

private fun validateBackupEntryName(raw: String, isDirectory: Boolean): String {
    val normalized = if (isDirectory) raw.removeSuffix("/") else raw
    require(normalized.isNotBlank() && !normalized.startsWith('/') && !normalized.startsWith('\\')) { "백업 ZIP 경로가 안전하지 않습니다." }
    require('\\' !in normalized) { "백업 ZIP 경로가 안전하지 않습니다." }
    val segments = normalized.split('/')
    require(segments.all { it.isNotBlank() && it != "." && it != ".." && ':' !in it }) { "백업 ZIP 경로가 안전하지 않습니다." }
    return segments.joinToString("/")
}

private fun isAcceptedSnapshotZipPath(name: String): Boolean {
    val parts = name.split('/')
    return parts.size == 3 && parts[0] == "snapshots" && parts[1].startsWith("snapshots_") &&
        parts[1] == safeRestoreFileName(parts[1]) && parts[2] == safeRestoreFileName(parts[2]) &&
        parts[2].endsWith(".html", ignoreCase = true)
}

private fun copyBackupEntryBounded(
    input: InputStream,
    output: OutputStream?,
    entryLimit: Long,
    totalBefore: Long,
    totalLimit: Long,
): Long {
    val buffer = ByteArray(8192)
    var entryBytes = 0L
    var total = totalBefore
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        entryBytes += read
        total += read
        require(entryBytes <= entryLimit) { "백업 ZIP 항목 크기가 제한을 초과했습니다." }
        require(total <= totalLimit) { "백업 ZIP 전체 크기가 제한을 초과했습니다." }
        output?.write(buffer, 0, read)
    }
    return total
}

fun restoreDatabaseBackupFromUri(context: Context, uri: Uri): DatabaseRestoreResult =
    GlobalBotState.withDatabaseMaintenanceLock {
        val db = GlobalBotState.getDb() ?: error("현재 DB가 열려 있지 않습니다.")
        val dao = db.postDao()
        val claimDao = db.moderationClaimDao()
        val tempDir = File(context.cacheDir, "db_restore_${System.currentTimeMillis()}_${UUID.randomUUID()}")
        var tempDirCreated = false
        var snapshotRestoreDir: File? = null
        var createdFiles: Set<File> = emptySet()
        var transactionCommitted = false
        var restoreResult: DatabaseRestoreResult? = null

        try {
            check(tempDir.mkdir()) { "백업 임시 폴더를 만들 수 없습니다." }
            tempDirCreated = true
            val manifestByOriginalPath = mutableMapOf<String, ManifestSnapshot>()
            val extractedSnapshotsByZipPath = mutableMapOf<String, File>()
            val backupDb = File(tempDir, APP_DATABASE_NAME)

            val extracted = context.contentResolver.openInputStream(uri)?.use { input ->
                extractDatabaseBackupZip(input, tempDir)
            } ?: error("백업 파일을 열 수 없습니다.")
            parseManifestSnapshots(extracted.manifestText.orEmpty()).forEach {
                manifestByOriginalPath[it.originalPath] = it
            }
            extractedSnapshotsByZipPath.putAll(extracted.snapshotsByZipPath)

            if (!backupDb.exists()) error("백업 ZIP 안에 $APP_DATABASE_NAME 파일이 없습니다.")

            val backupRows = readBackupDatabaseRows(backupDb)
            val referencedOriginalPaths = buildSet {
                backupRows.posts.mapNotNullTo(this) { it.snapshotPath }
                backupRows.blocks.mapNotNullTo(this) { it.snapshotPath }
                backupRows.holds.mapNotNullTo(this) { it.snapshotPath }
            }
            snapshotRestoreDir = createSnapshotImportStagingDirectory(File(context.cacheDir, "snapshots_imported"))
            val restoredByOriginalPath = restoreSnapshotFiles(
                snapshotRestoreDir = snapshotRestoreDir,
                manifestByOriginalPath = manifestByOriginalPath,
                extractedSnapshotsByZipPath = extractedSnapshotsByZipPath,
                referencedOriginalPaths = referencedOriginalPaths,
            )
            createdFiles = restoredByOriginalPath.values.map(::File).toSet()

            db.runInTransaction {
                var insertedPosts = 0
                var updatedPosts = 0
                var insertedBlocks = 0
                var insertedHolds = 0
                val scopeCounts = backupRows.skippedPostRowsByScope
                    .mapValuesTo(mutableMapOf()) { CheckedPostRestoreCounts(skipped = it.value) }

                backupRows.posts.forEach { backupPost ->
                    val imported = backupPost.copy(snapshotPath = importedSnapshotPath(backupPost.snapshotPath, restoredByOriginalPath))
                    val current = dao.getPost(imported.scopeId, imported.gallType, imported.gallId, imported.postNum)
                    val counts = scopeCounts[imported.scopeId] ?: CheckedPostRestoreCounts()
                    if (current == null) {
                        dao.insertOrUpdate(imported)
                        insertedPosts++
                        scopeCounts[imported.scopeId] = incrementCheckedPostRestoreCounts(counts, CheckedPostRestoreOutcome.INSERTED)
                    } else {
                        val mergedSnapshotPath = mergePostSnapshotPath(current.snapshotPath, imported.snapshotPath, current.checkTime, imported.checkTime)
                        val merged = current.copy(
                            commentCount = maxOf(current.commentCount, imported.commentCount),
                            checkTime = maxOf(current.checkTime, imported.checkTime),
                            title = current.title ?: imported.title,
                            author = current.author ?: imported.author,
                            isBlocked = current.isBlocked || imported.isBlocked,
                            blockReason = current.blockReason ?: imported.blockReason,
                            snapshotPath = mergeImportedSnapshotPath(current.snapshotPath, mergedSnapshotPath),
                            creationDate = current.creationDate ?: imported.creationDate
                        )
                        if (merged != current) {
                            dao.insertOrUpdate(merged)
                            updatedPosts++
                            scopeCounts[imported.scopeId] = incrementCheckedPostRestoreCounts(counts, CheckedPostRestoreOutcome.UPDATED)
                        } else {
                            scopeCounts[imported.scopeId] = incrementCheckedPostRestoreCounts(counts, CheckedPostRestoreOutcome.SKIPPED)
                        }
                    }
                }

                val existingBlocks = dao.getAllBlockHistoryForBackupMerge().map { blockHistoryMergeKey(it) }.toMutableSet()
                backupRows.blocks.forEach { block ->
                    val imported = block.copy(id = 0, snapshotPath = importedSnapshotPath(block.snapshotPath, restoredByOriginalPath))
                    if (existingBlocks.add(blockHistoryMergeKey(imported))) {
                        dao.insertBlockHistory(imported)
                        insertedBlocks++
                    }
                }

                val existingHolds = dao.getAllHoldHistoryForBackupMerge().map { holdHistoryMergeKey(it) }.toMutableSet()
                backupRows.holds.forEach { hold ->
                    val imported = hold.copy(id = 0, snapshotPath = importedSnapshotPath(hold.snapshotPath, restoredByOriginalPath))
                    if (existingHolds.add(holdHistoryMergeKey(imported))) {
                        val inserted = dao.insertHoldHistory(imported)
                        if (inserted >= 0) insertedHolds++
                    }
                }

                backupRows.claims.forEach { imported ->
                    val current = claimDao.find(
                        imported.gallType,
                        imported.gallId,
                        imported.postNum,
                        imported.targetType,
                        imported.targetNo,
                        imported.actionKind,
                    )
                    when {
                        current == null -> claimDao.insertIfAbsent(imported)
                        shouldReplaceClaimForRestore(current.status, imported.status) -> claimDao.replaceForRestore(imported)
                        else -> Unit // Never downgrade existing terminal suppression evidence.
                    }
                }

                restoreResult = DatabaseRestoreResult(
                    insertedPosts = insertedPosts,
                    updatedPosts = updatedPosts,
                    insertedBlockHistory = insertedBlocks,
                    insertedHoldHistory = insertedHolds,
                    restoredSnapshots = restoredByOriginalPath.size,
                    skippedRows = backupRows.skippedOtherRows + scopeCounts.values.sumOf { it.skipped },
                    checkedPostsByScopeId = scopeCounts.toMap(),
                )
            }
            transactionCommitted = true

            val referencedAfterMerge = runCatching {
                dao.getAllSnapshotPaths().mapNotNullTo(mutableSetOf()) {
                    runCatching { File(it).canonicalPath }.getOrNull()
                }
            }.getOrNull()
            cleanupCreatedSnapshotFilesAfterImport(createdFiles, transactionCommitted, referencedAfterMerge)
            snapshotRestoreDir.walkBottomUp()
                .filter { it.isDirectory && it.list().isNullOrEmpty() }
                .forEach(File::delete)
            restoreResult ?: error("DB 복원 트랜잭션 결과가 없습니다.")
        } catch (failure: Exception) {
            if (transactionCommitted && restoreResult != null) {
                Log.e("DatabaseBackup", "DB 복원 커밋 후 정리 실패; 생성된 스냅샷을 보존합니다.", failure)
                restoreResult!!
            } else {
                cleanupCreatedSnapshotFilesAfterImport(createdFiles, transactionCommitted = false, referencedPaths = null)
                snapshotRestoreDir?.deleteRecursively()
                throw failure
            }
        } finally {
            if (tempDirCreated) tempDir.deleteRecursively()
        }
    }

internal fun cleanupCreatedSnapshotFilesAfterImport(
    createdFiles: Set<File>,
    transactionCommitted: Boolean,
    referencedPaths: Set<String>?,
) {
    val livePaths = referencedPaths?.let { paths ->
        buildSet { paths.forEach { addSnapshotPathAndPair(this, it) } }
    }
    val filesToDelete = when {
        !transactionCommitted -> createdFiles
        referencedPaths == null -> emptySet()
        else -> createdFiles.filterTo(mutableSetOf()) { created ->
            created.canonicalPath !in livePaths.orEmpty()
        }
    }
    filesToDelete.forEach(File::delete)
}

fun chooseSnapshotCandidate(current: SnapshotCandidate?, imported: SnapshotCandidate?, preferOlder: Boolean): SnapshotCandidate? {
    if (current == null) return imported
    if (imported == null) return current
    val currentTime = current.recordedAt
    val importedTime = imported.recordedAt
    if (currentTime == null && importedTime == null) return current
    if (currentTime == null) return if (preferOlder) imported else current
    if (importedTime == null) return if (preferOlder) current else imported
    return if (preferOlder) {
        if (importedTime < currentTime) imported else current
    } else {
        if (importedTime > currentTime) imported else current
    }
}

fun checkedPostFromBackupColumns(row: Map<String, Any?>): CheckedPost = CheckedPost(
    scopeId = row.string("scopeId") ?: GLOBAL_SCAN_SCOPE,
    gallType = row.string("gallType") ?: "",
    gallId = row.string("gallId") ?: "",
    postNum = row.string("postNum") ?: "",
    commentCount = row.int("commentCount") ?: 0,
    checkTime = row.long("checkTime") ?: 0L,
    title = row.string("title"),
    author = row.string("author"),
    isBlocked = row.boolean("isBlocked") ?: false,
    blockReason = row.string("blockReason"),
    snapshotPath = row.string("snapshotPath"),
    creationDate = row.string("creationDate")
)

fun blockHistoryFromBackupColumns(row: Map<String, Any?>): BlockHistory = BlockHistory(
    id = 0,
    gallType = row.string("gallType") ?: "",
    gallId = row.string("gallId") ?: "",
    postNum = row.string("postNum") ?: "",
    targetType = row.string("targetType") ?: "POST",
    targetNo = row.string("targetNo") ?: "",
    targetAuthor = row.string("targetAuthor") ?: "",
    targetContent = row.string("targetContent") ?: "",
    blockReason = row.string("blockReason") ?: "",
    blockTime = row.long("blockTime") ?: 0L,
    snapshotPath = row.string("snapshotPath"),
    creationDate = row.string("creationDate"),
    actorBotId = row.string("actorBotId") ?: LEGACY_ACTOR_BOT_ID,
    scopeId = row.string("scopeId") ?: LEGACY_ACTOR_BOT_ID,
)

fun holdHistoryFromBackupColumns(row: Map<String, Any?>): HoldHistory = HoldHistory(
    id = 0,
    gallType = row.string("gallType") ?: "",
    gallId = row.string("gallId") ?: "",
    postNum = row.string("postNum") ?: "",
    targetType = row.string("targetType") ?: "POST",
    targetNo = row.string("targetNo") ?: "",
    targetAuthor = row.string("targetAuthor") ?: "",
    targetContent = row.string("targetContent") ?: "",
    holdReason = row.string("holdReason") ?: "",
    holdTime = row.long("holdTime") ?: 0L,
    snapshotPath = row.string("snapshotPath"),
    creationDate = row.string("creationDate"),
    actorBotId = row.string("actorBotId") ?: LEGACY_ACTOR_BOT_ID,
    scopeId = row.string("scopeId") ?: LEGACY_ACTOR_BOT_ID,
)

internal fun shouldReplaceClaimForRestore(currentStatus: String, importedStatus: String): Boolean =
    (currentStatus == ClaimStatus.PENDING.name || currentStatus == ClaimStatus.FAILED.name) &&
        (importedStatus == ClaimStatus.SUCCEEDED.name || importedStatus == ClaimStatus.UNKNOWN.name)

internal fun moderationClaimFromBackupColumns(
    row: Map<String, Any?>,
    ownerTokenFactory: () -> String = { "restored-${UUID.randomUUID()}" },
): ModerationActionClaim? {
    val rawStatus = row.string("status")?.trim()?.uppercase()
    val status = when (rawStatus) {
        ClaimStatus.SUCCEEDED.name -> ClaimStatus.SUCCEEDED
        ClaimStatus.UNKNOWN.name, ClaimStatus.PENDING.name -> ClaimStatus.UNKNOWN
        ClaimStatus.FAILED.name -> return null
        else -> ClaimStatus.UNKNOWN
    }
    val gallType = row.string("gallType").orEmpty()
    val gallId = row.string("gallId").orEmpty()
    val postNum = row.string("postNum").orEmpty()
    val targetType = row.string("targetType").orEmpty()
    val actionKind = row.string("actionKind").orEmpty()
    val actorBotId = row.string("actorBotId") ?: LEGACY_ACTOR_BOT_ID
    if (gallType.isBlank() || gallId.isBlank() || postNum.isBlank() || targetType.isBlank() || actionKind.isBlank() || actorBotId.isBlank()) return null
    val claimedAt = row.long("claimedAt") ?: 0L
    return ModerationActionClaim(
        gallType = gallType,
        gallId = gallId,
        postNum = postNum,
        targetType = targetType,
        targetNo = row.string("targetNo").orEmpty(),
        actionKind = actionKind,
        status = status.name,
        actorBotId = actorBotId,
        ownerToken = ownerTokenFactory(),
        claimedAt = claimedAt,
        finishedAt = row.long("finishedAt") ?: claimedAt,
    )
}

private data class BackupRows(
    val posts: List<CheckedPost>,
    val blocks: List<BlockHistory>,
    val holds: List<HoldHistory>,
    val claims: List<ModerationActionClaim>,
    val skippedPostRowsByScope: Map<String, Int>,
    val skippedOtherRows: Int,
) {
    val skippedRows: Int get() = skippedPostRowsByScope.values.sum() + skippedOtherRows
}

private fun readBackupDatabaseRows(backupDb: File): BackupRows {
    var sqlite: SQLiteDatabase? = null
    val skippedPosts = mutableMapOf<String, Int>()
    var skippedOther = 0
    return try {
        sqlite = SQLiteDatabase.openDatabase(backupDb.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        val posts = readTable(sqlite, "checked_posts").mapNotNull { row ->
            val scopeId = row.string("scopeId") ?: GLOBAL_SCAN_SCOPE
            val post = runCatching { checkedPostFromBackupColumns(row) }.getOrNull()
            if (post == null || post.gallType.isBlank() || post.gallId.isBlank() || post.postNum.isBlank()) {
                skippedPosts[scopeId] = (skippedPosts[scopeId] ?: 0) + 1
                null
            } else {
                post
            }
        }
        val blocks = readTable(sqlite, "block_history").mapNotNull { row ->
            val history = runCatching { blockHistoryFromBackupColumns(row) }.getOrNull()
            if (history == null || history.gallType.isBlank() || history.gallId.isBlank() || history.postNum.isBlank()) {
                skippedOther++
                null
            } else history
        }
        val holds = readTable(sqlite, "hold_history").mapNotNull { row ->
            val history = runCatching { holdHistoryFromBackupColumns(row) }.getOrNull()
            if (history == null || history.gallType.isBlank() || history.gallId.isBlank() || history.postNum.isBlank()) {
                skippedOther++
                null
            } else history
        }
        val claims = readTable(sqlite, "moderation_action_claims").mapNotNull { row ->
            val claim = runCatching { moderationClaimFromBackupColumns(row) }.getOrNull()
            if (claim == null) {
                if (!row.string("status").equals(ClaimStatus.FAILED.name, ignoreCase = true)) skippedOther++
                null
            } else claim
        }
        BackupRows(posts, blocks, holds, claims, skippedPosts, skippedOther)
    } finally {
        sqlite?.close()
    }
}

private fun readTable(db: SQLiteDatabase, table: String): List<Map<String, Any?>> {
    if (!tableExists(db, table)) return emptyList()
    val rows = mutableListOf<Map<String, Any?>>()
    db.rawQuery("SELECT * FROM `$table`", null).use { cursor ->
        while (cursor.moveToNext()) {
            rows.add(cursor.toColumnMap())
        }
    }
    return rows
}

private fun tableExists(db: SQLiteDatabase, table: String): Boolean {
    db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name=?", arrayOf(table)).use { cursor ->
        return cursor.moveToFirst()
    }
}

private fun Cursor.toColumnMap(): Map<String, Any?> {
    val map = linkedMapOf<String, Any?>()
    for (i in 0 until columnCount) {
        map[getColumnName(i)] = when (getType(i)) {
            Cursor.FIELD_TYPE_INTEGER -> getLong(i)
            Cursor.FIELD_TYPE_FLOAT -> getDouble(i)
            Cursor.FIELD_TYPE_STRING -> getString(i)
            Cursor.FIELD_TYPE_BLOB -> getBlob(i)
            else -> null
        }
    }
    return map
}

internal fun restoreSnapshotFiles(
    snapshotRestoreDir: File,
    manifestByOriginalPath: Map<String, ManifestSnapshot>,
    extractedSnapshotsByZipPath: Map<String, File>,
    referencedOriginalPaths: Set<String> = manifestByOriginalPath.keys,
): Map<String, String> {
    val result = mutableMapOf<String, String>()
    val pathsWithCompanions = buildSet {
        referencedOriginalPaths.forEach { path ->
            add(path)
            // Manifest paths are identifiers from another device; preserve relative legacy keys too.
            snapshotCompanionIdentifier(path)?.let { add(it) }
        }
    }
    pathsWithCompanions.forEach { originalPath ->
        val manifest = manifestByOriginalPath[originalPath] ?: return@forEach
        val zipPath = manifest.zipPath
        val extracted = extractedSnapshotsByZipPath[zipPath] ?: return@forEach
        val parts = zipPath.split('/')
        if (parts.size != 3 || parts[0] != "snapshots" || !parts[1].startsWith("snapshots_")) return@forEach
        val safeBotDirectory = safeRestoreFileName(parts[1])
        val safeFileName = safeRestoreFileName(parts[2])
        if (safeBotDirectory != parts[1] || safeFileName != parts[2]) return@forEach
        val botRestoreDir = File(snapshotRestoreDir, safeBotDirectory)
        if (!botRestoreDir.exists() && !botRestoreDir.mkdirs()) return@forEach
        val out = copySnapshotToAtomicNewFile(botRestoreDir, safeFileName) { output ->
            extracted.inputStream().use { input ->
                copyBackupEntryBounded(input, output, DATABASE_BACKUP_MAX_ENTRY_BYTES, 0L, DATABASE_BACKUP_MAX_ENTRY_BYTES)
            }
        }
        if (!isCanonicalFileStrictlyInside(out, listOf(snapshotRestoreDir))) {
            out.delete()
            return@forEach
        }
        result[originalPath] = out.canonicalPath
    }
    return result
}

internal fun createSnapshotImportStagingDirectory(root: File, importId: String = UUID.randomUUID().toString()): File {
    check(root.mkdirs() || root.isDirectory) { "스냅샷 복원 폴더를 만들 수 없습니다." }
    val safeId = safeRestoreFileName(importId)
    var candidate = File(root, safeId)
    var suffix = 2
    while (!candidate.mkdir()) {
        check(candidate.exists()) { "스냅샷 복원 스테이징 폴더를 만들 수 없습니다." }
        candidate = File(root, "${safeId}_$suffix")
        suffix++
    }
    return candidate
}

internal fun cleanupCreatedSnapshotFiles(createdFiles: Set<File>, referencedPaths: Set<String>) {
    createdFiles.forEach { file ->
        val path = runCatching { file.canonicalPath }.getOrElse { file.absolutePath }
        if (path !in referencedPaths) file.delete()
    }
}

internal fun resolveRestoredSnapshotPath(originalPath: String?, restoredByOriginalPath: Map<String, String>): String? {
    if (originalPath.isNullOrBlank()) return null
    return restoredByOriginalPath[originalPath]
}

internal fun importedSnapshotPath(
    backupPath: String?,
    restoredByOriginalPath: Map<String, String>,
): String? = resolveRestoredSnapshotPath(backupPath, restoredByOriginalPath)

internal fun mergeImportedSnapshotPath(currentTrustedPath: String?, restoredImportedPath: String?): String? =
    restoredImportedPath ?: currentTrustedPath

private fun mergePostSnapshotPath(currentPath: String?, importedPath: String?, currentTime: Long, importedTime: Long): String? {
    if (currentPath.isNullOrBlank()) return importedPath
    if (importedPath.isNullOrBlank()) return currentPath
    val currentInitial = snapshotPairCandidate(currentPath, initial = true, fallbackTime = currentTime)
    val importedInitial = snapshotPairCandidate(importedPath, initial = true, fallbackTime = importedTime)
    val currentLatest = snapshotPairCandidate(currentPath, initial = false, fallbackTime = currentTime)
    val importedLatest = snapshotPairCandidate(importedPath, initial = false, fallbackTime = importedTime)
    val chosenInitial = chooseSnapshotCandidate(currentInitial, importedInitial, preferOlder = true)
    val chosenLatest = chooseSnapshotCandidate(currentLatest, importedLatest, preferOlder = false)
    return chosenLatest?.path ?: chosenInitial?.path ?: currentPath
}

private fun snapshotPairCandidate(path: String, initial: Boolean, fallbackTime: Long): SnapshotCandidate? {
    val targetPath = when {
        initial && path.endsWith("_latest.html") -> path.replace("_latest.html", "_initial.html")
        !initial && path.endsWith("_initial.html") -> path.replace("_initial.html", "_latest.html")
        initial && path.endsWith("_initial.html") -> path
        !initial && path.endsWith("_latest.html") -> path
        else -> path
    }
    val file = File(targetPath)
    if (!file.exists()) return null
    return SnapshotCandidate(file.absolutePath, inferSnapshotTime(file.absolutePath, fallbackTime))
}

private fun inferSnapshotTime(path: String, fallbackTime: Long?): Long? {
    val fileName = File(path).name
    blockedSnapshotRecordedAtFromName(fileName)?.let { return it }
    val fileTime = runCatching { File(path).takeIf { it.exists() }?.lastModified()?.takeIf { it > 0L } }.getOrNull()
    return fileTime ?: fallbackTime
}

fun blockedSnapshotRecordedAtFromName(fileName: String): Long? {
    val marker = "_blocked_"
    val suffix = ".html"
    if (!fileName.endsWith(suffix)) return null
    val markerIndex = fileName.lastIndexOf(marker)
    if (markerIndex < 0) return null
    val start = markerIndex + marker.length
    val end = fileName.length - suffix.length
    if (start >= end) return null
    val digits = fileName.substring(start, end)
    if (digits.any { !it.isDigit() }) return null
    return digits.toLongOrNull()
}

private fun buildManifestJson(snapshots: List<BackupSnapshotFile>): String {
    val entries = snapshots.joinToString(",") { snapshot ->
        "{" +
            "\"zipPath\":\"${jsonEscape(sanitizeZipPath(snapshot.zipPath))}\"," +
            "\"originalPath\":\"${jsonEscape(snapshot.originalPath)}\"," +
            "\"gallType\":${jsonNullable(snapshot.gallType)}," +
            "\"gallId\":${jsonNullable(snapshot.gallId)}," +
            "\"postNum\":${jsonNullable(snapshot.postNum)}," +
            "\"kind\":${jsonNullable(snapshot.kind)}," +
            "\"recordedAt\":${snapshot.recordedAt ?: "null"}" +
            "}"
    }
    return "{" +
        "\"format\":\"armbandbot-db-backup\"," +
        "\"formatVersion\":$BACKUP_FORMAT_VERSION," +
        "\"appVersion\":\"${jsonEscape(ARMBANDBOT_APP_VERSION)}\"," +
        "\"createdAt\":${System.currentTimeMillis()}," +
        "\"databaseName\":\"$APP_DATABASE_NAME\"," +
        "\"snapshotEntries\":[${entries}]" +
        "}"
}

private fun parseManifestSnapshots(text: String): List<ManifestSnapshot> {
    return runCatching {
        val root = JSONObject(text)
        val array = root.optJSONArray("snapshotEntries") ?: return emptyList()
        buildList {
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val zipPath = obj.optString("zipPath").takeIf { it.isNotBlank() } ?: continue
                val originalPath = obj.optString("originalPath").takeIf { it.isNotBlank() } ?: zipPath
                add(
                    ManifestSnapshot(
                        zipPath = sanitizeZipPath(zipPath),
                        originalPath = originalPath,
                        gallType = obj.optNullableString("gallType"),
                        gallId = obj.optNullableString("gallId"),
                        postNum = obj.optNullableString("postNum"),
                        kind = obj.optNullableString("kind"),
                        recordedAt = obj.optNullableLong("recordedAt")
                    )
                )
            }
        }
    }.getOrDefault(emptyList())
}

private fun JSONObject.optNullableString(key: String): String? {
    if (!has(key) || isNull(key)) return null
    return optString(key).takeIf { it.isNotBlank() }
}

private fun JSONObject.optNullableLong(key: String): Long? {
    if (!has(key) || isNull(key)) return null
    return runCatching { getLong(key) }.getOrNull()
}

private fun jsonNullable(value: String?): String = value?.let { "\"${jsonEscape(it)}\"" } ?: "null"

private fun jsonEscape(value: String): String = value
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")
    .replace("\n", "\\n")
    .replace("\r", "\\r")

private fun sanitizeZipPath(path: String): String = path.replace('\\', '/').trimStart('/').split('/').filter { it.isNotBlank() && it != "." && it != ".." }.joinToString("/")

private fun assignSnapshotZipPaths(snapshots: List<BackupSnapshotFile>): List<BackupSnapshotFile> {
    // Allocate directories, not filename suffixes: initial/latest must remain siblings.
    val groups = snapshots.groupBy { snapshot ->
        require(isAcceptedSnapshotZipPath(snapshot.zipPath)) { "백업 스냅샷 경로가 안전하지 않습니다." }
        snapshot.zipPath.substringBeforeLast('/') to snapshot.file.absoluteFile.parentFile!!.canonicalPath
    }
    val reserved = groups.keys.mapTo(mutableSetOf()) { it.first }
    val assigned = mutableSetOf<String>()
    val directories = groups.keys.sortedWith(compareBy({ it.first }, { it.second })).associateWith { key ->
        var directory = key.first
        if (!assigned.add(directory)) {
            var index = 2
            while ("${key.first}_$index" in reserved || "${key.first}_$index" in assigned) index++
            directory = "${key.first}_$index"
            assigned.add(directory)
        }
        directory
    }
    val owners = mutableMapOf<String, String>()
    return snapshots.map { snapshot ->
        val key = snapshot.zipPath.substringBeforeLast('/') to snapshot.file.absoluteFile.parentFile!!.canonicalPath
        val zipPath = "${directories.getValue(key)}/${snapshot.zipPath.substringAfterLast('/')}"
        val owner = owners.putIfAbsent(zipPath, snapshot.file.canonicalPath)
        require(owner == null || owner == snapshot.file.canonicalPath) { "서로 다른 스냅샷이 같은 백업 경로를 사용합니다." }
        snapshot.copy(zipPath = zipPath)
    }
}

fun safeRestoreFileName(name: String): String {
    val safe = name.map { ch ->
        when {
            ch.isLetterOrDigit() -> ch
            ch == '.' || ch == '_' || ch == '-' -> ch
            else -> '_'
        }
    }.joinToString("")
    return safe.ifBlank { "snapshot.html" }
}

internal fun copySnapshotToAtomicNewFile(
    dir: File,
    name: String,
    write: (OutputStream) -> Unit,
): File {
    val safeName = safeRestoreFileName(name)
    val first = File(dir, safeName)
    val base = first.nameWithoutExtension
    val ext = first.extension.takeIf { it.isNotBlank() }?.let { ".$it" } ?: ""
    var index = 1
    while (true) {
        val candidate = if (index == 1) first else File(dir, "${base}_$index$ext")
        if (candidate.createNewFile()) {
            try {
                FileOutputStream(candidate).use(write)
                return candidate
            } catch (failure: Exception) {
                candidate.delete()
                throw failure
            }
        }
        index++
    }
}

private fun parseSnapshotFileName(name: String): Pair<String, String>? {
    val cleaned = name.removeSuffix(".html")
    val parts = cleaned.split('_')
    if (parts.size < 3) return null
    return parts[0] to parts[1]
}

private fun snapshotKind(name: String): String? = when {
    name.endsWith("_initial.html") -> "initial"
    name.endsWith("_latest.html") -> "latest"
    name.contains("_blocked_") -> "blocked"
    else -> null
}

internal fun blockHistoryMergeKey(history: BlockHistory): String = listOf(
    history.scopeId,
    history.actorBotId,
    history.gallType,
    history.gallId,
    history.postNum,
    history.targetType,
    history.targetNo,
    history.targetAuthor,
    history.targetContent,
    history.blockTime.toString()
).joinToString("\u001f")

internal fun holdHistoryMergeKey(history: HoldHistory): String = listOf(
    history.actorBotId,
    history.gallType,
    history.gallId,
    history.postNum,
    history.targetType,
    history.targetNo
).joinToString("\u001f")

private fun Map<String, Any?>.string(key: String): String? = this[key]?.toString()?.takeIf { it.isNotBlank() }
private fun Map<String, Any?>.long(key: String): Long? = when (val value = this[key]) {
    is Number -> value.toLong()
    is String -> value.toLongOrNull()
    else -> null
}
private fun Map<String, Any?>.int(key: String): Int? = long(key)?.toInt()
private fun Map<String, Any?>.boolean(key: String): Boolean? = when (val value = this[key]) {
    is Boolean -> value
    is Number -> value.toInt() != 0
    is String -> value == "1" || value.equals("true", ignoreCase = true)
    else -> null
}

package com.heyheyon.armbandbot

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import java.io.File
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
private const val BACKUP_FORMAT_VERSION = 2

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

data class DatabaseRestoreResult(
    val insertedPosts: Int = 0,
    val updatedPosts: Int = 0,
    val insertedBlockHistory: Int = 0,
    val insertedHoldHistory: Int = 0,
    val restoredSnapshots: Int = 0,
    val skippedRows: Int = 0
) {
    val changedRows: Int get() = insertedPosts + updatedPosts + insertedBlockHistory + insertedHoldHistory
}

private data class ManifestSnapshot(
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

fun databaseBackupSourceFiles(context: Context): List<File> {
    val db = context.getDatabasePath(APP_DATABASE_NAME)
    return listOf(
        db,
        File(db.path + "-wal"),
        File(db.path + "-shm")
    ).filter { it.exists() && it.isFile }
}

fun collectDatabaseBackupSnapshotFiles(context: Context): List<BackupSnapshotFile> {
    val paths = linkedSetOf<String>()
    GlobalBotState.getDb()?.postDao()?.getAllSnapshotPaths()?.forEach { path ->
        addSnapshotPathAndPair(paths, path)
    }
    context.cacheDir.listFiles()
        ?.filter { it.isDirectory && it.name.startsWith("snapshots_") }
        ?.forEach { dir ->
            dir.listFiles()?.filter { it.isFile && it.extension.equals("html", ignoreCase = true) }?.forEach { file ->
                paths.add(file.absolutePath)
            }
        }
    return paths.mapNotNull { path ->
        val file = File(path)
        if (!file.exists() || !file.isFile) return@mapNotNull null
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
    }.distinctBy { it.zipPath }
}

private fun addSnapshotPathAndPair(paths: MutableSet<String>, path: String?) {
    if (path.isNullOrBlank()) return
    val file = File(path)
    paths.add(file.absolutePath)
    val absolutePath = file.absolutePath
    when {
        absolutePath.endsWith("_latest.html") -> paths.add(absolutePath.replace("_latest.html", "_initial.html"))
        absolutePath.endsWith("_initial.html") -> paths.add(absolutePath.replace("_initial.html", "_latest.html"))
    }
}

fun writeDatabaseBackupZip(files: List<File>, outputStream: OutputStream): Int {
    return writeDatabaseBackupZip(databaseFiles = files, snapshotFiles = emptyList(), outputStream = outputStream)
}

fun writeDatabaseBackupZip(
    databaseFiles: List<File>,
    snapshotFiles: List<BackupSnapshotFile>,
    outputStream: OutputStream
): Int {
    val existingDbFiles = databaseFiles.filter { it.exists() && it.isFile }
    val existingSnapshots = snapshotFiles.filter { it.file.exists() && it.file.isFile }
    require(existingDbFiles.isNotEmpty()) { "백업할 DB 파일이 없습니다." }

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
        existingSnapshots.forEach { snapshot ->
            val entryName = uniqueZipPath(sanitizeZipPath(snapshot.zipPath), usedEntries)
            zip.putNextEntry(ZipEntry(entryName))
            snapshot.file.inputStream().use { input -> input.copyTo(zip) }
            zip.closeEntry()
        }
    }
    return existingDbFiles.size + existingSnapshots.size
}

fun backupDatabaseToUri(context: Context, uri: Uri): Int {
    GlobalBotState.getDb()?.openHelper?.writableDatabase?.query("PRAGMA wal_checkpoint(FULL)")?.use { }
    val files = databaseBackupSourceFiles(context)
    val snapshots = collectDatabaseBackupSnapshotFiles(context)
    val output = context.contentResolver.openOutputStream(uri)
        ?: error("백업 파일을 열 수 없습니다.")
    return output.use { writeDatabaseBackupZip(files, snapshots, it) }
}

fun restoreDatabaseBackupFromUri(context: Context, uri: Uri): DatabaseRestoreResult {
    val db = GlobalBotState.getDb() ?: error("현재 DB가 열려 있지 않습니다.")
    val dao = db.postDao()
    val tempDir = File(context.cacheDir, "db_restore_${System.currentTimeMillis()}_${UUID.randomUUID()}")
    tempDir.mkdirs()
    return try {
        val manifestByOriginalPath = mutableMapOf<String, ManifestSnapshot>()
        val extractedSnapshotsByZipPath = mutableMapOf<String, File>()
        val backupDb = File(tempDir, APP_DATABASE_NAME)

        context.contentResolver.openInputStream(uri)?.use { input ->
            ZipInputStream(input.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val safeName = sanitizeZipPath(entry.name)
                    if (safeName.isBlank() || entry.isDirectory || safeName.contains("..")) {
                        zip.closeEntry()
                        continue
                    }
                    when {
                        safeName == BACKUP_MANIFEST -> {
                            val text = zip.readBytes().toString(Charsets.UTF_8)
                            parseManifestSnapshots(text).forEach { manifestByOriginalPath[it.originalPath] = it }
                        }
                        safeName == APP_DATABASE_NAME -> {
                            backupDb.outputStream().use { output -> zip.copyTo(output) }
                        }
                        safeName.startsWith("snapshots/") && safeName.endsWith(".html", ignoreCase = true) -> {
                            val out = File(tempDir, safeName)
                            out.parentFile?.mkdirs()
                            out.outputStream().use { output -> zip.copyTo(output) }
                            extractedSnapshotsByZipPath[safeName] = out
                        }
                    }
                    zip.closeEntry()
                }
            }
        } ?: error("백업 파일을 열 수 없습니다.")

        if (!backupDb.exists()) error("백업 ZIP 안에 $APP_DATABASE_NAME 파일이 없습니다.")

        val snapshotRestoreDir = File(context.cacheDir, "snapshots_imported")
        snapshotRestoreDir.mkdirs()
        val restoredByOriginalPath = restoreSnapshotFiles(
            snapshotRestoreDir = snapshotRestoreDir,
            manifestByOriginalPath = manifestByOriginalPath,
            extractedSnapshotsByZipPath = extractedSnapshotsByZipPath
        )

        val backupRows = readBackupDatabaseRows(backupDb)
        var insertedPosts = 0
        var updatedPosts = 0
        var insertedBlocks = 0
        var insertedHolds = 0
        var skippedRows = backupRows.skippedRows

        backupRows.posts.forEach { backupPost ->
            try {
                val restoredPath = resolveRestoredSnapshotPath(backupPost.snapshotPath, restoredByOriginalPath)
                val imported = backupPost.copy(snapshotPath = restoredPath ?: backupPost.snapshotPath)
                val current = dao.getPost(imported.gallType, imported.gallId, imported.postNum)
                if (current == null) {
                    dao.insertOrUpdate(imported)
                    insertedPosts++
                } else {
                    val mergedSnapshotPath = mergePostSnapshotPath(current.snapshotPath, imported.snapshotPath, current.checkTime, imported.checkTime)
                    val merged = current.copy(
                        commentCount = maxOf(current.commentCount, imported.commentCount),
                        checkTime = maxOf(current.checkTime, imported.checkTime),
                        title = current.title ?: imported.title,
                        author = current.author ?: imported.author,
                        isBlocked = current.isBlocked || imported.isBlocked,
                        blockReason = current.blockReason ?: imported.blockReason,
                        snapshotPath = mergedSnapshotPath ?: current.snapshotPath ?: imported.snapshotPath,
                        creationDate = current.creationDate ?: imported.creationDate
                    )
                    if (merged != current) {
                        dao.insertOrUpdate(merged)
                        updatedPosts++
                    }
                }
            } catch (_: Exception) {
                skippedRows++
            }
        }

        val existingBlocks = dao.getAllBlockHistoryForBackupMerge().map { blockMergeKey(it) }.toMutableSet()
        backupRows.blocks.forEach { block ->
            try {
                val restoredPath = resolveRestoredSnapshotPath(block.snapshotPath, restoredByOriginalPath)
                val imported = block.copy(id = 0, snapshotPath = restoredPath ?: block.snapshotPath)
                if (existingBlocks.add(blockMergeKey(imported))) {
                    dao.insertBlockHistory(imported)
                    insertedBlocks++
                }
            } catch (_: Exception) {
                skippedRows++
            }
        }

        val existingHolds = dao.getAllHoldHistoryForBackupMerge().map { holdMergeKey(it) }.toMutableSet()
        backupRows.holds.forEach { hold ->
            try {
                val restoredPath = resolveRestoredSnapshotPath(hold.snapshotPath, restoredByOriginalPath)
                val imported = hold.copy(id = 0, snapshotPath = restoredPath ?: hold.snapshotPath)
                if (existingHolds.add(holdMergeKey(imported))) {
                    val inserted = dao.insertHoldHistory(imported)
                    if (inserted >= 0) insertedHolds++
                }
            } catch (_: Exception) {
                skippedRows++
            }
        }

        DatabaseRestoreResult(
            insertedPosts = insertedPosts,
            updatedPosts = updatedPosts,
            insertedBlockHistory = insertedBlocks,
            insertedHoldHistory = insertedHolds,
            restoredSnapshots = restoredByOriginalPath.size,
            skippedRows = skippedRows
        )
    } finally {
        tempDir.deleteRecursively()
    }
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
    creationDate = row.string("creationDate")
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
    creationDate = row.string("creationDate")
)

private data class BackupRows(
    val posts: List<CheckedPost>,
    val blocks: List<BlockHistory>,
    val holds: List<HoldHistory>,
    val skippedRows: Int
)

private fun readBackupDatabaseRows(backupDb: File): BackupRows {
    var sqlite: SQLiteDatabase? = null
    var skipped = 0
    return try {
        sqlite = SQLiteDatabase.openDatabase(backupDb.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        val posts = readTable(sqlite, "checked_posts").mapNotNull { row ->
            runCatching { checkedPostFromBackupColumns(row) }.getOrNull().also { if (it == null) skipped++ }
        }.filter { it.gallType.isNotBlank() && it.gallId.isNotBlank() && it.postNum.isNotBlank() }
        val blocks = readTable(sqlite, "block_history").mapNotNull { row ->
            runCatching { blockHistoryFromBackupColumns(row) }.getOrNull().also { if (it == null) skipped++ }
        }.filter { it.gallType.isNotBlank() && it.gallId.isNotBlank() && it.postNum.isNotBlank() }
        val holds = readTable(sqlite, "hold_history").mapNotNull { row ->
            runCatching { holdHistoryFromBackupColumns(row) }.getOrNull().also { if (it == null) skipped++ }
        }.filter { it.gallType.isNotBlank() && it.gallId.isNotBlank() && it.postNum.isNotBlank() }
        BackupRows(posts, blocks, holds, skipped)
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

private fun restoreSnapshotFiles(
    snapshotRestoreDir: File,
    manifestByOriginalPath: Map<String, ManifestSnapshot>,
    extractedSnapshotsByZipPath: Map<String, File>
): Map<String, String> {
    val result = mutableMapOf<String, String>()
    extractedSnapshotsByZipPath.forEach { (zipPath, extracted) ->
        val manifest = manifestByOriginalPath.values.firstOrNull { it.zipPath == zipPath }
        val originalPath = manifest?.originalPath ?: zipPath
        val out = uniqueFile(snapshotRestoreDir, File(zipPath).name)
        extracted.copyTo(out, overwrite = true)
        result[originalPath] = out.absolutePath
    }
    return result
}

private fun resolveRestoredSnapshotPath(originalPath: String?, restoredByOriginalPath: Map<String, String>): String? {
    if (originalPath.isNullOrBlank()) return null
    restoredByOriginalPath[originalPath]?.let { return it }
    val counterpart = when {
        originalPath.endsWith("_latest.html") -> originalPath.replace("_latest.html", "_initial.html")
        originalPath.endsWith("_initial.html") -> originalPath.replace("_initial.html", "_latest.html")
        else -> null
    }
    return counterpart?.let { restoredByOriginalPath[it] }
}

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
    Regex("_blocked_(\\d+)\\.html$").find(fileName)?.groupValues?.getOrNull(1)?.toLongOrNull()?.let { return it }
    val fileTime = runCatching { File(path).takeIf { it.exists() }?.lastModified()?.takeIf { it > 0L } }.getOrNull()
    return fileTime ?: fallbackTime
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
    val arrayText = Regex("\"snapshotEntries\"\\s*:\\s*\\[(.*)]", RegexOption.DOT_MATCHES_ALL)
        .find(text)?.groupValues?.getOrNull(1) ?: return emptyList()
    return Regex("\\{([^{}]*)}").findAll(arrayText).mapNotNull { match ->
        val obj = match.groupValues[1]
        val zipPath = jsonValue(obj, "zipPath") ?: return@mapNotNull null
        val originalPath = jsonValue(obj, "originalPath") ?: zipPath
        ManifestSnapshot(
            zipPath = sanitizeZipPath(zipPath),
            originalPath = originalPath,
            gallType = jsonValue(obj, "gallType"),
            gallId = jsonValue(obj, "gallId"),
            postNum = jsonValue(obj, "postNum"),
            kind = jsonValue(obj, "kind"),
            recordedAt = Regex("\"recordedAt\"\\s*:\\s*(\\d+)").find(obj)?.groupValues?.getOrNull(1)?.toLongOrNull()
        )
    }.toList()
}

private fun jsonValue(obj: String, key: String): String? {
    val raw = Regex("\"${Regex.escape(key)}\"\\s*:\\s*\"((?:\\\\.|[^\"])*)\"").find(obj)?.groupValues?.getOrNull(1) ?: return null
    return raw.replace("\\\"", "\"").replace("\\\\", "\\")
}

private fun jsonNullable(value: String?): String = value?.let { "\"${jsonEscape(it)}\"" } ?: "null"

private fun jsonEscape(value: String): String = value
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")
    .replace("\n", "\\n")
    .replace("\r", "\\r")

private fun sanitizeZipPath(path: String): String = path.replace('\\', '/').trimStart('/').split('/').filter { it.isNotBlank() && it != "." && it != ".." }.joinToString("/")

private fun uniqueZipPath(path: String, used: MutableSet<String>): String {
    if (used.add(path)) return path
    val file = File(path)
    val parent = file.parent?.replace('\\', '/')?.trimEnd('/')
    val baseName = file.nameWithoutExtension
    val extension = file.extension.takeIf { it.isNotBlank() }?.let { ".$it" } ?: ""
    var index = 2
    while (true) {
        val candidate = listOfNotNull(parent, "${baseName}_$index$extension").joinToString("/")
        if (used.add(candidate)) return candidate
        index++
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

private fun uniqueFile(dir: File, name: String): File {
    val safeName = safeRestoreFileName(name)
    var out = File(dir, safeName)
    if (!out.exists()) return out
    val base = out.nameWithoutExtension
    val ext = out.extension.takeIf { it.isNotBlank() }?.let { ".$it" } ?: ""
    var i = 2
    while (out.exists()) {
        out = File(dir, "${base}_$i$ext")
        i++
    }
    return out
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

private fun blockMergeKey(history: BlockHistory): String = listOf(
    history.gallType,
    history.gallId,
    history.postNum,
    history.targetType,
    history.targetNo,
    history.targetAuthor,
    history.targetContent,
    history.blockTime.toString()
).joinToString("\u001f")

private fun holdMergeKey(history: HoldHistory): String = listOf(
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

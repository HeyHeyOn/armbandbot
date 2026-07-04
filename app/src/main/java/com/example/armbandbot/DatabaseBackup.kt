package com.heyheyon.armbandbot

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

private const val APP_DATABASE_NAME = "bot_database"

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

fun writeDatabaseBackupZip(files: List<File>, outputStream: OutputStream): Int {
    require(files.any { it.exists() && it.isFile }) { "백업할 DB 파일이 없습니다." }
    ZipOutputStream(outputStream.buffered()).use { zip ->
        files.filter { it.exists() && it.isFile }.forEach { file ->
            zip.putNextEntry(ZipEntry(file.name))
            file.inputStream().use { input -> input.copyTo(zip) }
            zip.closeEntry()
        }
    }
    return files.count { it.exists() && it.isFile }
}

fun backupDatabaseToUri(context: Context, uri: Uri): Int {
    GlobalBotState.getDb()?.openHelper?.writableDatabase?.query("PRAGMA wal_checkpoint(FULL)")?.use { }
    val files = databaseBackupSourceFiles(context)
    val output = context.contentResolver.openOutputStream(uri)
        ?: error("백업 파일을 열 수 없습니다.")
    return writeDatabaseBackupZip(files, output)
}

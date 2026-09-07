package com.heyheyon.armbandbot

import android.content.Context
import java.io.File

internal fun copyDatabaseFilesForMigration(sourceDatabase: File, destinationDirectory: File): List<File> {
    val sources = listOf(
        sourceDatabase,
        File(sourceDatabase.path + "-wal"),
        File(sourceDatabase.path + "-shm"),
    ).filter { it.isFile }
    if (sources.isEmpty()) return emptyList()

    check(destinationDirectory.mkdirs() || destinationDirectory.isDirectory) {
        "DB 마이그레이션 백업 폴더를 만들 수 없습니다."
    }
    return sources.map { source ->
        val destination = File(destinationDirectory, source.name)
        source.copyTo(destination, overwrite = false)
        destination
    }
}

internal fun ensurePreMigrationDatabaseBackup(context: Context, targetVersion: Int) {
    val appContext = context.applicationContext
    val preferences = appContext.getSharedPreferences("database_migration_safety", Context.MODE_PRIVATE)
    val marker = "backed_up_before_v$targetVersion"
    if (preferences.getBoolean(marker, false)) return

    synchronized(AppDatabase::class.java) {
        if (preferences.getBoolean(marker, false)) return
        val source = appContext.getDatabasePath("bot_database")
        val destination = File(
            appContext.filesDir,
            "db_migration_backups/v${targetVersion}_${System.currentTimeMillis()}",
        )
        copyDatabaseFilesForMigration(source, destination)
        check(preferences.edit().putBoolean(marker, true).commit()) {
            "DB 마이그레이션 백업 상태를 기록할 수 없습니다."
        }
    }
}

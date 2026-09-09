package com.heyheyon.armbandbot

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppDatabaseMigration8To9Test {
    private lateinit var context: Context
    private val databaseName = "migration-8-9-test"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(databaseName)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(databaseName)
    }

    @Test
    fun migrationPreservesRowsAndAddsScopeActorsAndClaims() {
        open(version = 8, onCreate = ::createVersion8Schema).use { db ->
            db.execSQL(
                """INSERT INTO checked_posts
                    (gallType, gallId, postNum, commentCount, checkTime, title, author, isBlocked, blockReason, snapshotPath, creationDate)
                    VALUES ('M', 'test', '123', 4, 99, 'title', 'author', 1, 'reason', '/tmp/snapshot.html', '2026-09-07')""".trimIndent()
            )
            db.execSQL(
                """INSERT INTO block_history
                    (gallType, gallId, postNum, targetType, targetNo, targetAuthor, targetContent, blockReason, blockTime, snapshotPath, creationDate)
                    VALUES ('M', 'test', '123', 'post', '', 'author', 'body', 'reason', 100, NULL, NULL)""".trimIndent()
            )
            db.execSQL(
                """INSERT INTO hold_history
                    (gallType, gallId, postNum, targetType, targetNo, targetAuthor, targetContent, holdReason, holdTime, snapshotPath, creationDate)
                    VALUES ('M', 'test', '123', 'comment', '7', 'reply', 'text', 'reason', 101, NULL, NULL)""".trimIndent()
            )
        }

        open(version = 9, onUpgrade = { db, oldVersion, newVersion ->
            assertEquals(8, oldVersion)
            assertEquals(9, newVersion)
            AppDatabase.MIGRATION_8_9.migrate(db)
        }).use { db ->
            db.query("SELECT scopeId, commentCount, snapshotPath FROM checked_posts").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(GLOBAL_SCAN_SCOPE, cursor.getString(0))
                assertEquals(4, cursor.getInt(1))
                assertEquals("/tmp/snapshot.html", cursor.getString(2))
                assertEquals(1, cursor.count)
            }
            db.query("SELECT actorBotId FROM block_history").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(LEGACY_ACTOR_BOT_ID, cursor.getString(0))
            }
            db.query("SELECT actorBotId FROM hold_history").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(LEGACY_ACTOR_BOT_ID, cursor.getString(0))
            }
            db.query("SELECT name FROM sqlite_master WHERE type='table' AND name='moderation_action_claims'").use { cursor ->
                assertTrue(cursor.moveToFirst())
            }
            db.query("PRAGMA table_info('moderation_action_claims')").use { cursor ->
                val nameColumn = cursor.getColumnIndexOrThrow("name")
                val notNullColumn = cursor.getColumnIndexOrThrow("notnull")
                val defaultColumn = cursor.getColumnIndexOrThrow("dflt_value")
                var tokenFound = false
                while (cursor.moveToNext()) {
                    if (cursor.getString(nameColumn) == "ownerToken") {
                        tokenFound = true
                        assertEquals(1, cursor.getInt(notNullColumn))
                        assertEquals("''", cursor.getString(defaultColumn))
                    }
                }
                assertTrue("claim owner token column이 생성되어야 합니다.", tokenFound)
            }
            db.query("PRAGMA index_list('checked_posts')").use { cursor ->
                val nameColumn = cursor.getColumnIndexOrThrow("name")
                var found = false
                while (cursor.moveToNext()) {
                    if (cursor.getString(nameColumn) == CHECKED_POST_SCOPE_INDEX_NAME) found = true
                }
                assertTrue("checked_posts scope index가 생성되어야 합니다.", found)
            }
            db.query("PRAGMA index_list('hold_history')").use { cursor ->
                val nameColumn = cursor.getColumnIndexOrThrow("name")
                var found = false
                while (cursor.moveToNext()) {
                    if (cursor.getString(nameColumn) == HOLD_HISTORY_UNIQUE_INDEX_NAME) found = true
                }
                assertTrue("hold_history 대상 unique index가 유지되어야 합니다.", found)
            }
        }
    }

    @Test
    fun actualRoomOpenMigratesLegacyDatabaseAndValidatesGeneratedSchema() {
        open(version = 8, onCreate = ::createVersion8Schema).use { db ->
            db.execSQL(
                """INSERT INTO checked_posts
                    (gallType, gallId, postNum, commentCount, checkTime, title, author, isBlocked, blockReason, snapshotPath, creationDate)
                    VALUES ('M', 'legacy', '9', 7, 123, 'preserved', 'writer', 0, NULL, NULL, '2026-09-07')""".trimIndent()
            )
        }
        val room = androidx.room.Room.databaseBuilder(context, AppDatabase::class.java, databaseName)
            .addMigrations(AppDatabase.MIGRATION_8_9, AppDatabase.MIGRATION_9_10)
            .build()
        try {
            // Opening through Room, not only executing SQL, validates its generated schema.
            val post = room.postDao().getPost(GLOBAL_SCAN_SCOPE, "M", "legacy", "9")
            assertEquals("preserved", post?.title)
            assertEquals("writer", post?.author)
            assertEquals(7, post?.commentCount)
            assertEquals(123L, post?.checkTime)
            assertEquals(10, room.openHelper.writableDatabase.version)
        } finally {
            room.close()
        }
    }

    @Test
    fun actualRoomOpenFromV9PreservesUnknownProvenanceAndGlobalHoldDedup() {
        open(version = 9, onCreate = { db -> createVersion8Schema(db); AppDatabase.MIGRATION_8_9.migrate(db) }).use { db ->
            db.execSQL("INSERT INTO block_history (gallType,gallId,postNum,targetType,targetNo,targetAuthor,targetContent,blockReason,blockTime,actorBotId) VALUES ('M','fixture','1','POST','1','author','body','reason',99,'same-bot')")
            db.execSQL("INSERT INTO hold_history (gallType,gallId,postNum,targetType,targetNo,targetAuthor,targetContent,holdReason,holdTime,actorBotId) VALUES ('M','fixture','2','POST','2','author','body','reason',98,'same-bot')")
        }
        val room = androidx.room.Room.databaseBuilder(context, AppDatabase::class.java, databaseName)
            .addMigrations(AppDatabase.MIGRATION_9_10).build()
        try {
            assertEquals(10, room.openHelper.writableDatabase.version)
            val dao = room.postDao()
            val old = dao.getAllBlockHistoryForBackupMerge().single()
            assertEquals(LEGACY_ACTOR_BOT_ID, old.scopeId)
            assertEquals("same-bot", old.actorBotId)
            assertEquals("body", old.targetContent)
            assertEquals(99L, old.blockTime)
            val hold = dao.getAllHoldHistoryForBackupMerge().single()
            assertEquals(LEGACY_ACTOR_BOT_ID, hold.scopeId)
            assertEquals(-1L, dao.insertHoldHistory(hold.copy(id = 0, scopeId = "private")))
            assertEquals(hold, dao.getAllHoldHistoryForBackupMerge().single())
            dao.insertBlockHistory(old.copy(id = 0, scopeId = "private-at-operation"))
            assertEquals(setOf(LEGACY_ACTOR_BOT_ID, "private-at-operation"), dao.getAllBlockHistoryForBackupMerge().map { it.scopeId }.toSet())
        } finally { room.close() }
    }

    private fun open(
        version: Int,
        onCreate: (SupportSQLiteDatabase) -> Unit = {},
        onUpgrade: (SupportSQLiteDatabase, Int, Int) -> Unit = { _, _, _ -> },
    ): SupportSQLiteDatabase {
        val callback = object : SupportSQLiteOpenHelper.Callback(version) {
            override fun onCreate(db: SupportSQLiteDatabase) = onCreate(db)
            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) =
                onUpgrade(db, oldVersion, newVersion)
        }
        return FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(databaseName)
                .callback(callback)
                .build()
        ).writableDatabase
    }

    private fun createVersion8Schema(db: SupportSQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE checked_posts (
                gallType TEXT NOT NULL, gallId TEXT NOT NULL, postNum TEXT NOT NULL,
                commentCount INTEGER NOT NULL, checkTime INTEGER NOT NULL,
                title TEXT, author TEXT, isBlocked INTEGER NOT NULL,
                blockReason TEXT, snapshotPath TEXT, creationDate TEXT,
                PRIMARY KEY(gallType, gallId, postNum))""".trimIndent()
        )
        db.execSQL(
            """CREATE TABLE block_history (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                gallType TEXT NOT NULL, gallId TEXT NOT NULL, postNum TEXT NOT NULL,
                targetType TEXT NOT NULL, targetNo TEXT NOT NULL,
                targetAuthor TEXT NOT NULL, targetContent TEXT NOT NULL,
                blockReason TEXT NOT NULL, blockTime INTEGER NOT NULL,
                snapshotPath TEXT, creationDate TEXT)""".trimIndent()
        )
        db.execSQL(AppDatabase.CREATE_HOLD_HISTORY_SQL.trimIndent())
        db.execSQL(AppDatabase.CREATE_HOLD_HISTORY_INDEX_SQL)
    }
}

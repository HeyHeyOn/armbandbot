package com.heyheyon.armbandbot

import android.content.Context
import androidx.room.*

@Entity(
    tableName = "checked_posts",
    primaryKeys = ["gallType", "gallId", "postNum"]
)
data class CheckedPost(
    val gallType: String,
    val gallId: String,
    val postNum: String,
    val commentCount: Int,
    val checkTime: Long = System.currentTimeMillis(),
    val title: String? = null,
    val author: String? = null,
    val isBlocked: Boolean = false,
    val blockReason: String? = null,
    val snapshotPath: String? = null,
    val creationDate: String? = null
)

@Entity(tableName = "block_history")
data class BlockHistory(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val gallType: String,
    val gallId: String,
    val postNum: String,
    val targetType: String,
    val targetAuthor: String,
    val targetContent: String,
    val blockReason: String,
    val blockTime: Long = System.currentTimeMillis(),
    val snapshotPath: String? = null,
    val creationDate: String? = null
)

@Dao
interface PostDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertOrUpdate(post: CheckedPost)

    @Query("SELECT * FROM checked_posts WHERE gallType = :gallType AND gallId = :gallId AND postNum = :postNum LIMIT 1")
    fun getPost(gallType: String, gallId: String, postNum: String): CheckedPost?

    @Query("SELECT COUNT(*) FROM checked_posts")
    fun getPostCount(): Int

    @Query("DELETE FROM checked_posts")
    fun clearAllPosts()

    @Query("SELECT DISTINCT gallId FROM checked_posts")
    fun getGalleries(): List<String>

    @Query("""
        SELECT * FROM checked_posts
        WHERE (:gallId = 'ALL' OR gallId = :gallId)
          AND (:query = '' OR title LIKE '%' || :query || '%' OR author LIKE '%' || :query || '%')
        ORDER BY checkTime DESC
        LIMIT :limit OFFSET :offset
    """)
    fun getPostsCheckDesc(gallId: String, query: String, limit: Int, offset: Int): List<CheckedPost>

    @Query("""
        SELECT * FROM checked_posts
        WHERE (:gallId = 'ALL' OR gallId = :gallId)
          AND (:query = '' OR title LIKE '%' || :query || '%' OR author LIKE '%' || :query || '%')
        ORDER BY checkTime ASC
        LIMIT :limit OFFSET :offset
    """)
    fun getPostsCheckAsc(gallId: String, query: String, limit: Int, offset: Int): List<CheckedPost>

    @Query("""
        SELECT * FROM checked_posts
        WHERE (:gallId = 'ALL' OR gallId = :gallId)
          AND (:query = '' OR title LIKE '%' || :query || '%' OR author LIKE '%' || :query || '%')
        ORDER BY creationDate DESC
        LIMIT :limit OFFSET :offset
    """)
    fun getPostsCreateDesc(gallId: String, query: String, limit: Int, offset: Int): List<CheckedPost>

    @Query("""
        SELECT * FROM checked_posts
        WHERE (:gallId = 'ALL' OR gallId = :gallId)
          AND (:query = '' OR title LIKE '%' || :query || '%' OR author LIKE '%' || :query || '%')
        ORDER BY creationDate ASC
        LIMIT :limit OFFSET :offset
    """)
    fun getPostsCreateAsc(gallId: String, query: String, limit: Int, offset: Int): List<CheckedPost>

    @Query("SELECT * FROM checked_posts ORDER BY checkTime DESC LIMIT 100")
    fun getRecentPosts(): List<CheckedPost>

    @Query("UPDATE checked_posts SET snapshotPath = :path WHERE gallType = :gallType AND gallId = :gallId AND postNum = :postNum")
    fun updateSnapshotPath(gallType: String, gallId: String, postNum: String, path: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertBlockHistory(history: BlockHistory)

    @Query("DELETE FROM block_history")
    fun clearAllBlockHistory()

    @Query("""
        SELECT * FROM block_history
        WHERE (:type = 'ALL' OR targetType = :type)
          AND (:query = '' OR targetContent LIKE '%' || :query || '%' OR targetAuthor LIKE '%' || :query || '%')
        ORDER BY blockTime DESC
        LIMIT :limit OFFSET :offset
    """)
    fun getBlockHistoryCheckDesc(type: String, query: String, limit: Int, offset: Int): List<BlockHistory>

    @Query("""
        SELECT * FROM block_history
        WHERE (:type = 'ALL' OR targetType = :type)
          AND (:query = '' OR targetContent LIKE '%' || :query || '%' OR targetAuthor LIKE '%' || :query || '%')
        ORDER BY blockTime ASC
        LIMIT :limit OFFSET :offset
    """)
    fun getBlockHistoryCheckAsc(type: String, query: String, limit: Int, offset: Int): List<BlockHistory>

    @Query("""
        SELECT * FROM block_history
        WHERE (:type = 'ALL' OR targetType = :type)
          AND (:query = '' OR targetContent LIKE '%' || :query || '%' OR targetAuthor LIKE '%' || :query || '%')
        ORDER BY creationDate DESC
        LIMIT :limit OFFSET :offset
    """)
    fun getBlockHistoryCreateDesc(type: String, query: String, limit: Int, offset: Int): List<BlockHistory>

    @Query("""
        SELECT * FROM block_history
        WHERE (:type = 'ALL' OR targetType = :type)
          AND (:query = '' OR targetContent LIKE '%' || :query || '%' OR targetAuthor LIKE '%' || :query || '%')
        ORDER BY creationDate ASC
        LIMIT :limit OFFSET :offset
    """)
    fun getBlockHistoryCreateAsc(type: String, query: String, limit: Int, offset: Int): List<BlockHistory>

    @Query("""
        SELECT * FROM checked_posts
        WHERE (:gallId = 'ALL' OR gallId = :gallId)
        ORDER BY checkTime DESC
        LIMIT :limit OFFSET :offset
    """)
    fun getPostsDesc(gallId: String, limit: Int, offset: Int): List<CheckedPost>

    @Query("""
        SELECT * FROM checked_posts
        WHERE (:gallId = 'ALL' OR gallId = :gallId)
        ORDER BY checkTime ASC
        LIMIT :limit OFFSET :offset
    """)
    fun getPostsAsc(gallId: String, limit: Int, offset: Int): List<CheckedPost>

    @Query("""
        SELECT * FROM block_history
        WHERE (:type = 'ALL' OR targetType = :type)
        ORDER BY blockTime DESC
        LIMIT :limit OFFSET :offset
    """)
    fun getBlockHistoryDesc(type: String, limit: Int, offset: Int): List<BlockHistory>

    @Query("""
        SELECT * FROM block_history
        WHERE (:type = 'ALL' OR targetType = :type)
        ORDER BY blockTime ASC
        LIMIT :limit OFFSET :offset
    """)
    fun getBlockHistoryAsc(type: String, limit: Int, offset: Int): List<BlockHistory>
}

@Database(entities = [CheckedPost::class, BlockHistory::class], version = 6, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun postDao(): PostDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "bot_database"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
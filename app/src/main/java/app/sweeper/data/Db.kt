package app.sweeper.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

enum class PostType(val label: String, val plural: String) {
    POST("Post", "Posts"),
    REPLY("Reply", "Replies"),
    REPOST("Repost", "Reposts"),
    QUOTE("Quote", "Quotes"),
    LIKE("Like", "Likes"),
}

/**
 * PULLED   found, waiting for review / queueing
 * QUEUED   will be deleted
 * DELETING request in flight (reverted to QUEUED if the app dies)
 * SENT     X said OK, not yet confirmed by an independent read
 * VERIFIED confirmed gone by a real check  <- the only "deleted" state
 * FAILED   gave up, see lastError
 */
enum class ItemStatus { PULLED, QUEUED, DELETING, SENT, VERIFIED, FAILED }

@Entity(
    tableName = "posts",
    primaryKeys = ["id", "type"],
    indices = [Index("status"), Index("type"), Index("createdAt")],
)
data class PostEntity(
    val id: String,
    val type: PostType,
    /** For reposts: id of the original post (used for the un-repost call and for verification). */
    val sourceId: String? = null,
    val text: String = "",
    val createdAt: Long = 0,
    val authorId: String? = null,
    val authorHandle: String? = null,
    val replyToHandle: String? = null,
    val likeCount: Int = 0,
    val repostCount: Int = 0,
    val replyCount: Int = 0,
    val quoteCount: Int = 0,
    val viewCount: Long = 0,
    val hasMedia: Boolean = false,
    val hasLinks: Boolean = false,
    val hasHashtags: Boolean = false,
    val hasMentions: Boolean = false,
    val selfReply: Boolean = false,
    val lang: String? = null,
    val pinned: Boolean = false,
    val fromArchive: Boolean = false,
    /** Result of the filter engine: true = would be deleted. */
    val matches: Boolean = false,
    /** 0 = follow filters, 1 = always keep, 2 = always delete. */
    val manual: Int = 0,
    val status: ItemStatus = ItemStatus.PULLED,
    val attempts: Int = 0,
    val lastError: String? = null,
    val pulledAt: Long = 0,
    val sentAt: Long? = null,
    val verifiedAt: Long? = null,
    val alreadyGone: Boolean = false,
)

@Entity(tableName = "logs")
data class LogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ts: Long,
    /** 0 debug, 1 info, 2 warn, 3 error */
    val level: Int,
    val tag: String,
    val msg: String,
)

data class CountRow(
    val status: ItemStatus,
    val type: PostType,
    @ColumnInfo(name = "n") val n: Int,
)

private const val WILL_DELETE = "((matches = 1 AND manual != 1) OR manual = 2)"

@Dao
interface PostDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(items: List<PostEntity>): List<Long>

    @Query(
        "UPDATE posts SET likeCount=:likes, repostCount=:reposts, replyCount=:replies, quoteCount=:quotes, viewCount=:views " +
            "WHERE id=:id AND type=:type AND status='PULLED'"
    )
    suspend fun refreshMetrics(id: String, type: PostType, likes: Int, reposts: Int, replies: Int, quotes: Int, views: Long)

    @Query("SELECT * FROM posts WHERE status='QUEUED' ORDER BY createdAt ASC LIMIT 1")
    suspend fun nextOldest(): PostEntity?

    @Query("SELECT * FROM posts WHERE status='QUEUED' ORDER BY createdAt DESC LIMIT 1")
    suspend fun nextNewest(): PostEntity?

    @Query("SELECT * FROM posts WHERE status='QUEUED' ORDER BY RANDOM() LIMIT 1")
    suspend fun nextRandom(): PostEntity?

    @Query("SELECT * FROM posts WHERE id=:id AND type=:type")
    suspend fun get(id: String, type: PostType): PostEntity?

    @Query("UPDATE posts SET status='DELETING', attempts=attempts+1 WHERE id=:id AND type=:type")
    suspend fun markDeleting(id: String, type: PostType)

    @Query("UPDATE posts SET status='SENT', sentAt=:at, lastError=NULL WHERE id=:id AND type=:type")
    suspend fun markSent(id: String, type: PostType, at: Long)

    @Query("UPDATE posts SET status='VERIFIED', verifiedAt=:at, alreadyGone=:gone, lastError=NULL WHERE id=:id AND type=:type")
    suspend fun markVerified(id: String, type: PostType, at: Long, gone: Boolean)

    @Query("UPDATE posts SET status='FAILED', lastError=:error WHERE id=:id AND type=:type")
    suspend fun markFailed(id: String, type: PostType, error: String)

    @Query("UPDATE posts SET status='QUEUED', lastError=:error WHERE id=:id AND type=:type")
    suspend fun requeue(id: String, type: PostType, error: String?)

    @Query("UPDATE posts SET status='QUEUED' WHERE status='DELETING'")
    suspend fun recoverDeleting()

    @Query("UPDATE posts SET status='PULLED' WHERE id=:id AND type=:type AND status='QUEUED'")
    suspend fun unqueueOne(id: String, type: PostType)

    @Query("SELECT * FROM posts WHERE status='SENT' ORDER BY sentAt ASC")
    suspend fun sentItems(): List<PostEntity>

    @Query("SELECT status, type, COUNT(*) AS n FROM posts GROUP BY status, type")
    fun observeCounts(): Flow<List<CountRow>>

    @Query("SELECT COUNT(*) FROM posts WHERE status IN ('QUEUED','DELETING','SENT')")
    suspend fun remaining(): Int

    @Query("SELECT COUNT(*) FROM posts WHERE status='PULLED' AND $WILL_DELETE")
    fun observeWillDelete(): Flow<Int>

    @Query("SELECT COUNT(*) FROM posts WHERE status='PULLED' AND $WILL_DELETE")
    suspend fun willDelete(): Int

    @Query("SELECT COUNT(*) FROM posts WHERE status='VERIFIED' AND verifiedAt >= :since")
    suspend fun verifiedSince(since: Long): Int

    @Query("SELECT COUNT(*) FROM posts")
    suspend fun total(): Int

    @Query("UPDATE posts SET status='QUEUED', attempts=0, lastError=NULL WHERE status IN ('PULLED','FAILED') AND $WILL_DELETE")
    suspend fun queueMatching(): Int

    @Query("UPDATE posts SET status='PULLED' WHERE status='QUEUED'")
    suspend fun unqueueAll(): Int

    @Query("UPDATE posts SET manual=:manual WHERE id=:id AND type=:type")
    suspend fun setManual(id: String, type: PostType, manual: Int)

    @Query("UPDATE posts SET status='QUEUED', attempts=0, lastError=NULL WHERE id=:id AND type=:type AND status IN ('PULLED','FAILED')")
    suspend fun queueOne(id: String, type: PostType)

    @Query("UPDATE posts SET status='QUEUED', attempts=0, lastError=NULL WHERE status='FAILED'")
    suspend fun retryFailed(): Int

    @Query(
        "SELECT * FROM posts WHERE status='PULLED' AND (id > :lastId OR (id = :lastId AND type > :lastType)) " +
            "ORDER BY id, type LIMIT :n"
    )
    suspend fun pulledAfter(lastId: String, lastType: PostType, n: Int): List<PostEntity>

    @Query("UPDATE posts SET matches=:m WHERE type=:type AND id IN (:ids) AND status='PULLED'")
    suspend fun setMatches(type: PostType, ids: List<String>, m: Boolean)

    @Query(
        "SELECT createdAt FROM posts WHERE type=:type AND status != 'VERIFIED' ORDER BY createdAt DESC LIMIT 1 OFFSET :offset"
    )
    suspend fun nthNewest(type: PostType, offset: Int): Long?

    @Query(
        "SELECT * FROM posts WHERE (:type='ALL' OR type=:type) AND (" +
            ":view='ALL' " +
            "OR (:view='DELETE' AND status='PULLED' AND $WILL_DELETE) " +
            "OR (:view='KEEP' AND status='PULLED' AND NOT $WILL_DELETE) " +
            "OR (:view='QUEUED' AND status IN ('QUEUED','DELETING','SENT')) " +
            "OR (:view='DONE' AND status='VERIFIED') " +
            "OR (:view='FAILED' AND status='FAILED')) " +
            "AND (:q='' OR text LIKE '%' || :q || '%' OR authorHandle LIKE '%' || :q || '%') " +
            "ORDER BY createdAt DESC LIMIT :limit"
    )
    fun observe(type: String, view: String, q: String, limit: Int): Flow<List<PostEntity>>

    @Query("SELECT * FROM posts WHERE status='VERIFIED' ORDER BY verifiedAt DESC LIMIT :n")
    suspend fun recentVerified(n: Int): List<PostEntity>

    @Query("SELECT * FROM posts WHERE status='VERIFIED' ORDER BY verifiedAt DESC")
    suspend fun allVerified(): List<PostEntity>

    @Query("DELETE FROM posts WHERE status IN ('PULLED','FAILED')")
    suspend fun clearUnfinished(): Int

    @Query("DELETE FROM posts")
    suspend fun clearAll()
}

@Dao
interface LogDao {
    @Insert
    suspend fun insert(e: LogEntity)

    @Query("SELECT * FROM logs ORDER BY id DESC LIMIT :n")
    fun observe(n: Int): Flow<List<LogEntity>>

    @Query("SELECT * FROM logs ORDER BY id DESC LIMIT :n")
    suspend fun latest(n: Int): List<LogEntity>

    @Query("DELETE FROM logs WHERE id <= (SELECT MAX(id) FROM logs) - :keep")
    suspend fun trim(keep: Int)

    @Query("DELETE FROM logs")
    suspend fun clear()
}

@Database(entities = [PostEntity::class, LogEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun posts(): PostDao
    abstract fun logs(): LogDao
}

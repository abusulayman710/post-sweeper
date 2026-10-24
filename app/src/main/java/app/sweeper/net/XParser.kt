package app.sweeper.net

import app.sweeper.data.PostEntity
import app.sweeper.data.PostType
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.text.SimpleDateFormat
import java.util.Locale

internal operator fun JsonElement?.get(k: String): JsonElement? =
    (this as? JsonObject)?.get(k)?.takeIf { it !is JsonNull }

internal val JsonElement?.asStr: String?
    get() = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

internal val JsonElement?.asInt: Int get() = asStr?.toDoubleOrNull()?.toInt() ?: 0
internal val JsonElement?.asLong: Long get() = asStr?.toDoubleOrNull()?.toLong() ?: 0L
internal val JsonElement?.asBool: Boolean?
    get() = when (asStr) {
        "true" -> true
        "false" -> false
        else -> null
    }
internal val JsonElement?.asArr: JsonArray? get() = this as? JsonArray

data class ParsedTweet(
    val id: String,
    val sourceId: String?,
    val type: PostType,
    val text: String,
    val createdAt: Long,
    /** Who posted the outer tweet (for a repost: you). Used to make sure we only ever touch our own posts. */
    val ownerId: String?,
    /** Author of the content shown (for reposts and likes: the original author). */
    val authorId: String?,
    val authorHandle: String?,
    val replyToHandle: String?,
    val likes: Int,
    val reposts: Int,
    val replies: Int,
    val quotes: Int,
    val views: Long,
    val media: Boolean,
    val links: Boolean,
    val hashtags: Boolean,
    val mentions: Boolean,
    val selfReply: Boolean,
    val lang: String?,
    val pinned: Boolean,
)

data class TimelinePage(val tweets: List<ParsedTweet>, val cursor: String?, val entryCount: Int)

sealed interface TweetState {
    data object Absent : TweetState
    data object Unavailable : TweetState
    data class Present(val favorited: Boolean?, val retweeted: Boolean?) : TweetState
}

object XParser {
    private const val TWITTER_EPOCH = 1288834974657L

    fun snowflakeTime(id: String): Long = (id.toLongOrNull() ?: 0L).let { if (it == 0L) 0L else (it shr 22) + TWITTER_EPOCH }

    private fun parseDate(s: String?): Long? {
        if (s.isNullOrBlank()) return null
        return runCatching { SimpleDateFormat("EEE MMM dd HH:mm:ss Z yyyy", Locale.ENGLISH).parse(s)?.time }.getOrNull()
    }

    private fun unwrap(r: JsonElement?): JsonElement? {
        var x = r
        val type = x?.get("__typename").asStr
        if (type == "TweetWithVisibilityResults" || type == "TweetPreviewDisplay") x = x?.get("tweet")
        return x
    }

    fun parseTweet(raw: JsonElement?, pinned: Boolean = false): ParsedTweet? {
        val r = unwrap(raw) ?: return null
        val typeName = r["__typename"].asStr
        if (typeName in setOf("TweetTombstone", "TweetUnavailable")) return null
        val id = r["rest_id"].asStr ?: r["id_str"].asStr ?: return null
        val legacy = r["legacy"]
        val details = r["details"]
        val counts = r["counts"]
        if (legacy == null && details == null && counts == null && r["full_text"] == null) return null

        val ownerId = legacy?.get("user_id_str").asStr
            ?: r["core"]["user_results"]["result"]["rest_id"].asStr
            ?: r["core"]["user_results"]["rest_id"].asStr
            ?: r["user"]["id_str"].asStr

        val rtRaw = legacy?.get("retweeted_status_result")?.get("result")
            ?: r["retweeted_status_results"]?.get("result")
            ?: r["retweeted_status"]
        val rt = unwrap(rtRaw)
        val isRepost = rtRaw != null
        val base = rt ?: r
        val bl = base["legacy"] ?: legacy ?: base
        val bd = base["details"] ?: details
        val bc = base["counts"] ?: counts

        val text = base["note_tweet"]["note_tweet_results"]["result"]["text"].asStr
            ?: bd?.get("full_text").asStr
            ?: bl?.get("full_text").asStr
            ?: legacy?.get("full_text").asStr
            ?: base["full_text"].asStr
            ?: base["text"].asStr
            ?: ""
        val user = base["core"]["user_results"]["result"]
            ?: base["core"]["user_results"]
            ?: base["user"]
        val handle = user?.get("core")?.get("screen_name").asStr
            ?: user?.get("legacy")?.get("screen_name").asStr
            ?: user?.get("screen_name").asStr
        val authorId = user?.get("rest_id").asStr
            ?: bl?.get("user_id_str").asStr
            ?: user?.get("id_str").asStr

        val replyId = legacy?.get("in_reply_to_status_id_str").asStr
            ?: r["reply_to_results"]?.get("rest_id").asStr
            ?: r["reply_to_results"]?.get("result")?.get("rest_id").asStr
            ?: r["in_reply_to_status_id_str"].asStr
        val type = when {
            isRepost -> PostType.REPOST
            replyId != null -> PostType.REPLY
            legacy?.get("is_quote_status").asBool == true || r["quoted_tweet_results"] != null || r["is_quote_status"].asBool == true -> PostType.QUOTE
            else -> PostType.POST
        }
        val sourceId = if (isRepost) (rt?.get("rest_id").asStr ?: rtRaw?.get("rest_id").asStr ?: legacy?.get("retweeted_status_id_str").asStr ?: r["retweeted_status"]?.get("id_str").asStr) else null

        val detailsMs = bd?.get("created_at_ms").asLong
        val created = if (detailsMs > 0L) detailsMs else (parseDate(bl?.get("created_at").asStr ?: legacy?.get("created_at").asStr ?: r["created_at"].asStr) ?: snowflakeTime(id))
        val ent = bl?.get("entities") ?: base["entities"]
        val ext = bl?.get("extended_entities") ?: base["extended_entities"]
        val media = (ext?.get("media").asArr?.isNotEmpty() == true) || (ent?.get("media").asArr?.isNotEmpty() == true) || (base["media_entities2"].asArr?.isNotEmpty() == true)

        val likeCount = bc?.get("favorite_count").asInt.takeIf { it > 0 } ?: bl?.get("favorite_count").asInt
        val repostCount = bc?.get("retweet_count").asInt.takeIf { it > 0 } ?: bl?.get("retweet_count").asInt
        val replyCount = bc?.get("reply_count").asInt.takeIf { it > 0 } ?: bl?.get("reply_count").asInt
        val quoteCount = bc?.get("quote_count").asInt.takeIf { it > 0 } ?: bl?.get("quote_count").asInt

        return ParsedTweet(
            id = id,
            sourceId = sourceId,
            type = type,
            text = if (isRepost) "RT @${handle ?: "?"}: $text" else text,
            createdAt = created,
            ownerId = ownerId,
            authorId = authorId,
            authorHandle = handle,
            replyToHandle = legacy?.get("in_reply_to_screen_name").asStr ?: r["reply_to_user_results"]?.get("result")?.get("core")?.get("screen_name").asStr ?: r["in_reply_to_screen_name"].asStr,
            likes = likeCount,
            reposts = repostCount,
            replies = replyCount,
            quotes = quoteCount,
            views = base["views"]["count"].asLong,
            media = media,
            links = (ent?.get("urls").asArr?.isNotEmpty() == true) || (base["url_entities"].asArr?.isNotEmpty() == true),
            hashtags = (ent?.get("hashtags").asArr?.isNotEmpty() == true) || (bd?.get("hashtag_entities").asArr?.isNotEmpty() == true),
            mentions = (ent?.get("user_mentions").asArr?.isNotEmpty() == true) || (base["mention_entities"].asArr?.isNotEmpty() == true),
            selfReply = replyId != null && (legacy?.get("in_reply_to_user_id_str").asStr == ownerId || r["in_reply_to_user_id_str"].asStr == ownerId),
            lang = bl?.get("lang").asStr ?: r["lang"].asStr,
            pinned = pinned,
        )
    }

    private fun tweetFromItem(item: JsonElement?, pinned: Boolean): ParsedTweet? {
        val content = item?.get("itemContent") ?: item?.get("content") ?: item ?: return null
        if (content["promotedMetadata"] != null || content["promoted_metadata"] != null) return null
        val res = content["tweet_results"]?.get("result")
            ?: content["tweet"]?.get("result")
            ?: content["tweet"]
        return parseTweet(res, pinned)
    }

    private fun findInstructions(node: JsonElement?, depth: Int = 0): JsonArray? {
        if (node == null || depth > 10) return null
        if (node is JsonObject) {
            node["instructions"].asArr?.let { return it }
            for ((_, v) in node) {
                findInstructions(v, depth + 1)?.let { return it }
            }
        } else if (node is JsonArray) {
            for (item in node) {
                findInstructions(item, depth + 1)?.let { return it }
            }
        }
        return null
    }

    fun parseTimeline(root: JsonElement): TimelinePage {
        val result = root["data"]["user"]["result"]
            ?: root["data"]["user_result_by_screen_name"]["result"]
            ?: root["data"]["user_result_by_rest_id"]["result"]
        val tl = result?.get("timeline_v2")
            ?: result?.get("timeline")
            ?: result?.get("profile_with_replies_timeline_v2")
            ?: result?.get("profile_user_replies_timeline")
            ?: result?.get("profile_user_originals_timeline")
            ?: result?.get("favorites_by_time_timeline_v2")
        val instructions = (tl?.get("timeline")?.get("instructions") ?: tl?.get("instructions")).asArr
            ?: findInstructions(root["data"])
            ?: JsonArray(emptyList())
        val out = ArrayList<ParsedTweet>()
        var cursor: String? = null
        var entries = 0

        fun handleEntry(entry: JsonElement?, pinned: Boolean) {
            val content = entry?.get("content") ?: return
            entries++
            val entryId = entry["entryId"].asStr ?: entry["entry_id"].asStr.orEmpty()
            val cursorType = content["cursorType"].asStr ?: content["cursor_type"].asStr ?: content["content"]?.get("cursor_type").asStr
            val cursorVal = content["value"].asStr ?: content["content"]?.get("value").asStr
            if (cursorType == "Bottom" || entryId.startsWith("cursor-bottom")) {
                if (cursorVal != null) cursor = cursorVal
                return
            }
            if (cursorType != null) return
            content["items"].asArr?.forEach { m ->
                val sub = m["item"] ?: m
                tweetFromItem(sub, pinned)?.let(out::add)
            }
            tweetFromItem(content, pinned)?.let(out::add)
        }

        for (ins in instructions) {
            val insType = ins["type"].asStr ?: ins["__typename"].asStr
            when (insType) {
                "TimelineAddEntries" -> ins["entries"].asArr?.forEach { handleEntry(it, false) }
                "TimelinePinEntry" -> handleEntry(ins["entry"], true)
            }
        }
        return TimelinePage(out, cursor, entries)
    }

    fun parseTweetState(root: JsonElement): TweetState {
        val res = root["data"]["tweetResult"]["result"]
            ?: root["data"]["tweet_result_by_rest_id"]["result"]
            ?: root["data"]["tweet"]
            ?: return TweetState.Absent
        val r = unwrap(res) ?: return TweetState.Absent
        val typename = r["__typename"].asStr.orEmpty()
        return when (typename) {
            "TweetTombstone", "TweetUnavailable" -> TweetState.Unavailable
            else -> {
                val id = r["rest_id"].asStr ?: r["id_str"].asStr
                if (id == null) TweetState.Absent
                else {
                    val fav = r["perspective"]?.get("favorited").asBool
                        ?: r["legacy"]?.get("favorited").asBool
                        ?: r["favorited"].asBool
                    val ret = r["perspective"]?.get("retweeted").asBool
                        ?: r["legacy"]?.get("retweeted").asBool
                        ?: r["retweeted"].asBool
                    TweetState.Present(fav, ret)
                }
            }
        }
    }

    fun toEntity(t: ParsedTweet, now: Long, fromArchive: Boolean = false) = PostEntity(
        id = t.id,
        type = t.type,
        sourceId = t.sourceId,
        text = t.text,
        createdAt = t.createdAt,
        authorId = t.authorId,
        authorHandle = t.authorHandle,
        replyToHandle = t.replyToHandle,
        likeCount = t.likes,
        repostCount = t.reposts,
        replyCount = t.replies,
        quoteCount = t.quotes,
        viewCount = t.views,
        hasMedia = t.media,
        hasLinks = t.links,
        hasHashtags = t.hashtags,
        hasMentions = t.mentions,
        selfReply = t.selfReply,
        lang = t.lang,
        pinned = t.pinned,
        fromArchive = fromArchive,
        pulledAt = now,
    )
}

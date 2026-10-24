package app.sweeper.engine

import android.content.Context
import android.net.Uri
import app.sweeper.Graph
import app.sweeper.data.PostEntity
import app.sweeper.data.PostType
import app.sweeper.net.XParser
import app.sweeper.net.asArr
import app.sweeper.net.asBool
import app.sweeper.net.asInt
import app.sweeper.net.asStr
import app.sweeper.net.get
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeToSequence
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.io.SequenceInputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.zip.ZipInputStream

/**
 * X's timeline only reaches back ~3,200 posts. The official data archive (Settings > Your account > Download an
 * archive of your data) has all of them. Pick the .zip, or tweets.js / like.js from it, and they join the queue.
 * Ids that no longer exist are weeded out by a cheap read before any delete budget is spent.
 */
object ArchiveImporter {
    data class Result(val posts: Int, val likes: Int, val skipped: Int)

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun import(ctx: Context, uri: Uri, ownerId: String, ownerHandle: String): Result {
        var posts = 0
        var likes = 0
        var skipped = 0
        val now = System.currentTimeMillis()
        val dao = Graph.db.posts()

        suspend fun consume(name: String, stream: InputStream) {
            val lower = name.substringAfterLast('/').lowercase()
            val isTweets = Regex("""tweets?(-part\d+)?\.js""").matches(lower)
            val isLikes = Regex("""likes?(-part\d+)?\.js""").matches(lower)
            if (!isTweets && !isLikes) return
            val buf = ArrayList<PostEntity>(500)
            suspend fun flush() {
                if (buf.isNotEmpty()) {
                    dao.insertIgnore(buf.toList())
                    buf.clear()
                }
            }
            val buffered = BufferedInputStream(stream, 64 * 1024)
            // Skip the "window.YTD.tweets.part0 = " prefix, then put the opening bracket back.
            var c = buffered.read()
            while (c != -1 && c != '['.code) c = buffered.read()
            if (c == -1) return
            val input = SequenceInputStream(ByteArrayInputStream("[".toByteArray()), buffered)
            val seq: Sequence<JsonElement> = json.decodeToSequence(input, JsonElement.serializer())
            for (el in seq) {
                val e = if (isTweets) toEntity(el["tweet"] ?: el, ownerId, ownerHandle, now) else toLike(el["like"] ?: el, now)
                if (e == null) { skipped++; continue }
                buf.add(e)
                if (e.type == PostType.LIKE) likes++ else posts++
                if (buf.size >= 500) flush()
            }
            flush()
        }

        val name = uri.lastPathSegment.orEmpty().lowercase()
        ctx.contentResolver.openInputStream(uri)?.use { raw ->
            if (name.endsWith(".zip")) {
                val zis = ZipInputStream(raw)
                var entry = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val guard = object : FilterInputStream(zis) { override fun close() {} }
                        consume(entry.name, guard)
                    }
                    entry = zis.nextEntry
                }
            } else {
                consume(name.ifBlank { "tweets.js" }, raw)
            }
        }
        FilterEngine.recompute()
        return Result(posts, likes, skipped)
    }

    private fun date(s: String?): Long? =
        runCatching { SimpleDateFormat("EEE MMM dd HH:mm:ss Z yyyy", Locale.ENGLISH).parse(s ?: "")?.time }.getOrNull()

    private fun toEntity(t: JsonElement, ownerId: String, ownerHandle: String, now: Long): PostEntity? {
        val id = t["id_str"].asStr ?: t["id"].asStr ?: return null
        val text = t["full_text"].asStr ?: t["text"].asStr ?: ""
        val replyTo = t["in_reply_to_status_id_str"].asStr
        val isRt = text.startsWith("RT @")
        val type = when {
            isRt -> PostType.REPOST
            replyTo != null -> PostType.REPLY
            t["is_quote_status"].asBool == true -> PostType.QUOTE
            else -> PostType.POST
        }
        val ent = t["entities"]
        val media = (t["extended_entities"]["media"].asArr?.isNotEmpty() == true) || (ent["media"].asArr?.isNotEmpty() == true)
        val replyToUser = t["in_reply_to_user_id_str"].asStr
        return PostEntity(
            id = id, type = type, text = text,
            createdAt = date(t["created_at"].asStr) ?: XParser.snowflakeTime(id),
            authorId = ownerId, authorHandle = if (isRt) Regex("""^RT @(\w+)""").find(text)?.groupValues?.get(1) else ownerHandle,
            replyToHandle = t["in_reply_to_screen_name"].asStr,
            likeCount = t["favorite_count"].asInt, repostCount = t["retweet_count"].asInt,
            hasMedia = media, hasLinks = ent["urls"].asArr?.isNotEmpty() == true,
            hasHashtags = ent["hashtags"].asArr?.isNotEmpty() == true,
            hasMentions = ent["user_mentions"].asArr?.isNotEmpty() == true,
            selfReply = replyTo != null && replyToUser == ownerId,
            lang = t["lang"].asStr, fromArchive = true, pulledAt = now,
        )
    }

    private fun toLike(l: JsonElement, now: Long): PostEntity? {
        val id = l["tweetId"].asStr ?: return null
        return PostEntity(
            id = id, type = PostType.LIKE, text = l["fullText"].asStr ?: "",
            createdAt = XParser.snowflakeTime(id), fromArchive = true, pulledAt = now,
        )
    }
}

package app.sweeper

import app.sweeper.data.DateMode
import app.sweeper.data.FilterConfig
import app.sweeper.data.PostEntity
import app.sweeper.data.PostType
import app.sweeper.data.ScheduleConfig
import app.sweeper.data.ScheduleMode
import app.sweeper.data.TriState
import app.sweeper.data.TypeRule
import app.sweeper.engine.CompiledFilter
import app.sweeper.engine.ScheduleGate
import app.sweeper.net.TweetState
import app.sweeper.net.XParser
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class FilterAndParserTest {
    private val now = ZonedDateTime.of(2026, 6, 15, 12, 0, 0, 0, ZoneId.of("UTC")).toInstant().toEpochMilli()
    private val day = 86_400_000L

    private fun post(
        id: String = "100000001", type: PostType = PostType.POST, ageDays: Int = 60, text: String = "hello world",
        likes: Int = 0, pinned: Boolean = false, media: Boolean = false, manual: Int = 0, author: String? = null,
    ) = PostEntity(id = id, type = type, text = text, createdAt = now - ageDays * day, likeCount = likes, pinned = pinned, hasMedia = media, manual = manual, authorHandle = author)

    private fun matches(p: PostEntity, f: FilterConfig = FilterConfig()) = CompiledFilter(f, now).matches(p)

    @Test fun olderThanDaysHonoured() {
        val f = FilterConfig(dateMode = DateMode.OLDER_THAN, olderThanDays = 30)
        assertTrue(matches(post(ageDays = 31), f))
        assertFalse(matches(post(ageDays = 5), f))
    }

    @Test fun disabledTypesNeverMatchAndLikesOffByDefault() {
        assertFalse(matches(post(type = PostType.LIKE)))
        assertTrue(matches(post(type = PostType.LIKE), FilterConfig(likes = TypeRule(true))))
        assertFalse(matches(post(type = PostType.REPLY), FilterConfig(replies = TypeRule(false))))
    }

    @Test fun perTypeAgeStacksWithGlobalAge() {
        val f = FilterConfig(dateMode = DateMode.ALL, posts = TypeRule(true, olderThanDays = 365))
        assertFalse(matches(post(ageDays = 100), f))
        assertTrue(matches(post(ageDays = 400), f))
    }

    @Test fun keywordsIncludeExcludeRegexAndWholeWord() {
        val inc = FilterConfig(dateMode = DateMode.ALL, includeWords = "crypto, nft")
        assertTrue(matches(post(text = "Big NFT drop"), inc))
        assertFalse(matches(post(text = "lunch"), inc))
        val exc = FilterConfig(dateMode = DateMode.ALL, excludeWords = "keepme")
        assertFalse(matches(post(text = "please KEEPME forever"), exc))
        val rx = FilterConfig(dateMode = DateMode.ALL, includeWords = "^gm\\b", useRegex = true)
        assertTrue(matches(post(text = "gm friends"), rx))
        assertFalse(matches(post(text = "say gm"), rx))
        val ww = FilterConfig(dateMode = DateMode.ALL, includeWords = "cat", wholeWord = true)
        assertTrue(matches(post(text = "my cat sat"), ww))
        assertFalse(matches(post(text = "concatenate"), ww))
    }

    @Test fun popularPostsProtected() {
        val f = FilterConfig(dateMode = DateMode.ALL, keepLikes = 100)
        assertFalse(matches(post(likes = 100), f))
        assertTrue(matches(post(likes = 99), f))
    }

    @Test fun pinnedKeepIdsAndManualOverrides() {
        val f = FilterConfig(dateMode = DateMode.ALL, keepIds = "https://x.com/me/status/100000009, 100000010")
        assertFalse(matches(post(pinned = true), f))
        assertFalse(matches(post(id = "100000009"), f))
        assertFalse(matches(post(id = "100000010"), f))
        assertFalse(matches(post(manual = 1), f))
        assertTrue(matches(post(manual = 2, pinned = true), f))
    }

    @Test fun triStateContentRules() {
        val f = FilterConfig(dateMode = DateMode.ALL, hasMedia = TriState.YES)
        assertTrue(matches(post(media = true), f))
        assertFalse(matches(post(media = false), f))
    }

    @Test fun likeAuthorRules() {
        val f = FilterConfig(dateMode = DateMode.ALL, likes = TypeRule(true), authorsExclude = "@friend")
        assertFalse(matches(post(type = PostType.LIKE, author = "Friend"), f))
        assertTrue(matches(post(type = PostType.LIKE, author = "stranger"), f))
    }

    @Test fun keepNewestCutoff() {
        val cutoff = mapOf(PostType.POST to now - 10 * day)
        val cf = CompiledFilter(FilterConfig(dateMode = DateMode.ALL), now, cutoff)
        assertFalse(cf.matches(post(ageDays = 5)))
        assertTrue(cf.matches(post(ageDays = 20)))
    }

    // ------------------------------------------------------------------ parser

    private val timeline = """
    {"data":{"user":{"result":{"timeline_v2":{"timeline":{"instructions":[
      {"type":"TimelineClearCache"},
      {"type":"TimelinePinEntry","entry":{"entryId":"tweet-1","content":{"entryType":"TimelineTimelineItem","itemContent":{"itemType":"TimelineTweet","tweet_results":{"result":{"__typename":"Tweet","rest_id":"1","legacy":{"user_id_str":"42","created_at":"Wed Oct 10 20:19:24 +0000 2018","full_text":"pinned one","favorite_count":3}}}}}}},
      {"type":"TimelineAddEntries","entries":[
        {"entryId":"tweet-2","content":{"entryType":"TimelineTimelineItem","itemContent":{"itemType":"TimelineTweet","tweet_results":{"result":{"__typename":"Tweet","rest_id":"2","views":{"count":"1500"},"core":{"user_results":{"result":{"rest_id":"42","core":{"screen_name":"me"}}}},"legacy":{"user_id_str":"42","created_at":"Wed Oct 10 20:19:24 +0000 2018","full_text":"a reply","in_reply_to_status_id_str":"9","in_reply_to_screen_name":"bob","in_reply_to_user_id_str":"7","favorite_count":1,"retweet_count":2,"reply_count":3,"lang":"en","entities":{"urls":[{"url":"x"}],"hashtags":[],"user_mentions":[{"x":1}]}}}}}}},
        {"entryId":"tweet-3","content":{"entryType":"TimelineTimelineItem","itemContent":{"itemType":"TimelineTweet","tweet_results":{"result":{"__typename":"Tweet","rest_id":"3","legacy":{"user_id_str":"42","full_text":"RT @alice: hi","retweeted_status_result":{"result":{"__typename":"Tweet","rest_id":"77","core":{"user_results":{"result":{"rest_id":"55","legacy":{"screen_name":"alice"}}}},"legacy":{"user_id_str":"55","full_text":"hi there","favorite_count":900,"extended_entities":{"media":[{"id":1}]}}}}}}}}}},
        {"entryId":"profile-conversation-5","content":{"entryType":"TimelineTimelineModule","items":[
           {"item":{"itemContent":{"itemType":"TimelineTweet","tweet_results":{"result":{"__typename":"TweetWithVisibilityResults","tweet":{"__typename":"Tweet","rest_id":"4","legacy":{"user_id_str":"99","full_text":"someone else"}}}}}}},
           {"item":{"itemContent":{"itemType":"TimelineTweet","tweet_results":{"result":{"__typename":"Tweet","rest_id":"5","legacy":{"user_id_str":"42","full_text":"mine in thread","is_quote_status":true}}}}}}]}},
        {"entryId":"cursor-bottom-123","content":{"entryType":"TimelineTimelineCursor","cursorType":"Bottom","value":"CURSOR_NEXT"}}
      ]}
    ]}}}}}}
    """.trimIndent()

    @Test fun parsesTimelineEntriesPinnedModulesAndCursor() {
        val page = XParser.parseTimeline(Json.parseToJsonElement(timeline))
        assertEquals("CURSOR_NEXT", page.cursor)
        val byId = page.tweets.associateBy { it.id }
        assertEquals(setOf("1", "2", "3", "4", "5"), byId.keys)
        assertTrue(byId.getValue("1").pinned)
        val reply = byId.getValue("2")
        assertEquals(PostType.REPLY, reply.type)
        assertEquals("bob", reply.replyToHandle)
        assertEquals(1500L, reply.views)
        assertTrue(reply.links && reply.mentions)
        assertEquals(1_539_202_764_000L, reply.createdAt)
        val rt = byId.getValue("3")
        assertEquals(PostType.REPOST, rt.type)
        assertEquals("77", rt.sourceId)
        assertEquals("alice", rt.authorHandle)
        assertEquals(900, rt.likes)
        assertTrue(rt.media)
        assertEquals("42", rt.ownerId)
        assertEquals("99", byId.getValue("4").ownerId)
        assertEquals(PostType.QUOTE, byId.getValue("5").type)
    }

    @Test fun snowflakeFallbackForMissingDates() {
        val t = XParser.snowflakeTime("1000000000000000000")
        assertTrue(t > 1_500_000_000_000L)
    }

    @Test fun tweetStateAbsentTombstoneAndPresent() {
        fun st(s: String) = XParser.parseTweetState(Json.parseToJsonElement(s))
        assertEquals(TweetState.Absent, st("""{"data":{}}"""))
        assertEquals(TweetState.Unavailable, st("""{"data":{"tweetResult":{"result":{"__typename":"TweetTombstone"}}}}"""))
        val p = st("""{"data":{"tweetResult":{"result":{"__typename":"Tweet","rest_id":"1","legacy":{"favorited":false,"retweeted":true}}}}}""")
        assertTrue(p is TweetState.Present)
        p as TweetState.Present
        assertEquals(false, p.favorited)
        assertEquals(true, p.retweeted)
        val vis = st("""{"data":{"tweetResult":{"result":{"__typename":"TweetWithVisibilityResults","tweet":{"__typename":"Tweet","rest_id":"1","legacy":{"favorited":true}}}}}}""")
        assertTrue(vis is TweetState.Present && vis.favorited == true)
    }

    // ------------------------------------------------------------------ schedule

    private val utc = ZoneId.of("UTC")
    private fun at(h: Int, m: Int = 0, day: Int = 15) = ZonedDateTime.of(2026, 6, day, h, m, 0, 0, utc).toInstant().toEpochMilli() // 15 Jun 2026 is a Monday

    @Test fun scheduleAlwaysAndWindow() {
        assertEquals(0, ScheduleGate.nextAllowedAt(at(3), ScheduleConfig(), utc))
        val cfg = ScheduleConfig(ScheduleMode.WINDOW, startMinute = 8 * 60, endMinute = 23 * 60)
        assertEquals(0L, ScheduleGate.nextAllowedAt(at(9), cfg, utc))
        assertEquals(at(8, 0, 16), ScheduleGate.nextAllowedAt(at(23, 30), cfg, utc))
        assertEquals(at(8), ScheduleGate.nextAllowedAt(at(6), cfg, utc))
    }

    @Test fun scheduleOvernightAndDays() {
        val night = ScheduleConfig(ScheduleMode.WINDOW, startMinute = 22 * 60, endMinute = 6 * 60)
        assertEquals(0L, ScheduleGate.nextAllowedAt(at(23), night, utc))
        assertEquals(0L, ScheduleGate.nextAllowedAt(at(2), night, utc))
        assertEquals(at(22), ScheduleGate.nextAllowedAt(at(12), night, utc))
        val weekdays = ScheduleConfig(ScheduleMode.WINDOW, startMinute = 9 * 60, endMinute = 17 * 60, daysMask = 0b0011111)
        assertEquals(at(9, 0, 22), ScheduleGate.nextAllowedAt(at(10, 0, 20), weekdays, utc)) // Saturday -> next Monday
    }
}

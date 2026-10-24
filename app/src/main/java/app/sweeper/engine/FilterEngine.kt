package app.sweeper.engine

import app.sweeper.Graph
import app.sweeper.data.DateMode
import app.sweeper.data.FilterConfig
import app.sweeper.data.PostEntity
import app.sweeper.data.PostType
import app.sweeper.data.TriState
import app.sweeper.data.TypeRule
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Pre-compiled form of a [FilterConfig] so matching thousands of posts stays cheap. */
class CompiledFilter(
    val f: FilterConfig,
    private val now: Long,
    private val keepCutoff: Map<PostType, Long> = emptyMap(),
) {
    private val keepIds: Set<String> = Regex("""\d{5,}""").findAll(f.keepIds).map { it.value }.toSet()
    private val include = patterns(f.includeWords)
    private val exclude = patterns(f.excludeWords)
    private val langs = csv(f.languages)
    private val replyTo = handles(f.replyToHandles)
    private val authorsIn = handles(f.authorsInclude)
    private val authorsEx = handles(f.authorsExclude)

    private fun csv(s: String) = s.split(',', ' ', '\n', ';').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
    private fun handles(s: String) = csv(s).map { it.removePrefix("@") }.toSet()

    private fun patterns(s: String): List<Regex> {
        val opts = if (f.caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
        val parts = (if (f.useRegex) s.lines() else s.split('\n', ',')).map { it.trim() }.filter { it.isNotEmpty() }
        return parts.mapNotNull { w ->
            runCatching {
                when {
                    f.useRegex -> Regex(w, opts)
                    f.wholeWord -> Regex("(?<![\\p{L}\\p{N}_])" + Regex.escape(w) + "(?![\\p{L}\\p{N}_])", opts)
                    else -> Regex(Regex.escape(w), opts)
                }
            }.getOrNull()
        }
    }

    private fun rule(t: PostType): TypeRule = when (t) {
        PostType.POST -> f.posts
        PostType.REPLY -> f.replies
        PostType.REPOST -> f.reposts
        PostType.QUOTE -> f.quotes
        PostType.LIKE -> f.likes
    }

    private fun TriState.ok(v: Boolean) = this == TriState.ANY || (this == TriState.YES) == v

    /** True = this item would be deleted. */
    fun matches(p: PostEntity): Boolean {
        if (p.manual == 1) return false
        if (p.manual == 2) return true
        val rule = rule(p.type)
        if (!rule.enabled) return false
        if (p.id in keepIds || (p.sourceId != null && p.sourceId in keepIds)) return false
        if (f.keepPinned && p.pinned) return false

        val day = 86_400_000L
        val dateOk = when (f.dateMode) {
            DateMode.ALL -> true
            DateMode.OLDER_THAN -> p.createdAt <= now - f.olderThanDays * day
            DateMode.RANGE -> (f.afterMs == 0L || p.createdAt >= f.afterMs) && (f.beforeMs == 0L || p.createdAt <= f.beforeMs)
        }
        if (!dateOk) return false
        if (rule.olderThanDays > 0 && p.createdAt > now - rule.olderThanDays * day) return false
        keepCutoff[p.type]?.let { if (p.createdAt >= it) return false }

        if (include.isNotEmpty()) {
            val hit = if (f.includeAll) include.all { it.containsMatchIn(p.text) } else include.any { it.containsMatchIn(p.text) }
            if (!hit) return false
        }
        if (exclude.any { it.containsMatchIn(p.text) }) return false

        if (f.keepLikes > 0 && p.likeCount >= f.keepLikes) return false
        if (f.keepReposts > 0 && p.repostCount >= f.keepReposts) return false
        if (f.keepReplies > 0 && p.replyCount >= f.keepReplies) return false
        if (f.keepViews > 0 && p.viewCount >= f.keepViews) return false

        if (!f.hasMedia.ok(p.hasMedia) || !f.hasLinks.ok(p.hasLinks) || !f.hasHashtags.ok(p.hasHashtags) ||
            !f.hasMentions.ok(p.hasMentions)
        ) return false
        if (p.type == PostType.REPLY && !f.selfReply.ok(p.selfReply)) return false

        if (langs.isNotEmpty() && (p.lang?.lowercase() !in langs)) return false
        if (p.type == PostType.REPLY && replyTo.isNotEmpty() && (p.replyToHandle?.lowercase() !in replyTo)) return false

        if (p.type == PostType.LIKE || p.type == PostType.REPOST || p.type == PostType.QUOTE) {
            val a = p.authorHandle?.lowercase()
            if (authorsIn.isNotEmpty() && a !in authorsIn) return false
            if (authorsEx.isNotEmpty() && a in authorsEx) return false
        }
        return true
    }
}

object FilterEngine {
    private val lock = Mutex()

    /** Re-evaluates every not-yet-queued item against the current filter. Safe to call often. */
    suspend fun recompute(now: Long = System.currentTimeMillis()): Int = lock.withLock {
        val dao = Graph.db.posts()
        val f = Graph.settings.current.filter
        val cutoffs = HashMap<PostType, Long>()
        if (f.keepNewest > 0) {
            for (t in PostType.entries) dao.nthNewest(t, f.keepNewest - 1)?.let { cutoffs[t] = it }
        }
        val cf = CompiledFilter(f, now, cutoffs)
        var lastId = ""
        var lastType = PostType.POST
        var changed = 0
        while (true) {
            val page = dao.pulledAfter(lastId, lastType, 500)
            if (page.isEmpty()) break
            val last = page.last()
            lastId = last.id
            lastType = last.type
            for ((type, items) in page.groupBy { it.type }) {
                val yes = items.filter { cf.matches(it) }
                val no = items.filter { !cf.matches(it) }
                changed += items.count { it.matches != cf.matches(it) }
                if (yes.isNotEmpty()) dao.setMatches(type, yes.map { it.id }, true)
                if (no.isNotEmpty()) dao.setMatches(type, no.map { it.id }, false)
            }
        }
        changed
    }
}

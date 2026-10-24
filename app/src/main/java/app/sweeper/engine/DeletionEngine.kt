package app.sweeper.engine

import android.content.Context
import app.sweeper.AppLog
import app.sweeper.Graph
import app.sweeper.data.AttentionKind
import app.sweeper.data.Ctl
import app.sweeper.data.DeleteOrder
import app.sweeper.data.ItemStatus
import app.sweeper.data.PostEntity
import app.sweeper.data.PostType
import app.sweeper.data.RunMode
import app.sweeper.data.RunPhase
import app.sweeper.data.Settings
import app.sweeper.net.ApiOutcome
import app.sweeper.net.TimelineKind
import app.sweeper.net.TweetState
import app.sweeper.net.XParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

sealed interface Exit {
    data object Done : Exit
    data object Stopped : Exit
    data object Paused : Exit

    /** Nothing to do for a while: the service lets go of the foreground and an alarm wakes it at [resumeAt]. */
    data class Park(val resumeAt: Long, val phase: RunPhase, val message: String) : Exit

    /** A human has to look at this before anything else happens. */
    data class Attention(val kind: AttentionKind, val text: String) : Exit
}

private class ExitSignal(val exit: Exit) : CancellationException("exit")

/**
 * Scan -> (review) -> delete -> verify, one item at a time, always through the [RateGovernor].
 * An item becomes VERIFIED only after an independent read confirms it is really gone.
 */
class DeletionEngine(
    private val ctx: Context,
    private val keepAwake: (Long) -> Unit,
) {
    private val dao = Graph.db.posts()
    private val bus = Graph.bus
    private val api = Graph.api
    private val rnd = Random.Default
    private val clock = System::currentTimeMillis

    private lateinit var gov: RateGovernor
    private var cfg: Settings = Graph.settings.current
    private var consecutiveErrors = 0
    private var sinceBreather = 0
    private var nextBreatherAt = 0

    suspend fun run(mode: RunMode, fresh: Boolean): Exit = try {
        cfg = Graph.settings.current
        Graph.store.account()?.let { acc ->
            Graph.transport.restoreSessionIfPresent(acc.authToken, acc.ct0, acc.userId)
        }
        gov = RateGovernor(Graph.store.loadGovernor())
        scheduleBreather()
        when (mode) {
            RunMode.SCAN -> scanPhase(fresh) ?: Exit.Done
            RunMode.DELETE -> deletePhase()
            RunMode.SCAN_DELETE -> {
                val s = scanPhase(fresh)
                if (s != null) s else {
                    dao.queueMatching()
                    deletePhase()
                }
            }
        }
    } catch (e: ExitSignal) {
        e.exit
    }

    // ------------------------------------------------------------------ plumbing

    private fun checkpoint() {
        when (Graph.control.state.value) {
            Ctl.STOP -> throw ExitSignal(Exit.Stopped)
            Ctl.PAUSE -> throw ExitSignal(Exit.Paused)
            Ctl.RUN -> {}
        }
    }

    private suspend fun sleep(ms: Long) {
        if (ms > 0) withTimeoutOrNull(ms) { Graph.control.state.first { it != Ctl.RUN } }
        checkpoint()
    }

    private fun setPhase(phase: RunPhase, msg: String, resumeAt: Long = 0) {
        bus.update { it.copy(phase = phase, message = msg, resumeAt = resumeAt) }
    }

    private suspend fun persistGov() = Graph.store.saveGovernor(gov.snapshot())

    private fun writePolicy(): BucketPolicy = cfg.speed.let {
        BucketPolicy(
            windowMs = it.windowMinutes * 60_000L, initialBudget = it.budget, floor = it.floor, ceiling = it.ceiling,
            minGapMs = it.minGapSec * 1000L, maxGapMs = max(it.maxGapSec, it.minGapSec) * 1000L,
            spread = it.spread, adaptive = it.adaptive, useHeaders = it.useHeaders, jitter = it.jitterPct / 100.0,
        )
    }

    private fun policy(b: Bucket): BucketPolicy {
        val s = cfg.speed
        val win = s.windowMinutes * 60_000L
        return when (b) {
            Bucket.READ_TIMELINE -> BucketPolicy(win, s.readBudget, 5, s.readBudget * 2, 1_500, 5_000, false, s.adaptive, s.useHeaders, 0.4)
            Bucket.READ_TWEET -> BucketPolicy(win, s.verifyBudget, 15, s.verifyBudget * 2, 700, 2_500, false, s.adaptive, s.useHeaders, 0.4)
            else -> writePolicy()
        }
    }

    private fun caps() = Caps(cfg.speed.hourlyCap, cfg.speed.dailyCap)

    private fun fmt(ms: Long): String {
        val s = ms / 1000
        return when {
            s >= 3600 -> "${s / 3600}h ${(s % 3600) / 60}m"
            s >= 60 -> "${s / 60}m ${s % 60}s"
            else -> "${s}s"
        }
    }

    private suspend fun publishStats(b: Bucket = Bucket.DELETE_TWEET) {
        val now = clock()
        val p = policy(b)
        val st = gov.state(b)
        val budget = if (st.budget > 0) floor(st.budget).toInt() else p.initialBudget
        val remaining = dao.remaining()
        val eta = remaining.toLong() * p.windowMs / max(1, budget)
        val hits = Bucket.entries.sumOf { gov.state(it).hits }
        val rate = dao.verifiedSince(now - 3_600_000)
        bus.update {
            it.copy(
                windowUsed = st.used, windowBudget = budget,
                windowEndsAt = if (st.windowStart > 0) st.windowStart + p.windowMs else 0,
                windowMinutes = cfg.speed.windowMinutes, cooldownsHit = hits,
                etaMs = eta, ratePerHour = rate.toDouble(),
            )
        }
    }

    private fun scheduleBreather() {
        sinceBreather = 0
        val s = cfg.speed
        nextBreatherAt = s.breatherEveryMin + rnd.nextInt(max(1, s.breatherEveryMax - s.breatherEveryMin + 1))
    }

    /** Waits [ms]. Long waits are handed back as a Park so the service can drop the foreground and use an alarm. */
    private suspend fun waitFor(ms: Long, phase: RunPhase, msg: String): Exit? {
        val resume = clock() + ms
        if (ms >= cfg.safety.parkAfterSec * 1000L) return Exit.Park(resume, phase, msg)
        setPhase(phase, msg, resume)
        publishStats()
        if (cfg.safety.keepCpuAwake) keepAwake(ms + 15_000)
        sleep(ms)
        return null
    }

    /** Blocks until a request of bucket [b] may go out. Returns an Exit if the run must stop or park instead. */
    private suspend fun gate(b: Bucket): Exit? {
        while (true) {
            checkpoint()
            cfg = Graph.settings.current
            Conditions.check(ctx, cfg.safety, cfg.schedule, clock())?.let {
                return Exit.Park(max(it.retryAtMs, clock() + 20_000), RunPhase.WAITING, it.reason)
            }
            val w = gov.acquire(b, policy(b), caps())
            if (w.go) return null
            val (phase, msg) = when (w.reason) {
                WaitReason.RATE_LIMITED -> RunPhase.COOLDOWN to "Rate-limited by X, cooling down"
                WaitReason.WINDOW_FULL -> RunPhase.PARKED to "Window budget used, waiting for the next one"
                WaitReason.HOURLY_CAP -> RunPhase.PARKED to "Hourly cap reached"
                WaitReason.DAILY_CAP -> RunPhase.PARKED to "Daily cap reached"
                else -> RunPhase.PACING to "Next one in ${fmt(w.ms)}"
            }
            waitFor(w.ms, phase, msg)?.let { return it }
        }
    }

    // ------------------------------------------------------------------ error handling

    private sealed interface Reaction {
        data object Retry : Reaction
        data class Stop(val exit: Exit) : Reaction
        data class Permanent(val msg: String) : Reaction
        data class Gone(val msg: String) : Reaction
    }

    private suspend fun react(o: ApiOutcome<*>, b: Bucket): Reaction {
        val p = policy(b)
        return when (o) {
            is ApiOutcome.RateLimited -> {
                val w = gov.onRateLimited(b, p, o.resetAtMs, o.retryAfterMs)
                persistGov()
                consecutiveErrors = 0
                val st = gov.state(b)
                AppLog.w("limit", "HTTP 429 on ${b.name}: silent for ${fmt(w)}; learned budget ${st.budget.toInt()} per window (strike ${st.strikes})")
                Reaction.Retry
            }
            is ApiOutcome.AuthExpired -> Reaction.Stop(Exit.Attention(AttentionKind.LOGIN, "X says the session expired. Log in again."))
            is ApiOutcome.Locked -> Reaction.Stop(Exit.Attention(AttentionKind.LOCKED, "X reports the account is locked or suspended: ${o.msg}"))
            is ApiOutcome.Automation -> {
                AppLog.e("safety", "Automation flag from X: ${o.msg}")
                if (cfg.safety.stopOnAutomationFlag) {
                    Reaction.Stop(
                        Exit.Attention(
                            AttentionKind.AUTOMATION,
                            "X flagged the activity as automated. Stopped to protect your account. Wait a few hours, pick the Gentle profile, then resume.",
                        ),
                    )
                } else {
                    gov.coolAllWrites(clock() + cfg.safety.automationCooldownHours * 3_600_000L)
                    persistGov()
                    Reaction.Retry
                }
            }
            is ApiOutcome.Transient -> {
                consecutiveErrors++
                AppLog.w("net", "Transient error (${consecutiveErrors}/${cfg.safety.maxConsecutiveErrors}): ${o.msg}")
                if (consecutiveErrors >= cfg.safety.maxConsecutiveErrors) {
                    consecutiveErrors = 0
                    Reaction.Stop(Exit.Park(clock() + 600_000, RunPhase.WAITING, "Too many errors in a row (${o.msg}). Trying again in 10 minutes."))
                } else {
                    val ms = min(120_000L, 4_000L shl min(consecutiveErrors, 5)) + rnd.nextLong(1_000, 4_000)
                    val exit = waitFor(ms, RunPhase.PACING, "Retrying after an error: ${o.msg}")
                    if (exit != null) Reaction.Stop(exit) else Reaction.Retry
                }
            }
            is ApiOutcome.Permanent -> Reaction.Permanent(o.msg)
            is ApiOutcome.Gone -> Reaction.Gone(o.msg)
            is ApiOutcome.EndpointStale -> Reaction.Permanent(o.msg)
            is ApiOutcome.BadRequest -> Reaction.Permanent(o.msg)
            is ApiOutcome.CsrfStale -> Reaction.Stop(Exit.Attention(AttentionKind.LOGIN, "X rejected the session token. Log in again."))
            is ApiOutcome.Ok -> Reaction.Permanent("unexpected")
        }
    }

    // ------------------------------------------------------------------ scan

    private suspend fun scanPhase(fresh: Boolean): Exit? {
        val me = Graph.store.account() ?: return Exit.Attention(AttentionKind.LOGIN, "Log in first.")
        if (bus.snapshot.value.scanComplete && !fresh) return null
        val f = cfg.filter
        val kinds = buildList {
            if (f.posts.enabled || f.reposts.enabled || f.quotes.enabled) {
                add(TimelineKind.TWEETS)
            }
            if (f.replies.enabled) {
                add(TimelineKind.TWEETS_REPLIES)
            }
            if (f.likes.enabled) add(TimelineKind.LIKES)
        }
        if (kinds.isEmpty()) return Exit.Attention(AttentionKind.ERRORS, "Nothing selected. Turn on a type in Filters.")
        if (fresh) bus.update { it.copy(cursors = emptyMap(), scanned = 0, scanComplete = false) }
        val incrementalOk = cfg.scan.incremental && bus.snapshot.value.lastScanAt > 0 && fresh

        for (kind in kinds) {
            if (bus.snapshot.value.cursors[kind.name] == "DONE") continue
            var cursor = bus.snapshot.value.cursors[kind.name]
            var empty = 0
            var kindCount = 0
            while (true) {
                gate(Bucket.READ_TIMELINE)?.let { return it }
                gov.recordSend(Bucket.READ_TIMELINE, policy(Bucket.READ_TIMELINE))
                persistGov()
                setPhase(RunPhase.SCANNING, "Reading ${kind.name.lowercase().replace('_', ' ')} (${bus.snapshot.value.scanned} found)")
                when (val o = api.timeline(kind, me.userId, cursor, cfg.scan.pageSize)) {
                    is ApiOutcome.Ok -> {
                        gov.onResponse(Bucket.READ_TIMELINE, policy(Bucket.READ_TIMELINE), o.headers)
                        consecutiveErrors = 0
                        val page = o.value
                        val own = page.tweets.filter { kind == TimelineKind.LIKES || it.ownerId == me.userId }
                        val now = clock()
                        val entities = own.map {
                            val e = XParser.toEntity(it, now)
                            if (kind == TimelineKind.LIKES) e.copy(type = PostType.LIKE) else e
                        }
                        val res = if (entities.isEmpty()) emptyList() else dao.insertIgnore(entities)
                        var fresh0 = 0
                        entities.forEachIndexed { i, e ->
                            if (res[i] == -1L) dao.refreshMetrics(e.id, e.type, e.likeCount, e.repostCount, e.replyCount, e.quoteCount, e.viewCount)
                            else fresh0++
                        }
                        val cf = CompiledFilter(cfg.filter, now)
                        for ((type, items) in entities.filterIndexed { i, _ -> res[i] != -1L }.groupBy { it.type }) {
                            val yes = items.filter { cf.matches(it) }.map { it.id }
                            if (yes.isNotEmpty()) dao.setMatches(type, yes, true)
                        }
                        kindCount += own.size
                        val next = page.cursor
                        empty = if (page.tweets.isEmpty()) empty + 1 else 0
                        val limitHit = cfg.scan.maxItems > 0 && bus.snapshot.value.scanned + fresh0 >= cfg.scan.maxItems
                        val caughtUp = incrementalOk && own.isNotEmpty() && fresh0 == 0
                        val finished = next == null || next == cursor || empty >= cfg.scan.emptyPagesToStop || limitHit || caughtUp
                        bus.update {
                            it.copy(scanned = it.scanned + fresh0, cursors = it.cursors + (kind.name to (if (finished) "DONE" else next!!)))
                        }
                        if (finished) break
                        cursor = next
                    }
                    else -> when (val r = react(o, Bucket.READ_TIMELINE)) {
                        Reaction.Retry -> {}
                        is Reaction.Stop -> return r.exit
                        is Reaction.Permanent -> return Exit.Attention(AttentionKind.ERRORS, "Scan failed: ${r.msg}")
                        is Reaction.Gone -> break
                    }
                }
            }
            AppLog.i("scan", "${kind.name}: read $kindCount items")
        }
        FilterEngine.recompute()
        bus.update { it.copy(scanComplete = true, lastScanAt = clock(), cursors = emptyMap()) }
        val total = dao.total()
        val will = dao.willDelete()
        AppLog.i("scan", "Scan complete: $total items stored, $will match the filter")
        setPhase(RunPhase.DONE, "Scan complete: $will of $total match your filters")
        return null
    }

    // ------------------------------------------------------------------ delete

    private fun bucketFor(p: PostEntity) = when {
        p.type == PostType.LIKE -> Bucket.UNLIKE
        p.type == PostType.REPOST && p.sourceId != null -> Bucket.DELETE_REPOST
        else -> Bucket.DELETE_TWEET
    }

    private suspend fun nextItem(): PostEntity? = when (cfg.safety.order) {
        DeleteOrder.OLDEST_FIRST -> dao.nextOldest()
        DeleteOrder.NEWEST_FIRST -> dao.nextNewest()
        DeleteOrder.RANDOM -> dao.nextRandom()
    }

    private fun short(p: PostEntity) = p.text.replace('\n', ' ').take(40).ifBlank { p.id }

    private suspend fun deletePhase(): Exit {
        dao.recoverDeleting()
        val rem0 = dao.remaining()
        bus.update { it.copy(runTotal = it.runDeleted + it.runFailed + rem0) }
        if (rem0 == 0) return Exit.Done

        // First settle anything X already accepted but we never confirmed (crash, kill, parked mid-verify).
        for (p in dao.sentItems()) verifyItem(p)?.let { return it }

        var preChecks = 0
        while (true) {
            checkpoint()
            cfg = Graph.settings.current
            val p0 = nextItem() ?: break
            val b = bucketFor(p0)

            if (p0.fromArchive && cfg.verify.precheckArchive && p0.attempts == 0) {
                when (val v = check(p0)) {
                    is V.Halt -> return v.exit
                    is V.Fail -> {}
                    V.Gone -> { finish(p0, alreadyGone = true); continue }
                    V.Still -> preChecks++
                }
            }

            gate(b)?.let { return it }

            // The user may have changed their mind while we waited.
            val p = dao.get(p0.id, p0.type)
            if (p == null || p.status != ItemStatus.QUEUED) continue
            if (p.manual == 1) { dao.unqueueOne(p.id, p.type); continue }

            dao.markDeleting(p.id, p.type)
            gov.recordSend(b, policy(b))
            persistGov()
            setPhase(RunPhase.DELETING, "Deleting: ${short(p)}")

            when (val o = api.delete(p)) {
                is ApiOutcome.Ok -> {
                    gov.onResponse(b, policy(b), o.headers)
                    consecutiveErrors = 0
                    dao.markSent(p.id, p.type, clock())
                    verifyItem(p)?.let { return it }
                }
                is ApiOutcome.Gone -> {
                    dao.markSent(p.id, p.type, clock())
                    verifyItem(p)?.let { return it }
                }
                else -> when (val r = react(o, b)) {
                    Reaction.Retry -> dao.requeue(p.id, p.type, null)
                    is Reaction.Stop -> { dao.requeue(p.id, p.type, null); return r.exit }
                    is Reaction.Permanent -> fail(p, r.msg)
                    is Reaction.Gone -> { dao.markSent(p.id, p.type, clock()); verifyItem(p)?.let { return it } }
                }
            }
            publishStats(b)

            sinceBreather++
            if (cfg.speed.breathers && sinceBreather >= nextBreatherAt) {
                scheduleBreather()
                val ms = rnd.nextLong(cfg.speed.breatherSecMin * 1000L, max(cfg.speed.breatherSecMin, cfg.speed.breatherSecMax) * 1000L + 1)
                waitFor(ms, RunPhase.PACING, "Taking a short breather")?.let { return it }
            }
        }
        return Exit.Done
    }

    private suspend fun fail(p: PostEntity, msg: String) {
        dao.markFailed(p.id, p.type, msg)
        bus.update { it.copy(runFailed = it.runFailed + 1) }
        AppLog.w("delete", "FAILED ${p.type} ${p.id}: $msg")
    }

    private suspend fun finish(p: PostEntity, alreadyGone: Boolean) {
        dao.markVerified(p.id, p.type, clock(), alreadyGone)
        bus.update { it.copy(runDeleted = it.runDeleted + 1) }
        AppLog.i("delete", (if (alreadyGone) "Already gone " else "Verified deleted ") + "${p.type} ${p.id} \"${short(p)}\"")
    }

    // ------------------------------------------------------------------ verification

    private sealed interface Read {
        data class Got(val s: TweetState) : Read
        data class Halt(val exit: Exit) : Read
        data class Failed(val msg: String) : Read
    }

    private suspend fun readState(id: String): Read {
        while (true) {
            gate(Bucket.READ_TWEET)?.let { return Read.Halt(it) }
            gov.recordSend(Bucket.READ_TWEET, policy(Bucket.READ_TWEET))
            when (val o = api.tweetState(id)) {
                is ApiOutcome.Ok -> {
                    gov.onResponse(Bucket.READ_TWEET, policy(Bucket.READ_TWEET), o.headers)
                    consecutiveErrors = 0
                    return Read.Got(o.value)
                }
                else -> when (val r = react(o, Bucket.READ_TWEET)) {
                    Reaction.Retry -> {}
                    is Reaction.Stop -> return Read.Halt(r.exit)
                    is Reaction.Permanent -> return Read.Failed(r.msg)
                    is Reaction.Gone -> return Read.Got(TweetState.Absent)
                }
            }
        }
    }

    private sealed interface V {
        data object Gone : V
        data object Still : V
        data class Halt(val exit: Exit) : V
        data class Fail(val msg: String) : V
    }

    /** Independent read: is this item really gone from the account? */
    private suspend fun check(p: PostEntity): V {
        val s1 = when (val r = readState(p.id)) {
            is Read.Halt -> return V.Halt(r.exit)
            is Read.Failed -> return V.Fail(r.msg)
            is Read.Got -> r.s
        }
        when (s1) {
            TweetState.Absent -> return V.Gone
            TweetState.Unavailable -> return if (cfg.verify.unavailableIsGone) V.Gone else V.Still
            is TweetState.Present -> {}
        }
        if (s1 !is TweetState.Present) return V.Still
        return when (p.type) {
            PostType.LIKE -> if (s1.favorited == false) V.Gone else V.Still
            PostType.REPOST -> {
                val src = p.sourceId
                if (src == null) V.Still else when (val r = readState(src)) {
                    is Read.Halt -> V.Halt(r.exit)
                    is Read.Failed -> V.Fail(r.msg)
                    is Read.Got -> when (val s2 = r.s) {
                        TweetState.Absent, TweetState.Unavailable -> V.Gone
                        is TweetState.Present -> if (s2.retweeted == false) V.Gone else V.Still
                    }
                }
            }
            else -> V.Still
        }
    }

    /** Returns an Exit only if the whole run must stop or park; item-level outcomes are written to the DB. */
    private suspend fun verifyItem(p: PostEntity): Exit? {
        var checks = 0
        while (true) {
            sleep(rnd.nextLong(cfg.verify.delayMinMs.toLong(), max(cfg.verify.delayMinMs, cfg.verify.delayMaxMs).toLong() + 1))
            when (val v = check(p)) {
                is V.Halt -> return v.exit
                is V.Fail -> { fail(p, "Could not verify: ${v.msg}"); return null }
                V.Gone -> { finish(p, alreadyGone = false); return null }
                V.Still -> {
                    checks++
                    if (checks < cfg.verify.checksPerItem) {
                        waitFor(cfg.verify.retryDelaySec * 1000L, RunPhase.PACING, "Still visible, checking again")?.let { return it }
                        continue
                    }
                    val attempts = dao.get(p.id, p.type)?.attempts ?: 1
                    if (attempts >= cfg.verify.failAfterAttempts) fail(p, "Still visible after $attempts delete attempts")
                    else dao.requeue(p.id, p.type, "Still visible after delete, will retry")
                    return null
                }
            }
        }
    }
}

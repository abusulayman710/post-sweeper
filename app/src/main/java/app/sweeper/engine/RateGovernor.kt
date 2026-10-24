package app.sweeper.engine

import app.sweeper.data.BucketState
import app.sweeper.data.GovernorSnapshot
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/** Everything the governor needs to know about one kind of request. */
data class BucketPolicy(
    val windowMs: Long,
    val initialBudget: Int,
    val floor: Int,
    val ceiling: Int,
    val minGapMs: Long,
    val maxGapMs: Long,
    val spread: Boolean,
    val adaptive: Boolean,
    val useHeaders: Boolean,
    val jitter: Double,
)

data class Caps(val hourly: Int = 0, val daily: Int = 0) {
    companion object {
        val NONE = Caps()
    }
}

enum class WaitReason { NONE, PACING, WINDOW_FULL, RATE_LIMITED, HOURLY_CAP, DAILY_CAP }

data class Wait(val ms: Long, val reason: WaitReason) {
    val go: Boolean get() = ms <= 0
}

/**
 * The "super smart" part. One instance owns the request budget for every endpoint bucket.
 *
 *  - Never sends while a cooldown is active (no probing -> no spam while limited).
 *  - Spreads requests evenly over the window (or bursts, user's choice) with jitter, so traffic looks human.
 *  - Reads X's own x-rate-limit-* headers when present and obeys them with a safety reserve.
 *  - 429 -> sleeps until the server's reset (or an escalating ladder if there is no header) plus random slack,
 *    then shrinks its budget to just under what it observed (AIMD: multiplicative decrease).
 *  - Clean, well-used windows grow the budget again, a little at a time, up to the ceiling.
 *  - Hourly / daily caps are enforced across all write buckets.
 *
 * Pure logic with an injectable clock and RNG so it is fully unit-testable.
 */
class RateGovernor(
    snapshot: GovernorSnapshot,
    private val clock: () -> Long = System::currentTimeMillis,
    private val rnd: Random = Random.Default,
) {
    private val buckets = HashMap<String, BucketState>().apply { putAll(snapshot.buckets) }
    private var hourStart = snapshot.hourStart
    private var hourUsed = snapshot.hourUsed
    private var dayStart = snapshot.dayStart
    private var dayUsed = snapshot.dayUsed

    fun snapshot() = GovernorSnapshot(
        buckets = HashMap(buckets), hourStart = hourStart, hourUsed = hourUsed, dayStart = dayStart, dayUsed = dayUsed,
    )

    fun state(b: Bucket) = buckets[b.name] ?: BucketState()

    fun resetLearning() {
        buckets.replaceAll { _, s -> s.copy(budget = 0.0, strikes = 0, okSinceStrike = 0, cleanWindows = 0) }
    }

    private fun jitterMs(lo: Long, hi: Long) = lo + rnd.nextLong(max(1, hi - lo))

    private fun budgetInt(s: BucketState) = max(1, floor(s.budget).toInt())

    private fun normalise(s0: BucketState, p: BucketPolicy, now: Long): BucketState {
        var s = s0
        if (s.budget <= 0.0) s = s.copy(budget = p.initialBudget.toDouble())
        s = s.copy(budget = s.budget.coerceIn(p.floor.toDouble(), max(p.floor, p.ceiling).toDouble()))
        if (s.windowStart != 0L && now >= s.windowStart + p.windowMs) {
            var budget = s.budget
            var clean = s.cleanWindows
            // Additive increase, only after a window that was actually used and drew no 429.
            if (p.adaptive && !s.hitThisWindow && s.used >= floor(budget) * 0.9) {
                budget = min(p.ceiling.toDouble(), budget * 1.08 + 1.0)
                clean++
            }
            s = s.copy(windowStart = 0, used = 0, budget = budget, cleanWindows = clean, hitThisWindow = false)
        }
        return s
    }

    /** How long to wait before the next request of this bucket may go out (0 = go now). Does not consume budget. */
    fun acquire(b: Bucket, p: BucketPolicy, caps: Caps = Caps.NONE): Wait {
        val now = clock()
        val s = normalise(state(b), p, now)
        buckets[b.name] = s

        if (now < s.cooldownUntil) return Wait(s.cooldownUntil - now, WaitReason.RATE_LIMITED)

        if (p.useHeaders && s.serverRemaining >= 0 && now < s.serverResetAt) {
            val reserve = if (s.serverLimit > 0) max(1, (s.serverLimit * 0.04).toInt()) else 1
            if (s.serverRemaining <= reserve) {
                return Wait(s.serverResetAt - now + jitterMs(5_000, 30_000), WaitReason.WINDOW_FULL)
            }
        }

        if (b.write) capWait(caps, now)?.let { return it }

        if (s.used >= budgetInt(s)) {
            val end = if (s.windowStart != 0L) s.windowStart + p.windowMs else now
            return Wait(max(1_000, end - now + jitterMs(2_000, 20_000)), WaitReason.WINDOW_FULL)
        }

        if (now < s.nextAllowedAt) return Wait(s.nextAllowedAt - now, WaitReason.PACING)
        return Wait(0, WaitReason.NONE)
    }

    private fun capWait(caps: Caps, now: Long): Wait? {
        if (now >= hourStart + HOUR) { hourStart = 0; hourUsed = 0 }
        if (now >= dayStart + DAY) { dayStart = 0; dayUsed = 0 }
        if (caps.hourly > 0 && hourUsed >= caps.hourly && hourStart != 0L) {
            return Wait(hourStart + HOUR - now + jitterMs(3_000, 40_000), WaitReason.HOURLY_CAP)
        }
        if (caps.daily > 0 && dayUsed >= caps.daily && dayStart != 0L) {
            return Wait(dayStart + DAY - now + jitterMs(10_000, 120_000), WaitReason.DAILY_CAP)
        }
        return null
    }

    /** Call right before the request goes out. Consumes budget and schedules the next allowed time. */
    fun recordSend(b: Bucket, p: BucketPolicy) {
        val now = clock()
        var s = normalise(state(b), p, now)
        if (s.windowStart == 0L) s = s.copy(windowStart = now)
        s = s.copy(used = s.used + 1, totalSent = s.totalSent + 1, lastSendAt = now)
        s = s.copy(nextAllowedAt = now + nextGap(s, p, now))
        buckets[b.name] = s
        if (b.write) {
            if (hourStart == 0L) hourStart = now
            if (dayStart == 0L) dayStart = now
            hourUsed++
            dayUsed++
        }
    }

    private fun nextGap(s: BucketState, p: BucketPolicy, now: Long): Long {
        val remainingBudget = max(1, budgetInt(s) - s.used)
        val remainingWindow = max(0L, s.windowStart + p.windowMs - now)
        val base = if (p.spread) {
            max(p.minGapMs, remainingWindow / remainingBudget)
        } else {
            p.minGapMs + rnd.nextLong(max(1, p.maxGapMs - p.minGapMs + 1))
        }
        val j = 1.0 + (rnd.nextDouble() * 2 - 1) * p.jitter
        return max(p.minGapMs, (base * j).toLong())
    }

    /** A request succeeded. Pass the response headers (lower-case keys). */
    fun onResponse(b: Bucket, p: BucketPolicy, headers: Map<String, String>) {
        var s = state(b)
        if (s.budget <= 0.0) s = s.copy(budget = p.initialBudget.toDouble())
        val limit = headers["x-rate-limit-limit"]?.toIntOrNull()
        val remaining = headers["x-rate-limit-remaining"]?.toIntOrNull()
        val reset = headers["x-rate-limit-reset"]?.toLongOrNull()
        if (limit != null && remaining != null && reset != null) {
            s = s.copy(serverLimit = limit, serverRemaining = remaining, serverResetAt = reset * 1000)
            if (p.adaptive && p.useHeaders && limit > 0) {
                val cap = max(p.floor.toDouble(), limit * 0.9)
                if (s.budget > cap) s = s.copy(budget = cap)
            }
        }
        val ok = s.okSinceStrike + 1
        s = s.copy(okSinceStrike = ok, strikes = if (ok >= 3) 0 else s.strikes)
        buckets[b.name] = s
    }

    /**
     * Server said 429 (or code 88). Returns how long we will stay silent.
     * [resetAtMs] comes from x-rate-limit-reset, [retryAfterMs] from Retry-After; either may be null.
     */
    fun onRateLimited(b: Bucket, p: BucketPolicy, resetAtMs: Long?, retryAfterMs: Long?): Long {
        val now = clock()
        var s = state(b)
        if (s.budget <= 0.0) s = s.copy(budget = p.initialBudget.toDouble())
        val strikes = s.strikes + 1
        val ladder = LADDER[min(strikes - 1, LADDER.size - 1)]
        var base = when {
            resetAtMs != null && resetAtMs > now -> resetAtMs - now
            retryAfterMs != null && retryAfterMs > 0 -> retryAfterMs
            else -> ladder
        }
        // Hit again right after a reset: the header lied or the limit is stricter than we thought.
        if (strikes > 1) base = max(base, ladder)
        val wait = max(30_000L, base) + jitterMs(15_000, 60_000)

        var budget = s.budget
        if (p.adaptive) {
            val observed = s.used
            val shrunk = if (observed >= 3) min(budget * 0.85, observed * 0.9) else budget * 0.85
            budget = max(p.floor.toDouble(), shrunk)
        }
        s = s.copy(
            strikes = strikes, okSinceStrike = 0, hits = s.hits + 1, hitThisWindow = true, budget = budget,
            cooldownUntil = now + wait, windowStart = 0, used = 0, nextAllowedAt = 0,
            serverRemaining = -1,
        )
        buckets[b.name] = s
        return wait
    }

    /** Hard pause for every write bucket (used for the "looks automated" flag). */
    fun coolAllWrites(untilMs: Long) {
        for (b in Bucket.entries) if (b.write) {
            buckets[b.name] = state(b).copy(cooldownUntil = max(state(b).cooldownUntil, untilMs))
        }
    }

    companion object {
        const val HOUR = 3_600_000L
        const val DAY = 86_400_000L
        val LADDER = longArrayOf(120_000, 300_000, 900_000, 1_800_000, 3_600_000, 7_200_000)
    }
}

enum class Bucket(val write: Boolean) {
    DELETE_TWEET(true),
    DELETE_REPOST(true),
    UNLIKE(true),
    READ_TIMELINE(false),
    READ_TWEET(false),
}


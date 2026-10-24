package app.sweeper

import app.sweeper.data.GovernorSnapshot
import app.sweeper.engine.Bucket
import app.sweeper.engine.BucketPolicy
import app.sweeper.engine.Caps
import app.sweeper.engine.RateGovernor
import app.sweeper.engine.WaitReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class RateGovernorTest {
    private var now = 1_000_000_000L
    private val policy = BucketPolicy(
        windowMs = 900_000, initialBudget = 30, floor = 5, ceiling = 50, minGapMs = 1_000, maxGapMs = 5_000,
        spread = true, adaptive = true, useHeaders = true, jitter = 0.0,
    )

    private fun gov() = RateGovernor(GovernorSnapshot(), { now }, Random(1))

    /** Sends as fast as allowed for [windowMs] and returns how many went out. */
    private fun drive(g: RateGovernor, p: BucketPolicy, b: Bucket = Bucket.DELETE_TWEET, until: Long): Int {
        var sent = 0
        while (now < until) {
            val w = g.acquire(b, p)
            if (w.go) {
                g.recordSend(b, p)
                sent++
            } else {
                now += w.ms.coerceAtLeast(1)
            }
        }
        return sent
    }

    @Test
    fun neverExceedsBudgetInAWindow() {
        val g = gov()
        val start = now
        val sent = drive(g, policy, until = start + 900_000 - 1)
        assertTrue("sent $sent", sent <= 30)
        assertTrue("sent $sent", sent >= 25)
    }

    @Test
    fun spreadPacingIsEvenNotBursty() {
        val g = gov()
        val times = ArrayList<Long>()
        val start = now
        while (now < start + 300_000) {
            val w = g.acquire(Bucket.DELETE_TWEET, policy)
            if (w.go) { g.recordSend(Bucket.DELETE_TWEET, policy); times.add(now) } else now += w.ms
        }
        val gaps = times.zipWithNext { a, b -> b - a }
        assertTrue("gaps $gaps", gaps.all { it >= 1_000 })
        assertTrue("first gap should be near window/budget, was ${gaps.first()}", gaps.first() > 20_000)
    }

    @Test
    fun silentDuringCooldown() {
        val g = gov()
        g.recordSend(Bucket.DELETE_TWEET, policy)
        val wait = g.onRateLimited(Bucket.DELETE_TWEET, policy, null, null)
        assertTrue(wait >= 120_000)
        repeat(50) {
            now += 1_000
            if (now < g.state(Bucket.DELETE_TWEET).cooldownUntil) {
                val w = g.acquire(Bucket.DELETE_TWEET, policy)
                assertEquals(WaitReason.RATE_LIMITED, w.reason)
            }
        }
    }

    @Test
    fun usesServerResetWhenGiven() {
        val g = gov()
        val reset = now + 400_000
        val wait = g.onRateLimited(Bucket.DELETE_TWEET, policy, reset, null)
        assertTrue(wait in 400_000..400_000 + 60_001 + 30_000)
    }

    @Test
    fun ladderEscalatesOnRepeatedHits() {
        val g = gov()
        val w1 = g.onRateLimited(Bucket.DELETE_TWEET, policy, null, null)
        now += w1 + 1
        val w2 = g.onRateLimited(Bucket.DELETE_TWEET, policy, null, null)
        now += w2 + 1
        val w3 = g.onRateLimited(Bucket.DELETE_TWEET, policy, null, null)
        assertTrue("$w1 < $w2 < $w3", w1 < w2 && w2 < w3)
    }

    @Test
    fun budgetShrinksAfterHitAndRecoversAfterCleanWindows() {
        val g = gov()
        repeat(20) { g.recordSend(Bucket.DELETE_TWEET, policy); now += 1_000 }
        g.onRateLimited(Bucket.DELETE_TWEET, policy, null, null)
        val shrunk = g.state(Bucket.DELETE_TWEET).budget
        assertTrue("shrunk $shrunk", shrunk < 30 && shrunk >= policy.floor)

        // Wait out the cooldown, then run several clean, fully used windows.
        now = g.state(Bucket.DELETE_TWEET).cooldownUntil + 1
        repeat(6) {
            val end = now + 900_000
            drive(g, policy, until = end)
            now = end + 1
        }
        val grown = g.state(Bucket.DELETE_TWEET).budget
        assertTrue("grown $grown vs shrunk $shrunk", grown > shrunk)
        assertTrue(grown <= policy.ceiling)
    }

    @Test
    fun obeysRemainingHeaderBeforeHittingAWall() {
        val g = gov()
        g.recordSend(Bucket.DELETE_TWEET, policy)
        val reset = (now + 300_000) / 1000
        g.onResponse(Bucket.DELETE_TWEET, policy, mapOf("x-rate-limit-limit" to "50", "x-rate-limit-remaining" to "1", "x-rate-limit-reset" to reset.toString()))
        val w = g.acquire(Bucket.DELETE_TWEET, policy)
        assertEquals(WaitReason.WINDOW_FULL, w.reason)
        assertTrue(w.ms >= 290_000)
    }

    @Test
    fun hourlyCapIsEnforcedAcrossWriteBuckets() {
        val g = gov()
        val caps = Caps(hourly = 5)
        val open = policy.copy(initialBudget = 50, minGapMs = 1, maxGapMs = 2, spread = false)
        var sent = 0
        repeat(20) {
            val w = g.acquire(Bucket.DELETE_TWEET, open, caps)
            if (w.go) { g.recordSend(Bucket.DELETE_TWEET, open); sent++; now += 10 }
            else if (w.reason == WaitReason.PACING) now += w.ms
        }
        assertEquals(5, sent)
        assertEquals(WaitReason.HOURLY_CAP, g.acquire(Bucket.UNLIKE, open, caps).reason)
    }

    @Test
    fun readBucketsAreIndependentOfWriteCooldown() {
        val g = gov()
        g.onRateLimited(Bucket.DELETE_TWEET, policy, null, null)
        assertTrue(g.acquire(Bucket.READ_TWEET, policy).reason != WaitReason.RATE_LIMITED)
    }

    @Test
    fun snapshotSurvivesRestart() {
        val g = gov()
        g.onRateLimited(Bucket.DELETE_TWEET, policy, null, null)
        val g2 = RateGovernor(g.snapshot(), { now }, Random(2))
        assertEquals(WaitReason.RATE_LIMITED, g2.acquire(Bucket.DELETE_TWEET, policy).reason)
    }
}

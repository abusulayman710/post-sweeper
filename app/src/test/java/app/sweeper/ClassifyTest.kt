package app.sweeper

import app.sweeper.net.ApiOutcome
import app.sweeper.net.RawResponse
import app.sweeper.net.XApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClassifyTest {
    private fun c(status: Int, body: String, headers: Map<String, String> = emptyMap()) = XApi.classify(RawResponse(status, headers, body))

    @Test fun http429WithResetHeader() {
        val o = c(429, """{"errors":[{"code":88,"message":"Rate limit exceeded"}]}""", mapOf("x-rate-limit-reset" to "1800000000"))
        assertTrue(o is ApiOutcome.RateLimited)
        o as ApiOutcome.RateLimited
        assertEquals(1_800_000_000_000L, o.resetAtMs)
    }

    @Test fun rateLimitHiddenInA200() {
        assertTrue(c(200, """{"errors":[{"code":88,"message":"Rate limit exceeded"}]}""") is ApiOutcome.RateLimited)
    }

    @Test fun automationLockedAuthAndCsrf() {
        assertTrue(c(403, """{"errors":[{"code":226,"message":"This request looks like it might be automated."}]}""") is ApiOutcome.Automation)
        assertTrue(c(403, """{"errors":[{"code":326,"message":"To protect our users... account is temporarily locked."}]}""") is ApiOutcome.Locked)
        assertTrue(c(401, "{}") is ApiOutcome.AuthExpired)
        assertTrue(c(403, """{"errors":[{"code":215,"message":"Bad Authentication data."}]}""") is ApiOutcome.AuthExpired)
        assertTrue(c(403, """{"errors":[{"code":353,"message":"csrf"}]}""") is ApiOutcome.CsrfStale)
        assertTrue(c(403, """{"errors":[{"code":999,"message":"nope"}]}""") is ApiOutcome.Permanent)
    }

    @Test fun staleEndpointBadRequestAndServerErrors() {
        assertTrue(c(404, "") is ApiOutcome.EndpointStale)
        assertTrue(c(400, """{"errors":[{"message":"The following features cannot be null: a, b"}]}""") is ApiOutcome.BadRequest)
        assertTrue(c(503, "") is ApiOutcome.Transient)
        assertTrue(c(200, "<html>") is ApiOutcome.Transient)
    }

    @Test fun okAndGoneInA200() {
        assertTrue(c(200, """{"data":{"delete_tweet":{"tweet_results":{}}}}""") is ApiOutcome.Ok)
        assertTrue(c(200, """{"data":{}}""") is ApiOutcome.Ok)
        assertTrue(c(200, """{"errors":[{"code":144,"message":"_Missing: No status found with that ID."}]}""") is ApiOutcome.Gone)
        assertTrue(c(200, """{"errors":[{"message":"Authorization: Denied by access control"}]}""") is ApiOutcome.Permanent)
    }

    @Test fun txIdMatchesReferenceSignatureFormat() {
        val kb = app.sweeper.net.XTxId.decodeKeyBytes(app.sweeper.net.XTxId.FALLBACK_KEY_B64)
        assertEquals(48, kb.size)
        val animKey = app.sweeper.net.XTxId.computeAnimationKey(
            kb,
            app.sweeper.net.XTxId.FALLBACK_PATHS,
            app.sweeper.net.XTxId.FALLBACK_INDICES,
        )
        assertEquals("826c6010010000", animKey)
        assertEquals(
            "/i/api/graphql/7_KBXBpG0zBPRN6UCIV0-w/UserTweets",
            app.sweeper.net.XTxId.normalizePath("https://x.com/i/api/graphql/7_KBXBpG0zBPRN6UCIV0-w/UserTweets?variables=%7B%7D"),
        )
        val token = app.sweeper.net.XTxId.generate(
            method = "GET",
            urlOrPath = "https://x.com/i/api/1.1/account/settings.json",
            nowMs = 1_765_000_000_000L,
            randomByte = 42,
        )
        assertEquals(94, token.length)
        val decoded = java.util.Base64.getDecoder().decode("$token==")
        assertEquals(70, decoded.size)
        assertEquals(42, decoded[0].toInt() and 0xFF)
    }
}

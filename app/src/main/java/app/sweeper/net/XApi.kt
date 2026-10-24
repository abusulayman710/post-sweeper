package app.sweeper.net

import app.sweeper.data.AppJson
import app.sweeper.data.OpInfo
import app.sweeper.data.PostEntity
import app.sweeper.data.PostType
import app.sweeper.data.QueryIdCache
import app.sweeper.data.StateStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.net.URLEncoder

sealed interface ApiOutcome<out T> {
    data class Ok<T>(val value: T, val headers: Map<String, String> = emptyMap()) : ApiOutcome<T>
    data class RateLimited(val resetAtMs: Long?, val retryAfterMs: Long?, val msg: String) : ApiOutcome<Nothing>
    data class AuthExpired(val msg: String) : ApiOutcome<Nothing>
    data class Locked(val msg: String) : ApiOutcome<Nothing>
    data class Automation(val msg: String) : ApiOutcome<Nothing>
    /** The thing we wanted to act on does not exist (any more). */
    data class Gone(val msg: String) : ApiOutcome<Nothing>
    /** Retrying will not help. */
    data class Permanent(val msg: String) : ApiOutcome<Nothing>
    data class Transient(val msg: String, val status: Int = 0) : ApiOutcome<Nothing>

    // Internal, resolved inside XApi.call()
    data class EndpointStale(val msg: String) : ApiOutcome<Nothing>
    data class BadRequest(val msg: String) : ApiOutcome<Nothing>
    data class CsrfStale(val msg: String) : ApiOutcome<Nothing>
}

sealed interface IdentityResult {
    data class Ok(val userId: String, val handle: String) : IdentityResult
    data class Error(val message: String) : IdentityResult
}

enum class TimelineKind(val op: String) {
    TWEETS("UserTweets"),
    TWEETS_REPLIES("UserRepliesTimeline"),
    LIKES("Likes"),
}

/**
 * X's own web GraphQL + REST v1.1 API, driven the way the website drives it.
 * Operation ids and feature flags rotate; they are scraped from the live web client (and cached), with a
 * multi-tier fallback architecture so no operation ever gets stuck on a 404.
 */
class XApi(
    private val t: XTransport,
    private val store: StateStore,
) {
    private var cache: QueryIdCache? = null
    private var lastRefresh = 0L
    private val featureOverrides = HashMap<String, MutableSet<String>>()

    val origin: String get() = t.origin

    private suspend fun loadCache(): QueryIdCache {
        val current = cache ?: store.loadQueryIds().also { cache = it }
        if (current.schemaVersion < CURRENT_QID_SCHEMA) {
            val fresh = QueryIdCache(
                fetchedAt = System.currentTimeMillis(),
                ops = SEED.mapValues { (op, id) -> OpInfo(id, SEED_FEATURES[op].orEmpty(), SEED_TOGGLES[op].orEmpty()) },
                bearer = BEARER,
                schemaVersion = CURRENT_QID_SCHEMA,
            )
            cache = fresh
            store.saveQueryIds(fresh)
            return fresh
        }
        return current
    }

    private suspend fun opInfo(op: String): OpInfo? =
        loadCache().ops[op] ?: SEED[op]?.let { OpInfo(it, SEED_FEATURES[op].orEmpty(), SEED_TOGGLES[op].orEmpty()) }

    private suspend fun bearer(): String = loadCache().bearer.ifBlank { BEARER }

    suspend fun cacheInfo(): QueryIdCache = loadCache()

    /** Re-scrape operation ids from the live web client. Returns true if all needed operations were found. */
    suspend fun refreshQueryIds(force: Boolean = false): Boolean {
        val now = System.currentTimeMillis()
        if (!force && now - lastRefresh < 10 * 60_000) return false
        lastRefresh = now
        return try {
            val json = AppJson.parseToJsonElement(t.discover())
            val ops = (json["ops"] as? JsonObject)?.mapNotNull { (name, v) ->
                val id = v["id"].asStr ?: return@mapNotNull null
                name to OpInfo(
                    id,
                    v["fs"].asArr?.mapNotNull { it.asStr }.orEmpty(),
                    v["ft"].asArr?.mapNotNull { it.asStr }.orEmpty(),
                )
            }?.toMap().orEmpty()
            if (ops.isEmpty()) return false
            val merged = loadCache().ops + ops
            val c = QueryIdCache(now, merged, json["bearer"].asStr.orEmpty().ifBlank { BEARER }, schemaVersion = CURRENT_QID_SCHEMA)
            cache = c
            store.saveQueryIds(c)
            REQUIRED.all { merged.containsKey(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
    }

    private fun urlEnc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private suspend fun headers(
        method: String,
        urlOrPath: String,
        contentType: String? = null,
        includeTxId: Boolean = true,
    ): Map<String, String> {
        val h = linkedMapOf(
            "authorization" to "Bearer ${bearer()}",
            "x-twitter-auth-type" to "OAuth2Session",
            "x-twitter-active-user" to "yes",
            "x-twitter-client-language" to "en",
            "accept" to "*/*",
        )
        if (includeTxId) {
            h["x-client-transaction-id"] = XTxId.generate(method, urlOrPath)
        }
        t.csrf()?.let { h["x-csrf-token"] = it }
        if (contentType != null) h["content-type"] = contentType
        return h
    }

    private suspend fun features(op: String): JsonObject {
        val info = opInfo(op)
        val names = LinkedHashSet<String>().apply {
            if (info != null && info.features.isNotEmpty()) {
                addAll(info.features)
            } else {
                addAll(BASE_FEATURES.keys)
            }
            featureOverrides[op]?.let(::addAll)
        }
        return buildJsonObject {
            for (n in names) {
                val forcedFalse = featureOverrides[op]?.contains(n) == true
                put(n, if (forcedFalse) false else BASE_FEATURES[n] ?: false)
            }
        }
    }

    private suspend fun toggles(op: String, vararg extra: String): JsonObject {
        val names = LinkedHashSet<String>().apply {
            addAll(extra)
            opInfo(op)?.toggles?.let(::addAll)
        }
        return buildJsonObject {
            for (n in names) {
                put(n, BASE_TOGGLES[n] ?: false)
            }
        }
    }

    // ---------------------------------------------------------------- transport + classification

    private suspend fun call(
        op: String,
        post: Boolean,
        variables: JsonObject,
        withFeatures: Boolean,
        fieldToggles: JsonObject? = null,
        overrideQueryId: String? = null,
    ): ApiOutcome<JsonElement> {
        var attempt = 0
        var usedFallbackId = false
        while (true) {
            val info = opInfo(op)
            val queryId = overrideQueryId
                ?: (if (usedFallbackId) (FALLBACK_IDS[op] ?: info?.id) else (info?.id ?: SEED[op]))
                ?: return ApiOutcome.Transient("Unknown operation $op - open Diagnostics > Refresh endpoints")
            val base = "${t.origin}/i/api/graphql/$queryId/$op"
            val raw = try {
                if (post) {
                    val body = buildJsonObject {
                        put("variables", variables)
                        if (withFeatures) put("features", features(op))
                        put("queryId", queryId)
                    }
                    t.fetch("POST", base, headers("POST", base, "application/json"), body.toString())
                } else {
                    val sb = StringBuilder(base).append("?variables=").append(urlEnc(variables.toString()))
                    if (withFeatures) sb.append("&features=").append(urlEnc(features(op).toString()))
                    if (fieldToggles != null && fieldToggles.isNotEmpty()) {
                        sb.append("&fieldToggles=").append(urlEnc(fieldToggles.toString()))
                    }
                    val fullUrl = sb.toString()
                    t.fetch("GET", fullUrl, headers("GET", base))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                return ApiOutcome.Transient("Network: ${e.message}")
            } catch (e: Exception) {
                return ApiOutcome.Transient("${e.javaClass.simpleName}: ${e.message}")
            }
            val o = classify(raw)
            when {
                o is ApiOutcome.EndpointStale && !usedFallbackId && FALLBACK_IDS.containsKey(op) && FALLBACK_IDS[op] != queryId -> {
                    usedFallbackId = true
                }
                o is ApiOutcome.EndpointStale && attempt == 0 -> {
                    attempt++
                    usedFallbackId = false
                    // Evict stale cached op so it never poisons future calls
                    val currentOps = loadCache().ops.toMutableMap()
                    currentOps.remove(op)
                    val updatedCache = loadCache().copy(ops = currentOps)
                    cache = updatedCache
                    store.saveQueryIds(updatedCache)
                    if (!refreshQueryIds(force = true)) {
                        val seedId = SEED[op]
                        if (seedId != null && seedId != queryId) {
                            // Proceed to try seed ID directly
                        } else {
                            return ApiOutcome.Transient("Endpoint not found (${raw.status}) and refresh failed")
                        }
                    }
                }
                o is ApiOutcome.EndpointStale -> return ApiOutcome.Transient("Endpoint not found (${raw.status})", raw.status)
                o is ApiOutcome.BadRequest && attempt < 3 -> {
                    val missing = Regex("""cannot be null: ([\w, ]+)""").find(o.msg)
                        ?.groupValues?.get(1)?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
                    if (missing.isEmpty()) return ApiOutcome.Permanent("400: ${o.msg}")
                    featureOverrides.getOrPut(op) { mutableSetOf() }.addAll(missing)
                    attempt++
                }
                o is ApiOutcome.BadRequest -> return ApiOutcome.Permanent("400: ${o.msg}")
                o is ApiOutcome.CsrfStale && attempt == 0 -> {
                    attempt++
                    delay(400)
                }
                o is ApiOutcome.CsrfStale -> return ApiOutcome.AuthExpired(o.msg)
                o is ApiOutcome.Ok -> return ApiOutcome.Ok(o.value, raw.headers)
                else -> return o
            }
        }
    }

    private fun <T> unexpected(json: JsonElement): ApiOutcome<T> = retype(errorOutcome(json) ?: ApiOutcome.Permanent("Unexpected response"))

    /** REST v1.1 form-POST fallback used when a GraphQL mutation endpoint rotates or fails. */
    private suspend fun restPostForm(path: String, formBody: String): ApiOutcome<Unit>? {
        if (!t.origin.startsWith("https://x.com") && !t.origin.startsWith("https://twitter.com")) return null
        val url = "${t.origin}$path"
        return try {
            val raw = t.fetch("POST", url, headers("POST", path, "application/x-www-form-urlencoded"), formBody)
            if (raw.status in 200..299) {
                ApiOutcome.Ok(Unit, raw.headers)
            } else {
                val json = runCatching { AppJson.parseToJsonElement(raw.body) }.getOrNull()
                val code = json["errors"].asArr?.firstOrNull()?.get("code").asInt
                if (raw.status == 404 || code == 144 || code == 139 || code == 34) {
                    ApiOutcome.Gone("Already removed")
                } else {
                    null
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    private fun shouldTryRestFallback(o: ApiOutcome<*>): Boolean = when (o) {
        is ApiOutcome.Ok,
        is ApiOutcome.Gone,
        is ApiOutcome.RateLimited,
        is ApiOutcome.Locked,
        is ApiOutcome.Automation,
        is ApiOutcome.AuthExpired -> false
        else -> true
    }

    // ---------------------------------------------------------------- operations

    suspend fun delete(p: PostEntity): ApiOutcome<Unit> = when {
        p.type == PostType.LIKE -> unfavorite(p.id)
        p.type == PostType.REPOST && p.sourceId != null -> unretweet(p.sourceId)
        else -> deleteTweet(p.id)
    }

    suspend fun deleteTweet(id: String): ApiOutcome<Unit> {
        val vars = buildJsonObject { put("tweet_id", id); put("tweetId", id); put("dark_request", false) }
        val gql = when (val o = call("DeleteTweet", true, vars, false)) {
            is ApiOutcome.Ok -> if (o.value["data"]["delete_tweet"] != null || (o.value["data"] as? JsonObject)?.isNotEmpty() == true) ApiOutcome.Ok(Unit, o.headers) else unexpected(o.value)
            else -> retype(o)
        }
        if (gql is ApiOutcome.Ok || gql is ApiOutcome.Gone) return gql
        val relay = when (val o = call("deleteTweetMutation", true, vars, false, overrideQueryId = "6DqoQrCrai4VWFRVzm9pDg")) {
            is ApiOutcome.Ok -> ApiOutcome.Ok(Unit, o.headers)
            else -> retype(o)
        }
        if (relay is ApiOutcome.Ok || relay is ApiOutcome.Gone) return relay
        if (shouldTryRestFallback(gql)) {
            restPostForm("/i/api/1.1/statuses/destroy/$id.json", "id=${urlEnc(id)}")?.let { return it }
        }
        return gql
    }

    suspend fun unretweet(sourceId: String): ApiOutcome<Unit> {
        val vars = buildJsonObject { put("source_tweet_id", sourceId); put("sourceTweetId", sourceId); put("dark_request", false) }
        val gql = when (val o = call("DeleteRetweet", true, vars, false)) {
            is ApiOutcome.Ok -> if (o.value["data"]["unretweet"] != null || o.value["data"]["delete_retweet"] != null || (o.value["data"] as? JsonObject)?.isNotEmpty() == true) ApiOutcome.Ok(Unit, o.headers) else unexpected(o.value)
            else -> retype(o)
        }
        if (gql is ApiOutcome.Ok || gql is ApiOutcome.Gone) return gql
        val relay = when (val o = call("retweetButtonUnretweetMutation", true, vars, false, overrideQueryId = "TSIYEnua4u_yleM5nrWzFw")) {
            is ApiOutcome.Ok -> ApiOutcome.Ok(Unit, o.headers)
            else -> retype(o)
        }
        if (relay is ApiOutcome.Ok || relay is ApiOutcome.Gone) return relay
        if (shouldTryRestFallback(gql)) {
            restPostForm("/i/api/1.1/statuses/unretweet/$sourceId.json", "id=${urlEnc(sourceId)}")?.let { return it }
        }
        return gql
    }

    suspend fun unfavorite(id: String): ApiOutcome<Unit> {
        val vars = buildJsonObject { put("tweet_id", id); put("tweetId", id); put("dark_request", false) }
        val gql = when (val o = call("UnfavoriteTweet", true, vars, false)) {
            is ApiOutcome.Ok -> if (o.value["data"]["unfavorite_tweet"] != null || (o.value["data"] as? JsonObject)?.isNotEmpty() == true) ApiOutcome.Ok(Unit, o.headers) else unexpected(o.value)
            else -> retype(o)
        }
        if (gql is ApiOutcome.Ok || gql is ApiOutcome.Gone) return gql
        val relay = when (val o = call("likeButtonUnfavoriteMutation", true, vars, false, overrideQueryId = "w8z7av_jrLvW_Ty559qjVg")) {
            is ApiOutcome.Ok -> ApiOutcome.Ok(Unit, o.headers)
            else -> retype(o)
        }
        if (relay is ApiOutcome.Ok || relay is ApiOutcome.Gone) return relay
        if (shouldTryRestFallback(gql)) {
            restPostForm("/i/api/1.1/favorites/destroy.json", "id=${urlEnc(id)}")?.let { return it }
        }
        return gql
    }

    private data class TimelineCandidate(val op: String, val qid: String, val isRelay: Boolean = false)

    suspend fun timeline(kind: TimelineKind, userId: String, cursor: String?, count: Int): ApiOutcome<TimelinePage> {
        val candidates = when (kind) {
            TimelineKind.TWEETS -> listOf(
                TimelineCandidate("UserTweets", opInfo("UserTweets")?.id ?: "7_KBXBpG0zBPRN6UCIV0-w"),
                TimelineCandidate("UserOriginalsTimeline", opInfo("UserOriginalsTimeline")?.id ?: "QLGv_wL7wziqsHhU0K100w"),
                TimelineCandidate("UserTweets", "-CJwD9O5YnU4BdTVqez5Lw", isRelay = true),
                TimelineCandidate("UserOriginalsTimeline", "fwFj4eMJBZSIjYIAs1GOiQ", isRelay = true),
            )
            TimelineKind.TWEETS_REPLIES -> listOf(
                TimelineCandidate("UserRepliesTimeline", opInfo("UserRepliesTimeline")?.id ?: "X9Te-FyiGcoV2qNrnx9w3w"),
                TimelineCandidate("UserTweets", opInfo("UserTweets")?.id ?: "7_KBXBpG0zBPRN6UCIV0-w"),
                TimelineCandidate("UserRepliesTimeline", "mYRShwuEyzQr17R_J0VciQ", isRelay = true),
                TimelineCandidate("UserTweets", "-CJwD9O5YnU4BdTVqez5Lw", isRelay = true),
                TimelineCandidate("UserTweetsAndReplies", opInfo("UserTweetsAndReplies")?.id ?: "bnsYxFljT6y98rFwiGr3fw"),
            )
            TimelineKind.LIKES -> listOf(
                TimelineCandidate("Likes", opInfo("Likes")?.id ?: "mu4us66Q6RRNOywVQhGtRg"),
                TimelineCandidate("likesQuery", "4Mn8P8wvLjcMWazJyeZQYw", isRelay = true),
            )
        }

        val handle = store.account()?.handle.orEmpty().removePrefix("@")
        var lastOutcome: ApiOutcome<TimelinePage>? = null

        for (cand in candidates) {
            val vars = buildJsonObject {
                if (cand.isRelay) {
                    if (cand.op == "likesQuery") {
                        put("userId", userId)
                    } else if (handle.isNotBlank()) {
                        put("screenName", handle)
                        if (cand.op == "UserTweets" || cand.op == "UserOriginalsTimeline") {
                            put("sortByMostLiked", false)
                        }
                    } else {
                        put("userId", userId)
                    }
                    put("count", count)
                    if (cursor != null) put("cursor", cursor)
                    put("__relay_internal__pv__xclientsfeaturesappviewerisloggedinrelayprovider", true)
                    put("__relay_internal__pv__xclientsfeaturestranslationgroktranslatedpostenabledrelayprovider", true)
                    put("__relay_internal__pv__xclientsfeaturestweetcomponentsgrokanalyzepostrelayprovider", true)
                    put("__relay_internal__pv__xclientsfeaturestranslationnoteautotranslationenabledrelayprovider", true)
                    put("__relay_internal__pv__sportspostcontextenabledrelayprovider", true)
                } else {
                    put("userId", userId)
                    put("count", count)
                    put("includePromotedContent", false)
                    when (cand.op) {
                        "UserTweets", "UserOriginalsTimeline" -> {
                            put("withQuickPromoteEligibilityTweetFields", true)
                            put("withVoice", true)
                        }
                        "UserRepliesTimeline", "UserTweetsAndReplies" -> {
                            put("withCommunity", true)
                            put("withVoice", true)
                        }
                        "Likes" -> {
                            put("withClientEventToken", false)
                            put("withBirdwatchNotes", false)
                            put("withVoice", true)
                        }
                    }
                    if (cursor != null) put("cursor", cursor)
                }
            }

            val outcome = if (cand.isRelay) {
                call(cand.op, false, vars, false, null, overrideQueryId = cand.qid)
            } else {
                call(cand.op, false, vars, true, toggles(cand.op), overrideQueryId = cand.qid)
            }

            when (outcome) {
                is ApiOutcome.Ok -> {
                    val page = XParser.parseTimeline(outcome.value)
                    if (page.entryCount == 0 && errorOutcome(outcome.value) != null) {
                        lastOutcome = retype(errorOutcome(outcome.value)!!)
                        continue
                    }
                    return ApiOutcome.Ok(page, outcome.headers)
                }
                is ApiOutcome.EndpointStale -> {
                    lastOutcome = retype(outcome)
                    continue
                }
                else -> {
                    return retype(outcome)
                }
            }
        }

        return lastOutcome ?: ApiOutcome.Transient("All timeline endpoints returned 404")
    }

    suspend fun tweetState(id: String): ApiOutcome<TweetState> {
        val vars = buildJsonObject {
            put("tweetId", id)
            put("withCommunity", false)
            put("includePromotedContent", false)
            put("withVoice", false)
        }
        val ft = toggles("TweetResultByRestId", "withArticleRichContentState", "withArticlePlainText", "withGrokAnalyze", "withDisallowedReplyControls")
        val gql = when (val o = call("TweetResultByRestId", false, vars, true, ft)) {
            is ApiOutcome.Ok -> ApiOutcome.Ok(XParser.parseTweetState(o.value), o.headers)
            is ApiOutcome.Gone -> ApiOutcome.Ok(TweetState.Absent, emptyMap())
            else -> retype(o)
        }
        if (gql is ApiOutcome.Ok) return gql
        val relayVars = buildJsonObject {
            put("restId", id)
            put("withFocalTweet", true)
            put("__relay_internal__pv__xclientsfeaturesappviewerisloggedinrelayprovider", true)
            put("__relay_internal__pv__xclientsfeaturestranslationgroktranslatedpostenabledrelayprovider", true)
            put("__relay_internal__pv__xclientsfeaturestweetcomponentsgrokanalyzepostrelayprovider", true)
            put("__relay_internal__pv__xclientsfeaturestranslationnoteautotranslationenabledrelayprovider", true)
            put("__relay_internal__pv__sportspostcontextenabledrelayprovider", true)
        }
        val relay = when (val o = call("TweetResultByRestId", false, relayVars, false, null, overrideQueryId = "H8WXc5foIw5114seCv13ow")) {
            is ApiOutcome.Ok -> ApiOutcome.Ok(XParser.parseTweetState(o.value), o.headers)
            is ApiOutcome.Gone -> ApiOutcome.Ok(TweetState.Absent, emptyMap())
            else -> retype(o)
        }
        if (relay is ApiOutcome.Ok) return relay
        return gql
    }

    /** Screen name of the logged-in account (REST endpoint, cheap). */
    suspend fun screenName(): String? = try {
        val path = "/i/api/1.1/account/settings.json"
        val raw = t.fetch("GET", "${t.origin}$path", headers("GET", path))
        if (raw.status in 200..299) {
            AppJson.parseToJsonElement(raw.body)["screen_name"].asStr?.takeIf { it.isNotBlank() }
        } else null
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    /** Numeric user id of the logged-in account via verify_credentials, multi/list, viewerQuery, or UserByScreenName. */
    suspend fun verifyUserId(handle: String? = null): String? = when (val r = resolveIdentity(handle)) {
        is IdentityResult.Ok -> r.userId
        is IdentityResult.Error -> null
    }

    /**
     * Resolves both the numeric `userId` and `@handle` of the logged-in user using multiple REST and GraphQL
     * endpoints so cookie login and WebView login never fall back to a broken `@me` placeholder.
     *
     * @param hint Optional user-supplied hint: can be a `@username`, a numeric user ID, or a `twid` (`u%3D12345`).
     */
    suspend fun resolveIdentity(hint: String? = null): IdentityResult {
        val trimmed = hint?.trim().orEmpty()
        val uidFromHint = Regex("""^(?:twid=)?(?:u(?:%3D|=))?(\d{4,})$""", RegexOption.IGNORE_CASE)
            .find(trimmed)?.groupValues?.get(1)
        val handleFromHint = if (uidFromHint == null && trimmed.isNotBlank()) {
            trimmed.removePrefix("@").trim().takeIf { it.matches(Regex("""^[A-Za-z0-9_]{1,30}$""")) }
        } else null

        var uid: String? = uidFromHint ?: t.userIdFromCookie()
        var handle: String? = null
        var authVerified = false
        var lastHttpStatus = 0
        var lastErrorMsg: String? = null

        fun recordError(raw: RawResponse) {
            lastHttpStatus = raw.status
            val j = runCatching { AppJson.parseToJsonElement(raw.body) }.getOrNull()
            val msg = j["errors"].asArr?.firstOrNull()?.get("message").asStr
            if (!msg.isNullOrBlank()) lastErrorMsg = msg
        }

        // 1. GET /i/api/1.1/account/settings.json -> screen_name
        runCatching {
            val path = "/i/api/1.1/account/settings.json"
            val raw = t.fetch("GET", "${t.origin}$path", headers("GET", path))
            if (raw.status in 200..299) {
                val sn = AppJson.parseToJsonElement(raw.body)["screen_name"].asStr?.takeIf { it.isNotBlank() }
                if (sn != null) {
                    handle = sn
                    authVerified = true
                }
            } else {
                recordError(raw)
            }
        }

        // Check if twid cookie was set by settings.json response
        if (uid.isNullOrBlank()) uid = t.userIdFromCookie()

        // 2. GET /i/api/1.1/account/multi/list.json -> users[0].user_id + screen_name
        if (uid.isNullOrBlank() || handle.isNullOrBlank()) {
            runCatching {
                val path = "/i/api/1.1/account/multi/list.json"
                val raw = t.fetch("GET", "${t.origin}$path", headers("GET", path))
                if (raw.status in 200..299) {
                    val root = AppJson.parseToJsonElement(raw.body)
                    val users = root["users"].asArr ?: root.asArr
                    val firstUser = users?.firstOrNull()
                    val uId = firstUser["user_id"].asStr ?: firstUser["id_str"].asStr ?: firstUser["id"].asStr
                    val sn = firstUser["screen_name"].asStr
                    if (!uId.isNullOrBlank()) {
                        uid = uId
                        authVerified = true
                    }
                    if (!sn.isNullOrBlank() && handle.isNullOrBlank()) {
                        handle = sn
                        authVerified = true
                    }
                } else {
                    recordError(raw)
                }
            }
        }

        // 3. GET /i/api/1.1/account/verify_credentials.json -> id_str + screen_name
        if (uid.isNullOrBlank() || handle.isNullOrBlank()) {
            runCatching {
                val path = "/i/api/1.1/account/verify_credentials.json"
                val raw = t.fetch("GET", "${t.origin}$path", headers("GET", path))
                if (raw.status in 200..299) {
                    val root = AppJson.parseToJsonElement(raw.body)
                    val uId = root["id_str"].asStr ?: root["id"].asStr
                    val sn = root["screen_name"].asStr
                    if (!uId.isNullOrBlank()) {
                        uid = uId
                        authVerified = true
                    }
                    if (!sn.isNullOrBlank() && handle.isNullOrBlank()) {
                        handle = sn
                        authVerified = true
                    }
                } else {
                    recordError(raw)
                }
            }
        }

        // 4. GraphQL viewerQuery -> viewer_v2.user_results.result.{rest_id, core.screen_name}
        if (uid.isNullOrBlank() || handle.isNullOrBlank()) {
            runCatching {
                val vars = buildJsonObject { put("withCommunitiesMemberships", true) }
                val ft = toggles("viewerQuery", "withAuxiliaryUserLabels", "withPayments")
                when (val o = call("viewerQuery", false, vars, true, ft)) {
                    is ApiOutcome.Ok -> {
                        val res = o.value["data"]["viewer_v2"]["user_results"]["result"]
                            ?: o.value["data"]["viewer"]["user_results"]["result"]
                        val uId = res["rest_id"].asStr
                        val sn = res["core"]["screen_name"].asStr ?: res["legacy"]["screen_name"].asStr
                        if (!uId.isNullOrBlank()) {
                            uid = uId
                            authVerified = true
                        }
                        if (!sn.isNullOrBlank() && handle.isNullOrBlank()) {
                            handle = sn
                            authVerified = true
                        }
                    }
                    else -> {}
                }
            }
        }

        // 5. If handle is known (from steps 1..4 or handleFromHint) and uid is missing, resolve via UserByScreenName
        val lookupHandle = handle ?: handleFromHint
        if (!lookupHandle.isNullOrBlank() && (uid.isNullOrBlank() || !authVerified)) {
            runCatching {
                val vars = buildJsonObject { put("screen_name", lookupHandle) }
                val ft = toggles("UserByScreenName", "withAuxiliaryUserLabels", "withPayments")
                when (val o = call("UserByScreenName", false, vars, true, ft)) {
                    is ApiOutcome.Ok -> {
                        val res = o.value["data"]["user"]["result"]
                        val uId = res["rest_id"].asStr
                        val sn = res["core"]["screen_name"].asStr ?: res["legacy"]["screen_name"].asStr ?: lookupHandle
                        if (!uId.isNullOrBlank()) {
                            uid = uId
                            handle = sn
                            authVerified = true
                        }
                    }
                    is ApiOutcome.AuthExpired -> {
                        lastHttpStatus = 401
                        lastErrorMsg = o.msg
                    }
                    else -> {}
                }
            }
        }

        // 6. If uid is known and handle is still missing, resolve via UserByRestId
        if (!uid.isNullOrBlank() && handle.isNullOrBlank()) {
            runCatching {
                val vars = buildJsonObject {
                    put("userId", uid)
                    put("withSafetyModeUserFields", true)
                }
                when (val o = call("UserByRestId", false, vars, true, null)) {
                    is ApiOutcome.Ok -> {
                        val res = o.value["data"]["user"]["result"]
                        val sn = res["core"]["screen_name"].asStr ?: res["legacy"]["screen_name"].asStr
                        if (!sn.isNullOrBlank()) {
                            handle = sn
                            authVerified = true
                        }
                    }
                    is ApiOutcome.AuthExpired -> {
                        lastHttpStatus = 401
                        lastErrorMsg = o.msg
                    }
                    else -> {}
                }
            }
        }

        // If user explicitly provided both uid and handle hint, or if we got uid + handle from X:
        val finalUid = uid?.takeIf { it.isNotBlank() }
        val finalHandle = (handle ?: handleFromHint)?.takeIf { it.isNotBlank() }

        if (finalUid != null && finalHandle != null && (authVerified || lastHttpStatus !in setOf(401, 403))) {
            return IdentityResult.Ok(finalUid, finalHandle)
        }

        val reason = when {
            lastHttpStatus in setOf(401, 403) ->
                "X rejected these cookies (HTTP $lastHttpStatus${lastErrorMsg?.let { ": $it" }.orEmpty()}). Copy fresh auth_token and ct0 from a logged-in x.com tab."
            finalUid == null && finalHandle != null ->
                "Connected as @$finalHandle, but could not look up numeric user ID. Enter your numeric User ID (or twid) in the 3rd field."
            finalUid != null && finalHandle == null ->
                "Could not verify session with X${lastErrorMsg?.let { " ($it)" }.orEmpty()}. Enter your @username in the 3rd field or check your cookies."
            else ->
                "Could not connect to X with these cookies${lastErrorMsg?.let { " ($it)" }.orEmpty()}. Check auth_token & ct0, and enter your @username in the 3rd field."
        }
        return IdentityResult.Error(reason)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> retype(o: ApiOutcome<*>): ApiOutcome<T> = o as ApiOutcome<T>

    companion object {

        internal fun classify(raw: RawResponse): ApiOutcome<JsonElement> {
            val h = raw.headers
            val json = runCatching { AppJson.parseToJsonElement(raw.body) }.getOrNull()
            val first = json["errors"].asArr?.firstOrNull()
            val code = first["code"].asInt
            val msg = first["message"].asStr ?: raw.body.take(200)
            val lower = msg.lowercase()
            val reset = h["x-rate-limit-reset"]?.toLongOrNull()?.times(1000)
            val retryAfter = h["retry-after"]?.toLongOrNull()?.times(1000)

            if (raw.status == 429 || code == 88 || lower.contains("rate limit exceeded")) {
                return ApiOutcome.RateLimited(reset, retryAfter, msg)
            }
            return when (raw.status) {
                401 -> ApiOutcome.AuthExpired(msg)
                403 -> when {
                    code == 226 || lower.contains("might be automated") -> ApiOutcome.Automation(msg)
                    code == 326 || code == 64 || lower.contains("temporarily locked") || lower.contains("suspended") -> ApiOutcome.Locked(msg)
                    code == 353 -> ApiOutcome.CsrfStale(msg)
                    code in setOf(32, 89, 215, 239) -> ApiOutcome.AuthExpired(msg)
                    else -> ApiOutcome.Permanent("403: $msg")
                }
                404 -> ApiOutcome.EndpointStale(msg)
                400 -> ApiOutcome.BadRequest(msg)
                in 500..599 -> ApiOutcome.Transient("HTTP ${raw.status}", raw.status)
                in 200..299 -> when {
                    json == null -> ApiOutcome.Transient("Unreadable response")
                    json["data"] != null -> ApiOutcome.Ok(json)
                    else -> errorOutcome(json) ?: ApiOutcome.Transient("Empty response")
                }
                else -> ApiOutcome.Transient("HTTP ${raw.status}", raw.status)
            }
        }

        /** Maps a GraphQL `errors` array (HTTP 200) to an outcome; null if there is none. */
        internal fun errorOutcome(json: JsonElement?): ApiOutcome<Nothing>? {
            val first = json["errors"].asArr?.firstOrNull() ?: return null
            val code = first["code"].asInt
            val msg = first["message"].asStr ?: "error $code"
            val lower = msg.lowercase()
            return when {
                code == 88 || lower.contains("rate limit") -> ApiOutcome.RateLimited(null, null, msg)
                code in setOf(32, 89, 215, 239) -> ApiOutcome.AuthExpired(msg)
                code == 226 || lower.contains("might be automated") -> ApiOutcome.Automation(msg)
                code == 326 || code == 64 || lower.contains("temporarily locked") -> ApiOutcome.Locked(msg)
                code == 144 || lower.contains("no status found") || lower.contains("not found") -> ApiOutcome.Gone(msg)
                else -> ApiOutcome.Permanent(msg)
            }
        }

        const val CURRENT_QID_SCHEMA = 3

        /** Public bearer token that x.com's own web client ships with. Refreshed from the live bundle when found. */
        const val BEARER =
            "AAAAAAAAAAAAAAAAAAAAANRILgAAAAAAnNwIzUejRCOuH5E6I8xnZz4puTs%3D1Zv7ttfk8LF81IUq16cHjhLTvJu4FA33AGWWjCpTnA"

        val REQUIRED = listOf(
            "DeleteTweet",
            "DeleteRetweet",
            "UnfavoriteTweet",
            "UserTweets",
            "UserRepliesTimeline",
            "UserOriginalsTimeline",
            "UserTweetsAndReplies",
            "Likes",
            "TweetResultByRestId",
        )

        /** Current primary query IDs from live x.com / twitter-openapi. */
        val SEED = mapOf(
            "DeleteTweet" to "nxpZCY2K-I6QoFHAHeojFQ",
            "DeleteRetweet" to "ZyZigVsNiFO6v1dEks1eWg",
            "UnfavoriteTweet" to "ZYKSe-w7KEslx3JhSIk5LA",
            "UserTweets" to "7_KBXBpG0zBPRN6UCIV0-w",
            "UserRepliesTimeline" to "X9Te-FyiGcoV2qNrnx9w3w",
            "UserOriginalsTimeline" to "QLGv_wL7wziqsHhU0K100w",
            "UserRepostsTimeline" to "jVw4ob-dDfVzoaz2G6NpVA",
            "UserTweetsAndReplies" to "X9Te-FyiGcoV2qNrnx9w3w",
            "Likes" to "mu4us66Q6RRNOywVQhGtRg",
            "TweetResultByRestId" to "xZfFqfOmKQu_OXekGI59mg",
            "UserByScreenName" to "AMIBMjtxEEATh4z8V9GtRg",
            "UserByRestId" to "mxaYWwt52eAb6OAzYHMVzg",
            "viewerQuery" to "ZXYoRV2xxqxwt4hUF3IzQg",
        )

        /** Secondary fallback query IDs from recent x.com bundles in case an endpoint is mid-rollout. */
        val FALLBACK_IDS = mapOf(
            "DeleteTweet" to "VaenaVgh5q5ih7kvyVjgtg",
            "UserTweets" to "-CJwD9O5YnU4BdTVqez5Lw",
            "UserRepliesTimeline" to "mYRShwuEyzQr17R_J0VciQ",
            "UserOriginalsTimeline" to "fwFj4eMJBZSIjYIAs1GOiQ",
            "UserRepostsTimeline" to "_GFiwCwSAUonMvmAk_vJvA",
            "UserTweetsAndReplies" to "7_KBXBpG0zBPRN6UCIV0-w",
            "Likes" to "4Mn8P8wvLjcMWazJyeZQYw",
            "TweetResultByRestId" to "H8WXc5foIw5114seCv13ow",
            "UserByScreenName" to "yCL-MKd-l5kCSmJJNJjE6A",
            "UserByRestId" to "XIpMDIi_YoVzXeoON-cfAQ",
        )

        private val TIMELINE_FEATURE_LIST = listOf(
            "rweb_video_screen_enabled",
            "rweb_cashtags_enabled",
            "profile_label_improvements_pcf_label_in_post_enabled",
            "responsive_web_profile_redirect_enabled",
            "rweb_tipjar_consumption_enabled",
            "verified_phone_label_enabled",
            "creator_subscriptions_tweet_preview_api_enabled",
            "responsive_web_graphql_timeline_navigation_enabled",
            "premium_content_api_read_enabled",
            "communities_web_enable_tweet_community_results_fetch",
            "c9s_tweet_anatomy_moderator_badge_enabled",
            "responsive_web_grok_analyze_button_fetch_trends_enabled",
            "responsive_web_grok_analyze_post_followups_enabled",
            "rweb_cashtags_composer_attachment_enabled",
            "responsive_web_jetfuel_frame",
            "rweb_sports_post_context_enabled",
            "responsive_web_grok_share_attachment_enabled",
            "responsive_web_grok_annotations_enabled",
            "articles_preview_enabled",
            "responsive_web_edit_tweet_api_enabled",
            "rweb_conversational_replies_downvote_enabled",
            "graphql_is_translatable_rweb_tweet_is_translatable_enabled",
            "view_counts_everywhere_api_enabled",
            "longform_notetweets_consumption_enabled",
            "responsive_web_twitter_article_tweet_consumption_enabled",
            "content_disclosure_indicator_enabled",
            "content_disclosure_ai_generated_indicator_enabled",
            "responsive_web_grok_show_grok_translated_post",
            "responsive_web_grok_analysis_button_from_backend",
            "post_ctas_fetch_enabled",
            "freedom_of_speech_not_reach_fetch_enabled",
            "standardized_nudges_misinfo",
            "tweet_with_visibility_results_prefer_gql_limited_actions_policy_enabled",
            "longform_notetweets_rich_text_read_enabled",
            "longform_notetweets_inline_media_enabled",
            "responsive_web_nested_quote_preview_enabled",
            "responsive_web_grok_image_annotation_enabled",
            "responsive_web_grok_imagine_annotation_enabled",
            "responsive_web_grok_community_note_auto_translation_is_enabled",
            "responsive_web_enhance_cards_enabled",
        )

        val SEED_FEATURES: Map<String, List<String>> = mapOf(
            "UserTweets" to TIMELINE_FEATURE_LIST,
            "UserRepliesTimeline" to TIMELINE_FEATURE_LIST,
            "UserOriginalsTimeline" to TIMELINE_FEATURE_LIST,
            "UserRepostsTimeline" to TIMELINE_FEATURE_LIST,
            "UserTweetsAndReplies" to TIMELINE_FEATURE_LIST,
            "Likes" to TIMELINE_FEATURE_LIST,
            "TweetResultByRestId" to TIMELINE_FEATURE_LIST,
            "UserByScreenName" to listOf(
                "hidden_profile_subscriptions_enabled",
                "profile_label_improvements_pcf_label_in_post_enabled",
                "responsive_web_profile_redirect_enabled",
                "rweb_tipjar_consumption_enabled",
                "verified_phone_label_enabled",
                "subscriptions_verification_info_is_identity_verified_enabled",
                "subscriptions_verification_info_verified_since_enabled",
                "highlights_tweets_tab_ui_enabled",
                "responsive_web_twitter_article_notes_tab_enabled",
                "subscriptions_feature_can_gift_premium",
                "creator_subscriptions_tweet_preview_api_enabled",
                "responsive_web_graphql_timeline_navigation_enabled",
                "responsive_web_graphql_skip_user_profile_image_extensions_enabled",
            ),
            "UserByRestId" to listOf(
                "hidden_profile_subscriptions_enabled",
                "profile_label_improvements_pcf_label_in_post_enabled",
                "responsive_web_profile_redirect_enabled",
                "rweb_tipjar_consumption_enabled",
                "verified_phone_label_enabled",
                "highlights_tweets_tab_ui_enabled",
                "responsive_web_twitter_article_notes_tab_enabled",
                "subscriptions_feature_can_gift_premium",
                "creator_subscriptions_tweet_preview_api_enabled",
                "responsive_web_graphql_timeline_navigation_enabled",
                "responsive_web_graphql_skip_user_profile_image_extensions_enabled",
                "payments_enabled",
            ),
            "viewerQuery" to listOf(
                "rweb_tipjar_consumption_enabled",
                "responsive_web_graphql_exclude_directive_enabled",
                "verified_phone_label_enabled",
                "creator_subscriptions_tweet_preview_api_enabled",
                "responsive_web_graphql_skip_user_profile_image_extensions_enabled",
                "responsive_web_graphql_timeline_navigation_enabled",
                "hidden_profile_subscriptions_enabled",
                "highlights_tweets_tab_ui_enabled",
                "profile_label_improvements_pcf_label_in_post_enabled",
                "responsive_web_profile_redirect_enabled",
                "responsive_web_twitter_article_notes_tab_enabled",
                "subscriptions_feature_can_gift_premium",
                "subscriptions_verification_info_is_identity_verified_enabled",
                "subscriptions_verification_info_verified_since_enabled",
            ),
        )

        val SEED_TOGGLES: Map<String, List<String>> = mapOf(
            "UserTweets" to listOf("withArticlePlainText"),
            "UserRepliesTimeline" to listOf("withArticlePlainText", "withPayments"),
            "UserOriginalsTimeline" to listOf("withArticlePlainText", "withPayments"),
            "UserRepostsTimeline" to listOf("withArticlePlainText", "withPayments"),
            "UserTweetsAndReplies" to listOf("withArticlePlainText", "withPayments"),
            "Likes" to listOf("withArticlePlainText", "withPayments"),
            "TweetResultByRestId" to listOf("withArticleRichContentState", "withArticlePlainText", "withArticleSummaryText", "withArticleVoiceOver", "withPayments"),
            "UserByScreenName" to listOf("withPayments", "withAuxiliaryUserLabels"),
        )

        val BASE_TOGGLES: Map<String, Boolean> = mapOf(
            "withAuxiliaryUserLabels" to true,
            "withArticleRichContentState" to true,
            "withArticlePlainText" to false,
            "withArticleSummaryText" to false,
            "withArticleVoiceOver" to false,
            "withPayments" to false,
            "withGrokAnalyze" to false,
            "withDisallowedReplyControls" to false,
        )

        val BASE_FEATURES: Map<String, Boolean> = linkedMapOf(
            "rweb_tipjar_consumption_enabled" to false,
            "responsive_web_graphql_exclude_directive_enabled" to true,
            "verified_phone_label_enabled" to false,
            "creator_subscriptions_tweet_preview_api_enabled" to true,
            "responsive_web_graphql_timeline_navigation_enabled" to true,
            "responsive_web_graphql_skip_user_profile_image_extensions_enabled" to false,
            "communities_web_enable_tweet_community_results_fetch" to true,
            "c9s_tweet_anatomy_moderator_badge_enabled" to true,
            "articles_preview_enabled" to true,
            "tweetypie_unmention_optimization_enabled" to true,
            "responsive_web_edit_tweet_api_enabled" to true,
            "graphql_is_translatable_rweb_tweet_is_translatable_enabled" to true,
            "view_counts_everywhere_api_enabled" to true,
            "longform_notetweets_consumption_enabled" to true,
            "responsive_web_twitter_article_tweet_consumption_enabled" to true,
            "tweet_awards_web_tipping_enabled" to false,
            "creator_subscriptions_quote_tweet_preview_enabled" to false,
            "freedom_of_speech_not_reach_fetch_enabled" to true,
            "standardized_nudges_misinfo" to true,
            "tweet_with_visibility_results_prefer_gql_limited_actions_policy_enabled" to true,
            "rweb_video_timestamps_enabled" to true,
            "rweb_video_screen_enabled" to false,
            "longform_notetweets_rich_text_read_enabled" to true,
            "longform_notetweets_inline_media_enabled" to false,
            "responsive_web_enhance_cards_enabled" to false,
            "content_disclosure_ai_generated_indicator_enabled" to true,
            "content_disclosure_indicator_enabled" to true,
            "post_ctas_fetch_enabled" to false,
            "premium_content_api_read_enabled" to false,
            "profile_label_improvements_pcf_label_in_post_enabled" to true,
            "responsive_web_grok_analysis_button_from_backend" to true,
            "responsive_web_grok_analyze_button_fetch_trends_enabled" to false,
            "responsive_web_grok_analyze_post_followups_enabled" to true,
            "responsive_web_grok_annotations_enabled" to true,
            "responsive_web_grok_community_note_auto_translation_is_enabled" to true,
            "responsive_web_grok_image_annotation_enabled" to true,
            "responsive_web_grok_imagine_annotation_enabled" to true,
            "responsive_web_grok_share_attachment_enabled" to true,
            "responsive_web_grok_show_grok_translated_post" to true,
            "responsive_web_jetfuel_frame" to true,
            "responsive_web_nested_quote_preview_enabled" to true,
            "responsive_web_profile_redirect_enabled" to true,
            "rweb_cashtags_composer_attachment_enabled" to true,
            "rweb_cashtags_enabled" to true,
            "rweb_conversational_replies_downvote_enabled" to false,
            "rweb_sports_post_context_enabled" to true,
            "hidden_profile_subscriptions_enabled" to true,
            "highlights_tweets_tab_ui_enabled" to true,
            "responsive_web_twitter_article_notes_tab_enabled" to true,
            "subscriptions_feature_can_gift_premium" to false,
            "subscriptions_verification_info_is_identity_verified_enabled" to true,
            "subscriptions_verification_info_verified_since_enabled" to true,
            "payments_enabled" to false,
        )
    }
}

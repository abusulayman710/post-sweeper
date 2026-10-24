package app.sweeper.net

import android.content.Context
import android.os.Build
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebStorage
import app.sweeper.data.AppJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

data class RawResponse(val status: Int, val headers: Map<String, String>, val body: String)

/**
 * Direct HTTP transport for X's web APIs backed by an explicit session cookie jar synced with Android's CookieManager.
 *
 * Why direct HttpURLConnection instead of a hidden loadDataWithBaseURL WebView:
 *  1. Modern Android System WebView treats loadDataWithBaseURL pages as having an opaque SiteForCookies, which drops
 *     SameSite=Lax cookies (`ct0`) on subresource `fetch()` calls.
 *  2. Opening `/i/flow/login` in a WebView sets guest `ct0` cookies on `x.com` that collide with `.x.com` session
 *     cookies and trigger HTTP 403 code 353 ("ct0 header does not match ct0 cookie").
 *  3. Foreground-service runs continue at full speed when the screen is off without WebView timer throttling.
 */
class XTransport(private val ctx: Context) {
    @Volatile
    var origin: String = "https://x.com"

    @Volatile
    private var sessionAuthToken: String = ""

    @Volatile
    private var sessionCt0: String = ""

    @Volatile
    private var sessionTwid: String = ""

    private val txMutex = Mutex()

    private val userAgent: String by lazy {
        val raw = runCatching { WebSettings.getDefaultUserAgent(ctx) }.getOrNull()
            ?: "Mozilla/5.0 (Linux; Android ${Build.VERSION.RELEASE}; Pixel) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
        cleanUserAgent(raw)
    }

    /** Explicitly sets the active X session cookies and wipes any conflicting guest cookies in CookieManager. */
    suspend fun setSessionCookies(authToken: String, ct0: String, userId: String? = null) {
        val cleanAuth = authToken.trim().removePrefix("auth_token=").substringBefore(";").trim()
        val cleanCt0 = ct0.trim().removePrefix("ct0=").substringBefore(";").trim()
        val cleanUid = userId?.trim()?.let { Regex("""(\d{4,})""").find(it)?.groupValues?.get(1) }.orEmpty()

        sessionAuthToken = cleanAuth
        sessionCt0 = cleanCt0
        if (cleanUid.isNotBlank()) {
            sessionTwid = "u%3D$cleanUid"
        }

        withContext(Dispatchers.Main) {
            runCatching {
                val cm = CookieManager.getInstance()
                cm.setAcceptCookie(true)
                cm.removeAllCookies(null)
                cm.flush()
                val targets = linkedSetOf(origin, "https://x.com", "https://twitter.com")
                for (t in targets) {
                    val domainAttr = if (t.contains("x.com")) "; Domain=.x.com" else if (t.contains("twitter.com")) "; Domain=.twitter.com" else ""
                    cm.setCookie(t, "auth_token=$cleanAuth$domainAttr; Path=/; Secure; HttpOnly")
                    cm.setCookie(t, "ct0=$cleanCt0$domainAttr; Path=/; Secure")
                    if (cleanUid.isNotBlank()) {
                        cm.setCookie(t, "twid=u%3D$cleanUid$domainAttr; Path=/; Secure")
                    }
                }
                cm.flush()
            }
        }
    }

    /** Restores in-memory session cookies from persisted Account or CookieManager if not already set. */
    fun restoreSessionIfPresent(authToken: String, ct0: String, userId: String) {
        if (authToken.isNotBlank() && sessionAuthToken.isBlank()) {
            sessionAuthToken = authToken
        }
        if (ct0.isNotBlank() && sessionCt0.isBlank()) {
            sessionCt0 = ct0
        }
        if (userId.isNotBlank() && sessionTwid.isBlank()) {
            sessionTwid = "u%3D$userId"
        }
    }

    fun cookies(): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        val raw = runCatching { CookieManager.getInstance().getCookie(origin) }.getOrNull().orEmpty()
        for (part in raw.split(";")) {
            val i = part.indexOf('=')
            if (i > 0) {
                val k = part.substring(0, i).trim()
                val v = part.substring(i + 1).trim()
                if (k.isNotEmpty() && v.isNotEmpty()) {
                    // Do not let an empty or duplicate guest ct0 overwrite a non-empty one
                    if (k == "ct0" && map.containsKey("ct0")) continue
                    map[k] = v
                }
            }
        }
        if (sessionAuthToken.isNotBlank()) map["auth_token"] = sessionAuthToken
        if (sessionCt0.isNotBlank()) map["ct0"] = sessionCt0
        if (sessionTwid.isNotBlank()) map["twid"] = sessionTwid
        return map
    }

    fun authToken(): String? = cookies()["auth_token"]?.takeIf { it.isNotBlank() }

    fun csrf(): String? = sessionCt0.ifBlank { cookies()["ct0"].orEmpty() }.takeIf { it.isNotBlank() }

    fun hasSession(): Boolean = !authToken().isNullOrBlank()

    /** The numeric user id is in the `twid` cookie as u%3D<id>. */
    fun userIdFromCookie(): String? =
        cookies()["twid"]?.let { Regex("""u(?:%3D|=)(\d+)""").find(it)?.groupValues?.get(1) }

    suspend fun clearSession() {
        sessionAuthToken = ""
        sessionCt0 = ""
        sessionTwid = ""
        withContext(Dispatchers.Main) {
            runCatching {
                val cm = CookieManager.getInstance()
                cm.removeAllCookies(null)
                cm.flush()
                WebStorage.getInstance().deleteAllData()
            }
        }
    }

    private suspend fun ensureTxIdReady() {
        if (XTxId.isInitialized() || !origin.startsWith("https://x.com")) return
        txMutex.withLock {
            if (XTxId.isInitialized() || !origin.startsWith("https://x.com")) return
            withContext(Dispatchers.IO) {
                runCatching {
                    val html = simpleGet("$origin/?lang=en", 8_000)
                    if (!html.isNullOrBlank()) {
                        XTxId.updateFromHtml(html) { scriptUrl -> simpleGet(scriptUrl, 8_000) }
                    }
                }
            }
        }
    }

    private fun simpleGet(urlStr: String, timeoutMs: Int): String? {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", userAgent)
            setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            setRequestProperty("Accept-Language", "en-US,en;q=0.9")
            setRequestProperty("Accept-Encoding", "gzip")
            if (urlStr.startsWith(origin) || urlStr.startsWith("https://x.com") || urlStr.startsWith("https://twitter.com")) {
                val cMap = cookies()
                if (cMap.isNotEmpty()) {
                    val cookieHeader = cMap.entries.joinToString("; ") { "${it.key}=${it.value}" }
                    setRequestProperty("Cookie", cookieHeader)
                }
            }
        }
        return try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            if (stream == null) return null
            val decoded = if ("gzip".equals(conn.contentEncoding, ignoreCase = true)) GZIPInputStream(stream) else stream
            decoded.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    suspend fun fetch(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: String? = null,
        timeoutMs: Long = 45_000,
    ): RawResponse {
        ensureTxIdReady()
        return withContext(Dispatchers.IO) {
            val cookieMap = LinkedHashMap(cookies())
            // Keep x-csrf-token and Cookie ct0 strictly identical
            val csrfHeader = headers["x-csrf-token"] ?: headers["X-Csrf-Token"] ?: cookieMap["ct0"]
            if (!csrfHeader.isNullOrBlank()) {
                cookieMap["ct0"] = csrfHeader
            }
            val cookieHeader = cookieMap.entries.joinToString("; ") { "${it.key}=${it.value}" }

            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = method.uppercase()
                connectTimeout = timeoutMs.toInt().coerceAtLeast(5_000)
                readTimeout = timeoutMs.toInt().coerceAtLeast(5_000)
                instanceFollowRedirects = true
                useCaches = false
                setRequestProperty("User-Agent", userAgent)
                setRequestProperty("Accept", "*/*")
                setRequestProperty("Accept-Language", "en-US,en;q=0.9")
                setRequestProperty("Accept-Encoding", "gzip")
                setRequestProperty("Origin", origin)
                setRequestProperty("Referer", "$origin/")
                if (cookieHeader.isNotBlank()) {
                    setRequestProperty("Cookie", cookieHeader)
                }
                for ((k, v) in headers) {
                    setRequestProperty(k, v)
                }
                if (!csrfHeader.isNullOrBlank()) {
                    setRequestProperty("x-csrf-token", csrfHeader)
                }
            }

            try {
                if (body != null && method.uppercase() != "GET" && method.uppercase() != "HEAD") {
                    conn.doOutput = true
                    val bytes = body.toByteArray(Charsets.UTF_8)
                    conn.setFixedLengthStreamingMode(bytes.size)
                    conn.outputStream.use { it.write(bytes) }
                }

                val status = conn.responseCode
                val respHeaders = LinkedHashMap<String, String>()
                val setCookies = ArrayList<String>()
                for ((rawKey, values) in conn.headerFields) {
                    if (rawKey == null || values == null) continue
                    val lower = rawKey.lowercase()
                    if (lower == "set-cookie") {
                        setCookies.addAll(values)
                    } else {
                        respHeaders[lower] = values.joinToString(", ")
                    }
                }
                if (setCookies.isNotEmpty()) {
                    handleSetCookies(setCookies)
                }

                val rawStream = if (status in 200..299) conn.inputStream else conn.errorStream
                val respBody = if (rawStream != null) {
                    val decoded = if ("gzip".equals(conn.contentEncoding, ignoreCase = true)) GZIPInputStream(rawStream) else rawStream
                    decoded.bufferedReader(Charsets.UTF_8).use { it.readText() }
                } else {
                    ""
                }
                RawResponse(status, respHeaders, respBody)
            } finally {
                conn.disconnect()
            }
        }
    }

    private fun handleSetCookies(setCookies: List<String>) {
        val cm = runCatching { CookieManager.getInstance() }.getOrNull()
        for (sc in setCookies) {
            val firstPair = sc.substringBefore(";").trim()
            val eq = firstPair.indexOf('=')
            if (eq <= 0) continue
            val name = firstPair.substring(0, eq).trim()
            val value = firstPair.substring(eq + 1).trim()
            val isDelete = value.isEmpty() || sc.contains("Max-Age=0", ignoreCase = true) || sc.contains("Max-Age=-", ignoreCase = true)

            if (name == "ct0") {
                if (!isDelete && value.isNotBlank()) {
                    sessionCt0 = value
                    cm?.setCookie(origin, sc)
                }
            } else if (name == "twid") {
                if (!isDelete && value.isNotBlank()) {
                    sessionTwid = value
                    cm?.setCookie(origin, sc)
                }
            } else if (name == "auth_token") {
                if (!isDelete && value.isNotBlank()) {
                    sessionAuthToken = value
                    cm?.setCookie(origin, sc)
                }
            } else if (!isDelete) {
                cm?.setCookie(origin, sc)
            }
        }
    }

    /** Discovers live GraphQL queryIds and feature flags from x.com bundles + twitter-openapi fallback. */
    suspend fun discover(timeoutMs: Long = 45_000): String = withContext(Dispatchers.IO) {
        val want = listOf(
            "DeleteTweet",
            "DeleteRetweet",
            "UnfavoriteTweet",
            "UserTweets",
            "UserRepliesTimeline",
            "UserOriginalsTimeline",
            "UserRepostsTimeline",
            "UserTweetsAndReplies",
            "Likes",
            "TweetResultByRestId",
            "UserByScreenName",
            "UserByRestId",
            "viewerQuery",
        )
        val ops = LinkedHashMap<String, Triple<String, List<String>, List<String>>>()
        var bearer = ""

        fun scanJs(txt: String) {
            val reLegacy = Regex("""queryId:"([A-Za-z0-9_-]+)",operationName:"(\w+)"""")
            for (m in reLegacy.findAll(txt)) {
                val qid = m.groupValues[1]
                val opName = m.groupValues[2]
                val tailEnd = (m.range.last + 1 + 3000).coerceAtMost(txt.length)
                var tail = txt.substring(m.range.last + 1, tailEnd)
                val cut = tail.indexOf("queryId:")
                if (cut > 0) tail = tail.substring(0, cut)
                val fs = Regex("""featureSwitches:\[([^\]]*)]""").find(tail)?.groupValues?.get(1)
                    ?.replace("\"", "")?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
                val ft = Regex("""fieldToggles:\[([^\]]*)]""").find(tail)?.groupValues?.get(1)
                    ?.replace("\"", "")?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
                ops[opName] = Triple(qid, fs, ft)
            }
            val reRelay = Regex("""params:\{id:[`"]([A-Za-z0-9_-]+)[`"],(?:metadata:\{[^}]*\},)?name:[`"](\w+)[`"]""")
            for (m in reRelay.findAll(txt)) {
                val qid = m.groupValues[1]
                val opName = m.groupValues[2]
                if (!ops.containsKey(opName)) {
                    ops[opName] = Triple(qid, emptyList(), emptyList())
                }
            }
            if (bearer.isEmpty()) {
                Regex("""AAAAAAAAAAAAAAAAAAAAA[A-Za-z0-9%]+""").find(txt)?.let { bearer = it.value }
            }
        }

        val perReqTimeout = (timeoutMs / 4).toInt().coerceIn(5_000, 15_000)

        runCatching {
            val html = simpleGet("$origin/?lang=en", perReqTimeout) ?: simpleGet("$origin/home", perReqTimeout)
            if (!html.isNullOrBlank()) {
                if (origin.startsWith("https://x.com")) {
                    XTxId.updateFromHtml(html) { scriptUrl -> simpleGet(scriptUrl, perReqTimeout) }
                }
                val urls = Regex("""https://abs\.twimg\.com/(?:responsive-web/client-web|x-web/x-web)[^"'\s)]*\.js""")
                    .findAll(html).map { it.value }.distinct().toList()
                for (u in urls) {
                    val js = runCatching { simpleGet(u, perReqTimeout) }.getOrNull() ?: continue
                    scanJs(js)
                }
            }
        }

        if (!want.all { ops.containsKey(it) }) {
            runCatching {
                val ndTxt = simpleGet(
                    "https://raw.githubusercontent.com/fa0311/twitter_api_safe_relay/main/packages/server/requests.ndjson",
                    perReqTimeout,
                )
                if (!ndTxt.isNullOrBlank()) {
                    val lineRe = Regex("""/graphql/([A-Za-z0-9_-]+)/(\w+)""")
                    for (line in ndTxt.lineSequence()) {
                        val m = lineRe.find(line) ?: continue
                        val qid = m.groupValues[1]
                        val name = m.groupValues[2]
                        if (name in want && !ops.containsKey(name)) {
                            ops[name] = Triple(qid, emptyList(), emptyList())
                        }
                    }
                }
            }
        }

        if (!want.all { ops.containsKey(it) }) {
            runCatching {
                val phTxt = simpleGet(
                    "https://raw.githubusercontent.com/fa0311/twitter-openapi/refs/heads/main/src/config/placeholder.json",
                    perReqTimeout,
                )
                if (!phTxt.isNullOrBlank()) {
                    val ph = AppJson.parseToJsonElement(phTxt) as? JsonObject
                    if (ph != null) {
                        for (w in want) {
                            if (ops.containsKey(w)) continue
                            val entry = ph[w] as? JsonObject ?: continue
                            val qid = (entry["queryId"] as? JsonPrimitive)?.content ?: continue
                            val fs = (entry["features"] as? JsonObject)?.keys?.toList().orEmpty()
                            val ft = (entry["fieldToggles"] as? JsonObject)?.keys?.toList().orEmpty()
                            ops[w] = Triple(qid, fs, ft)
                        }
                    }
                }
            }
        }

        val outJson = buildJsonObject {
            put(
                "ops",
                buildJsonObject {
                    for ((name, triple) in ops) {
                        put(
                            name,
                            buildJsonObject {
                                put("id", triple.first)
                                put("fs", buildJsonArray { triple.second.forEach { add(JsonPrimitive(it)) } })
                                put("ft", buildJsonArray { triple.third.forEach { add(JsonPrimitive(it)) } })
                            },
                        )
                    }
                },
            )
            put("bearer", bearer)
        }
        outJson.toString()
    }

    companion object {
        /** A WebView UA says "; wv)" and "Version/4.0" which no real Chrome does. Look like Chrome. */
        fun cleanUserAgent(ua: String): String =
            ua.replace(Regex("""\s*;\s*wv(?=\))"""), "")
                .replace("; wv", "")
                .replace(Regex("""\s*Version/\d+\.\d+"""), "")

        /**
         * Pure JS runtime polyfills for older Android System WebViews.
         * Never mutates `document.head` or `document.body` so React 19's `hydrateRoot(document, ...)` on x.com
         * succeeds without hydration mismatches.
         */
        const val POLYFILL_JS = """
(function(){
  if (window.__swPolyfilled) return;
  window.__swPolyfilled = true;
  if (typeof Error.isError !== 'function') {
    Error.isError = function(e) {
      return e instanceof Error || (!!e && typeof e === 'object' && (e.name === 'Error' || e.name === 'DOMException' || typeof e.message === 'string' && typeof e.stack === 'string'));
    };
  }
  if (typeof URL.parse !== 'function') {
    URL.parse = function(u, b) {
      try { return b !== undefined ? new URL(u, b) : new URL(u); } catch (_) { return null; }
    };
  }
  if (typeof URL.canParse !== 'function') {
    URL.canParse = function(u, b) {
      try { b !== undefined ? new URL(u, b) : new URL(u); return true; } catch (_) { return false; }
    };
  }
  if (typeof Promise.withResolvers !== 'function') {
    Promise.withResolvers = function() {
      var resolve, reject;
      var promise = new Promise(function(res, rej) { resolve = res; reject = rej; });
      return { promise: promise, resolve: resolve, reject: reject };
    };
  }
  if (typeof Object.hasOwn !== 'function') {
    Object.hasOwn = function(obj, prop) {
      return Object.prototype.hasOwnProperty.call(obj, prop);
    };
  }
  try { Object.defineProperty(navigator, 'webdriver', { get: function() { return false; } }); } catch (_) {}
})();
"""
    }
}

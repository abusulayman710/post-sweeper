package app.sweeper.net

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

@Serializable
data class ReleaseInfo(
    val tagName: String,
    val name: String,
    val body: String,
    val htmlUrl: String,
    val apkDownloadUrl: String,
)

sealed interface UpdateStatus {
    data class Available(val info: ReleaseInfo) : UpdateStatus
    data object UpToDate : UpdateStatus
    data class Error(val error: String) : UpdateStatus
}

object UpdateChecker {
    const val REPO_API_URL = "https://api.github.com/repos/abusulayman710/post-sweeper/releases/latest"
    private val jsonParser = Json { ignoreUnknownKeys = true }

    fun check(currentVersion: String): UpdateStatus {
        return try {
            val conn = (URL(REPO_API_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10_000
                readTimeout = 10_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "PostSweeper-App/$currentVersion")
                setRequestProperty("Accept", "application/vnd.github.v3+json")
                setRequestProperty("Accept-Encoding", "gzip")
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                return UpdateStatus.Error("GitHub API returned HTTP $code")
            }
            val stream = if ("gzip".equals(conn.contentEncoding, ignoreCase = true)) {
                GZIPInputStream(conn.inputStream)
            } else {
                conn.inputStream
            }
            val text = BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
            val json = jsonParser.parseToJsonElement(text).jsonObject

            val tagName = json["tag_name"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val name = json["name"]?.jsonPrimitive?.contentOrNull.orEmpty().ifBlank { tagName }
            val body = json["body"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val htmlUrl = json["html_url"]?.jsonPrimitive?.contentOrNull.orEmpty()

            var apkUrl: String? = null
            val assets = json["assets"]?.jsonArray
            if (assets != null) {
                for (a in assets) {
                    val obj = a.jsonObject
                    val downloadUrl = obj["browser_download_url"]?.jsonPrimitive?.contentOrNull
                    val assetName = obj["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    if (assetName.endsWith(".apk", ignoreCase = true) && downloadUrl != null) {
                        apkUrl = downloadUrl
                        break
                    }
                }
            }

            val remoteVer = tagName.removePrefix("v").trim()
            val localVer = currentVersion.removePrefix("v").trim()

            if (isNewer(remoteVer, localVer)) {
                UpdateStatus.Available(
                    ReleaseInfo(
                        tagName = tagName,
                        name = name,
                        body = body,
                        htmlUrl = htmlUrl,
                        apkDownloadUrl = apkUrl ?: htmlUrl,
                    )
                )
            } else {
                UpdateStatus.UpToDate
            }
        } catch (e: Exception) {
            UpdateStatus.Error(e.message ?: "Failed to check for updates")
        }
    }

    fun isNewer(remote: String, local: String): Boolean {
        if (remote.isBlank() || local.isBlank()) return false
        val rParts = remote.split('-')[0].split('.').mapNotNull { it.toIntOrNull() }
        val lParts = local.split('-')[0].split('.').mapNotNull { it.toIntOrNull() }
        val maxLen = maxOf(rParts.size, lParts.size)
        for (i in 0 until maxLen) {
            val r = rParts.getOrElse(i) { 0 }
            val l = lParts.getOrElse(i) { 0 }
            if (r > l) return true
            if (r < l) return false
        }
        return false
    }
}

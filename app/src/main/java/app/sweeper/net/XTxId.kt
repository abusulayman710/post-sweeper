package app.sweeper.net

import java.math.BigDecimal
import java.math.RoundingMode
import java.security.MessageDigest
import java.util.Base64
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * Generates X's `x-client-transaction-id` header using the same algorithm as x.com's `ondemand.s` / `sign.o` bundle:
 *  - 48-byte `twitter-site-verification` key from `<meta name="twitter-site-verification" content="...">`
 *  - 4 `<svg id="loading-x-anim-0..3">` second `<path d="...">` curve tables
 *  - `rowIndex` and `keyByteIndices` from `sign.o-*.js` / `ondemand.s.*.js`
 *  - `SHA-256("$method!$path!$timeNow" + "obfiowerehiring" + animationKey)`
 *  - 70-byte payload (`maskByte` + XORed `keyBytes[48] + timeBytes[4] + sha256[16] + [3]`) base64-encoded without `=`.
 */
object XTxId {
    private const val DEFAULT_KEYWORD = "obfiowerehiring"
    private const val ADDITIONAL_RANDOM_NUMBER = 3
    private const val EPOCH_OFFSET_SECONDS = 1682924400L

    internal const val FALLBACK_KEY_B64 = "nuie+f5z5kRTsobD6IWTpYEr7gJ+XjNMktpBadp4DNAg2JUYR/BI7oIGWl1qqIfd"
    internal val FALLBACK_INDICES = Indices(rowIndex = 25, keyByteIndices = listOf(21, 22, 32))
    internal val FALLBACK_PATHS = listOf(
        "M 10,30 C 203,108 249,42 14,234 h 130 s 162,64 211,94 C 112,82 48,17 228,235 h 75 s 189,153 30,83 C 228,175 222,238 137,213 h 190 s 224,244 187,31 C 122,4 101,166 145,92 h 125 s 101,75 11,233 C 90,127 162,3 53,126 h 184 s 238,172 132,49 C 86,56 151,90 34,57 h 85 s 119,202 52,34 C 20,224 6,29 216,134 h 33 s 158,9 175,239 C 114,23 250,182 28,105 h 218 s 210,70 150,193 C 203,92 23,108 185,228 h 151 s 113,182 2,121 C 226,133 125,172 98,178 h 71 s 82,88 243,130 C 56,197 69,188 76,243 h 4 s 141,197 138,14 C 7,22 246,148 252,236 h 120 s 40,223 107,45 C 178,137 228,241 54,241 h 217 s 119,210 112,33 C 186,245 74,188 112,108 h 108 s 91,53 138,178 C 84,254 163,20 132,127 h 205 s 14,198 120,115 C 161,128 125,82 118,89 h 6 s 14,188 154,154",
        "M 10,30 C 106,97 69,219 105,155 h 86 s 141,160 147,227 C 64,63 102,214 40,136 h 173 s 151,188 11,86 C 132,68 3,105 159,97 h 200 s 100,189 153,211 C 45,121 139,228 255,55 h 147 s 134,145 146,35 C 17,85 242,136 212,118 h 142 s 41,113 243,170 C 210,198 137,157 91,101 h 57 s 12,253 214,118 C 82,0 117,218 250,65 h 87 s 90,191 150,57 C 165,240 114,85 241,174 h 1 s 175,233 42,175 C 129,141 127,140 126,2 h 42 s 235,19 247,132 C 75,133 235,159 144,86 h 75 s 223,167 185,181 C 92,107 212,246 229,230 h 235 s 218,33 195,113 C 169,208 102,10 63,188 h 152 s 60,251 144,95 C 250,209 221,102 233,164 h 156 s 202,200 108,215 C 29,126 121,154 248,103 h 223 s 111,161 162,110 C 104,180 152,75 190,15 h 162 s 93,184 5,225 C 8,247 115,61 151,136 h 134 s 167,70 248,236",
        "M 10,30 C 196,212 77,210 196,13 h 214 s 244,217 23,63 C 106,62 210,128 28,169 h 176 s 173,29 44,154 C 40,94 187,170 99,145 h 48 s 143,119 193,10 C 158,228 218,198 70,76 h 240 s 86,95 50,135 C 112,75 197,243 30,207 h 73 s 102,162 95,100 C 174,57 235,173 213,21 h 91 s 71,50 199,49 C 88,183 195,115 250,119 h 57 s 244,81 112,151 C 180,241 104,147 183,13 h 143 s 31,219 29,108 C 221,121 45,171 212,201 h 101 s 248,10 85,221 C 148,33 253,39 186,245 h 71 s 227,107 132,39 C 153,139 63,232 37,112 h 186 s 174,202 136,12 C 22,98 37,12 51,213 h 89 s 141,149 203,0 C 103,12 186,49 134,195 h 25 s 179,215 36,61 C 174,234 113,254 91,255 h 159 s 107,219 212,51 C 113,2 93,43 182,217 h 175 s 128,28 158,61 C 15,35 126,94 219,145 h 251 s 32,76 138,71",
        "M 10,30 C 30,93 173,16 32,140 h 129 s 0,96 175,181 C 88,87 123,8 108,198 h 50 s 81,249 253,46 C 211,23 212,243 62,183 h 167 s 232,38 44,229 C 181,18 51,60 251,10 h 207 s 66,79 91,198 C 194,164 110,126 222,71 h 21 s 119,204 71,180 C 241,227 206,34 247,62 h 37 s 73,124 66,251 C 70,168 128,60 100,155 h 207 s 83,50 108,109 C 101,183 136,230 103,9 h 198 s 134,11 156,221 C 140,54 245,134 0,46 h 196 s 174,69 211,82 C 137,95 253,62 173,235 h 203 s 236,126 210,30 C 130,108 96,77 0,212 h 219 s 242,109 160,36 C 209,140 24,114 78,203 h 139 s 167,103 124,42 C 158,84 226,91 231,229 h 215 s 243,77 148,182 C 93,18 233,197 106,179 h 218 s 52,200 130,190 C 13,200 123,163 42,42 h 182 s 114,206 218,237 C 17,162 35,74 51,182 h 240 s 31,89 185,25",
    )

    data class Indices(val rowIndex: Int, val keyByteIndices: List<Int>)
    data class TxState(val keyBytes: IntArray, val animationKey: String, val updatedAt: Long)

    @Volatile
    private var state: TxState = run {
        val kb = decodeKeyBytes(FALLBACK_KEY_B64)
        val ak = computeAnimationKey(kb, FALLBACK_PATHS, FALLBACK_INDICES)
        TxState(kb, ak, 0L)
    }

    fun isInitialized(): Boolean = state.updatedAt > 0L

    fun updateFromHtml(html: String, fetchText: (String) -> String?) {
        val keyB64 = extractVerificationKey(html) ?: return
        val paths = extractSvgPaths(html).takeIf { it.size >= 4 } ?: FALLBACK_PATHS
        val scriptUrl = extractSignScriptUrl(html)
        val indices = scriptUrl?.let { url ->
            runCatching { fetchText(url)?.let(::extractIndices) }.getOrNull()
        } ?: FALLBACK_INDICES
        val kb = decodeKeyBytes(keyB64)
        if (kb.isEmpty()) return
        val ak = runCatching { computeAnimationKey(kb, paths, indices) }.getOrDefault(state.animationKey)
        state = TxState(kb, ak, System.currentTimeMillis())
    }

    fun generate(
        method: String,
        urlOrPath: String,
        nowMs: Long = System.currentTimeMillis(),
        randomByte: Int = Random.nextInt(256),
    ): String {
        val s = state
        val path = normalizePath(urlOrPath)
        val timeNow = ((nowMs / 1000L) - EPOCH_OFFSET_SECONDS).coerceAtLeast(0L).toInt()
        val timeBytes = intArrayOf(
            timeNow and 0xFF,
            (timeNow ushr 8) and 0xFF,
            (timeNow ushr 16) and 0xFF,
            (timeNow ushr 24) and 0xFF,
        )
        val payload = "${method.uppercase()}!$path!$timeNow$DEFAULT_KEYWORD${s.animationKey}"
        val digest = MessageDigest.getInstance("SHA-256").digest(payload.toByteArray(Charsets.UTF_8))
        val hashBytes = IntArray(16) { digest[it].toInt() and 0xFF }

        val mask = randomByte and 0xFF
        val out = ByteArray(1 + s.keyBytes.size + timeBytes.size + hashBytes.size + 1)
        var pos = 0
        out[pos++] = mask.toByte()
        for (b in s.keyBytes) out[pos++] = (b xor mask).toByte()
        for (b in timeBytes) out[pos++] = (b xor mask).toByte()
        for (b in hashBytes) out[pos++] = (b xor mask).toByte()
        out[pos] = (ADDITIONAL_RANDOM_NUMBER xor mask).toByte()

        return Base64.getEncoder().encodeToString(out).trimEnd('=')
    }

    internal fun normalizePath(urlOrPath: String): String {
        val withoutOrigin = if (urlOrPath.startsWith("http://") || urlOrPath.startsWith("https://")) {
            val slash = urlOrPath.indexOf('/', urlOrPath.indexOf("://") + 3)
            if (slash >= 0) urlOrPath.substring(slash) else "/"
        } else {
            urlOrPath
        }
        return withoutOrigin.substringBefore('?').ifEmpty { "/" }
    }

    internal fun decodeKeyBytes(b64: String): IntArray =
        runCatching { Base64.getDecoder().decode(b64).map { it.toInt() and 0xFF }.toIntArray() }.getOrDefault(IntArray(0))

    internal fun extractVerificationKey(html: String): String? {
        val m1 = Regex("""<meta[^>]*name=["']twitter-site-verification["'][^>]*content=["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(html)
        if (m1 != null) return m1.groupValues[1]
        val m2 = Regex("""<meta[^>]*content=["']([^"']+)["'][^>]*name=["']twitter-site-verification["']""", RegexOption.IGNORE_CASE).find(html)
        return m2?.groupValues?.get(1)
    }

    internal fun extractSvgPaths(html: String): List<String> {
        val svgRe = Regex("""<svg[^>]*id=["']loading-x-anim-(\d)["'][\s\S]*?</svg>""", RegexOption.IGNORE_CASE)
        val pathRe = Regex("""<path[^>]*\sd=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        val byIndex = HashMap<Int, String>()
        for (m in svgRe.findAll(html)) {
            val idx = m.groupValues[1].toIntOrNull() ?: continue
            val paths = pathRe.findAll(m.value).map { it.groupValues[1] }.toList()
            val target = paths.getOrNull(1) ?: paths.getOrNull(0)
            if (!target.isNullOrBlank()) byIndex[idx] = target
        }
        return (0..3).mapNotNull { byIndex[it] }
    }

    internal fun extractSignScriptUrl(html: String): String? {
        Regex("""https://abs\.twimg\.com/[^"'\s>]*sign\.[a-z0-9]+-[A-Za-z0-9_-]+\.js""").find(html)?.let { return it.value }
        Regex("""/x-web/x-web/assets/sign\.[a-z0-9]+-[A-Za-z0-9_-]+\.js""").find(html)?.let {
            return "https://abs.twimg.com${it.value}"
        }
        val onDemand = Regex("""['"]ondemand\.s['"]\s*:\s*['"]([a-f0-9]+)['"]""").find(html)?.groupValues?.get(1)
        if (onDemand != null) {
            return "https://abs.twimg.com/responsive-web/client-web/ondemand.s.${onDemand}a.js"
        }
        return null
    }

    internal fun extractIndices(jsText: String): Indices? {
        val matches = Regex("""\(\w\[(\d{1,2})\],\s*16\)""").findAll(jsText)
            .mapNotNull { it.groupValues[1].toIntOrNull() }
            .toList()
        if (matches.size < 2) return null
        return Indices(rowIndex = matches.first(), keyByteIndices = matches.drop(1))
    }

    internal fun computeAnimationKey(keyBytes: IntArray, svgPaths: List<String>, indices: Indices): String {
        val rowIndex = keyBytes[indices.rowIndex] % 16
        var frameTime = indices.keyByteIndices
            .map { keyBytes[it] % 16 }
            .fold(1) { a, b -> a * b }
        frameTime = (jsRound(frameTime / 10.0) * 10.0).roundToInt()

        val grid = parse2DArray(keyBytes, svgPaths)
        val frameRow = grid.getOrElse(rowIndex) { grid.first() }
        val targetTime = frameTime.toDouble() / 4096.0
        return animate(frameRow, targetTime)
    }

    private fun parse2DArray(keyBytes: IntArray, svgPaths: List<String>): List<List<Int>> {
        val path = svgPaths[keyBytes[5] % 4]
        val body = if (path.length > 9) path.substring(9) else path
        return body.split('C').map { seg ->
            seg.replace(Regex("""[^\d]+"""), " ").trim().split(Regex("""\s+""")).mapNotNull { it.toIntOrNull() }
        }
    }

    private fun jsRound(num: Double): Double {
        val x = floor(num)
        return if (num - x >= 0.5) x + ceil(num - x) else x
    }

    private fun isOdd(num: Int): Double = if (num % 2 != 0) -1.0 else 0.0

    private fun round2(v: Double): Double =
        BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP).toDouble()

    private fun solve(value: Double, minVal: Double, maxVal: Double, rounding: Boolean): Double {
        val result = value * (maxVal - minVal) / 255.0 + minVal
        return if (rounding) floor(result) else round2(result)
    }

    internal fun floatToHex(input: Double): String {
        var x = input
        val result = ArrayList<String>()
        var quotient = x.toLong()
        var fraction = x - quotient.toDouble()
        while (quotient > 0L) {
            val q = (x / 16.0).toLong()
            val r = (x - (q.toDouble() * 16.0)).toInt()
            result.add(0, if (r > 9) (r + 55).toChar().toString() else r.toString())
            x = q.toDouble()
            quotient = x.toLong()
        }
        if (fraction == 0.0) return result.joinToString("")
        result.add(".")
        var guard = 0
        while (fraction > 0.0 && guard++ < 32) {
            fraction *= 16.0
            val integer = fraction.toInt()
            fraction -= integer.toDouble()
            result.add(if (integer > 9) (integer + 55).toChar().toString() else integer.toString())
        }
        return result.joinToString("")
    }

    private class Cubic(private val curves: List<Double>) {
        fun getValue(time: Double): Double {
            var startGradient = 0.0
            var endGradient = 0.0
            var start = 0.0
            var mid = 0.0
            var end = 1.0
            if (time <= 0.0) {
                if (curves[0] > 0.0) startGradient = curves[1] / curves[0]
                else if (curves[1] == 0.0 && curves[2] > 0.0) startGradient = curves[3] / curves[2]
                return startGradient * time
            }
            if (time >= 1.0) {
                if (curves[2] < 1.0) endGradient = (curves[3] - 1.0) / (curves[2] - 1.0)
                else if (curves[2] == 1.0 && curves[0] < 1.0) endGradient = (curves[1] - 1.0) / (curves[0] - 1.0)
                return 1.0 + endGradient * (time - 1.0)
            }
            while (start < end) {
                mid = (start + end) / 2.0
                val xEst = calculate(curves[0], curves[2], mid)
                if (abs(time - xEst) < 0.00001) return calculate(curves[1], curves[3], mid)
                if (xEst < time) start = mid else end = mid
            }
            return calculate(curves[1], curves[3], mid)
        }

        private fun calculate(a: Double, b: Double, m: Double): Double =
            3.0 * a * (1.0 - m) * (1.0 - m) * m + 3.0 * b * (1.0 - m) * m * m + m * m * m
    }

    private fun interpolate(fromList: List<Double>, toList: List<Double>, f: Double): List<Double> =
        fromList.indices.map { i -> fromList[i] * (1.0 - f) + toList[i] * f }

    private fun rotationMatrix(deg: Double): List<Double> {
        val rad = deg * PI / 180.0
        val c = cos(rad)
        val s = sin(rad)
        return listOf(c, -s, s, c, 0.0, 0.0)
    }

    private fun animate(frames: List<Int>, targetTime: Double): String {
        val fromColor = frames.take(3).map { it.toDouble() } + 1.0
        val toColor = frames.subList(3, 6).map { it.toDouble() } + 1.0
        val fromRotation = listOf(0.0)
        val toRotation = listOf(solve(frames[6].toDouble(), 60.0, 360.0, true))
        val remaining = frames.drop(7)
        val curves = remaining.mapIndexed { idx, item -> solve(item.toDouble(), isOdd(idx), 1.0, false) }
        val cubic = Cubic(curves)
        val v = cubic.getValue(targetTime)
        val color = interpolate(fromColor, toColor, v).map { if (it > 0.0) it else 0.0 }
        val rotation = interpolate(fromRotation, toRotation, v)
        val matrix = rotationMatrix(rotation[0])

        val strArr = ArrayList<String>()
        for (i in 0 until 3) {
            strArr.add(color[i].roundToInt().toString(16))
        }
        for (value in matrix) {
            var rounded = round2(abs(value))
            if (rounded < 0.0) rounded = -rounded
            val hexValue = floatToHex(rounded)
            when {
                hexValue.startsWith(".") -> strArr.add(("0$hexValue").lowercase())
                hexValue.isEmpty() -> strArr.add("0")
                else -> strArr.add(hexValue.lowercase())
            }
        }
        strArr.add("0")
        strArr.add("0")
        return strArr.joinToString("").replace(Regex("""[.-]"""), "")
    }
}

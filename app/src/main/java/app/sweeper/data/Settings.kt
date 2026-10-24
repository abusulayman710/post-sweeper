package app.sweeper.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

val AppJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    isLenient = true
}

enum class TriState { ANY, YES, NO }
enum class DateMode { ALL, OLDER_THAN, RANGE }
enum class DeleteOrder { OLDEST_FIRST, NEWEST_FIRST, RANDOM }
enum class SpeedProfile { GENTLE, BALANCED, FAST, CUSTOM }
enum class ScheduleMode { ALWAYS, WINDOW }
enum class ThemeMode { SYSTEM, LIGHT, DARK }
enum class ScanMode { SCAN_ONLY, SCAN_AND_DELETE }

@Serializable
data class TypeRule(val enabled: Boolean = true, val olderThanDays: Int = 0)

/** What to delete. Everything here narrows the set; a post is deleted only if it passes every rule. */
@Serializable
data class FilterConfig(
    val posts: TypeRule = TypeRule(true),
    val replies: TypeRule = TypeRule(true),
    val reposts: TypeRule = TypeRule(true),
    val quotes: TypeRule = TypeRule(true),
    val likes: TypeRule = TypeRule(false),

    val dateMode: DateMode = DateMode.OLDER_THAN,
    val olderThanDays: Int = 30,
    val afterMs: Long = 0,
    val beforeMs: Long = 0,
    /** Keep the newest N items of each type (0 = off). */
    val keepNewest: Int = 0,

    /** Words/phrases, one per line or comma separated. A post is deleted only if it matches (empty = any). */
    val includeWords: String = "",
    val includeAll: Boolean = false,
    /** Posts containing any of these are kept. */
    val excludeWords: String = "",
    val useRegex: Boolean = false,
    val caseSensitive: Boolean = false,
    val wholeWord: Boolean = false,

    /** Keep anything at or above these numbers (0 = off). */
    val keepLikes: Int = 0,
    val keepReposts: Int = 0,
    val keepReplies: Int = 0,
    val keepViews: Long = 0,

    val hasMedia: TriState = TriState.ANY,
    val hasLinks: TriState = TriState.ANY,
    val hasHashtags: TriState = TriState.ANY,
    val hasMentions: TriState = TriState.ANY,
    val selfReply: TriState = TriState.ANY,

    val languages: String = "",
    /** Replies: only those sent to these handles (empty = any). */
    val replyToHandles: String = "",
    /** Likes/reposts/quotes: only those whose original author is in this list (empty = any). */
    val authorsInclude: String = "",
    /** Likes/reposts/quotes: keep those whose original author is in this list. */
    val authorsExclude: String = "",

    val keepPinned: Boolean = true,
    /** Post ids or links that must never be deleted. */
    val keepIds: String = "",
)

@Serializable
data class SpeedConfig(
    val profile: SpeedProfile = SpeedProfile.BALANCED,
    val windowMinutes: Int = 15,
    val budget: Int = 35,
    val ceiling: Int = 50,
    val floor: Int = 8,
    val minGapSec: Int = 3,
    val maxGapSec: Int = 12,
    val spread: Boolean = true,
    val adaptive: Boolean = true,
    val useHeaders: Boolean = true,
    val jitterPct: Int = 35,
    val breathers: Boolean = true,
    val breatherEveryMin: Int = 15,
    val breatherEveryMax: Int = 40,
    val breatherSecMin: Int = 20,
    val breatherSecMax: Int = 75,
    val hourlyCap: Int = 0,
    val dailyCap: Int = 0,
    val readBudget: Int = 40,
    val verifyBudget: Int = 120,
)

fun SpeedConfig.withProfile(p: SpeedProfile): SpeedConfig = when (p) {
    SpeedProfile.GENTLE -> copy(
        profile = p, budget = 20, ceiling = 30, floor = 5, minGapSec = 6, maxGapSec = 25, jitterPct = 45,
        breatherEveryMin = 10, breatherEveryMax = 25, breatherSecMin = 30, breatherSecMax = 120,
    )
    SpeedProfile.BALANCED -> copy(
        profile = p, budget = 35, ceiling = 50, floor = 8, minGapSec = 3, maxGapSec = 12, jitterPct = 35,
        breatherEveryMin = 15, breatherEveryMax = 40, breatherSecMin = 20, breatherSecMax = 75,
    )
    SpeedProfile.FAST -> copy(
        profile = p, budget = 50, ceiling = 80, floor = 10, minGapSec = 1, maxGapSec = 5, jitterPct = 25,
        breatherEveryMin = 30, breatherEveryMax = 80, breatherSecMin = 10, breatherSecMax = 40,
    )
    SpeedProfile.CUSTOM -> copy(profile = p)
}

@Serializable
data class SafetyConfig(
    val stopOnAutomationFlag: Boolean = true,
    val automationCooldownHours: Int = 12,
    val maxConsecutiveErrors: Int = 8,
    val parkAfterSec: Int = 120,
    val keepCpuAwake: Boolean = true,
    val wifiOnly: Boolean = false,
    val unmeteredOnly: Boolean = false,
    val chargingOnly: Boolean = false,
    val minBatteryPct: Int = 0,
    val pauseInBatterySaver: Boolean = false,
    val order: DeleteOrder = DeleteOrder.OLDEST_FIRST,
)

@Serializable
data class ScheduleConfig(
    val mode: ScheduleMode = ScheduleMode.ALWAYS,
    val startMinute: Int = 8 * 60,
    val endMinute: Int = 23 * 60,
    /** Bit 0 = Monday ... bit 6 = Sunday. */
    val daysMask: Int = 0b1111111,
)

@Serializable
data class VerifyConfig(
    val delayMinMs: Int = 700,
    val delayMaxMs: Int = 1800,
    val checksPerItem: Int = 3,
    val retryDelaySec: Int = 20,
    val failAfterAttempts: Int = 4,
    /** Archive imports: look first, delete only what still exists (saves the delete budget). */
    val precheckArchive: Boolean = true,
    /** A tweet that X reports as unavailable counts as gone. */
    val unavailableIsGone: Boolean = true,
)

@Serializable
data class ScanConfig(
    val mode: ScanMode = ScanMode.SCAN_ONLY,
    val pageSize: Int = 40,
    val maxItems: Int = 0,
    val incremental: Boolean = true,
    val emptyPagesToStop: Int = 2,
)

@Serializable
data class NotifyConfig(
    val liveProgress: Boolean = true,
    val updateIntervalSec: Int = 2,
    val showEta: Boolean = true,
    val showRate: Boolean = true,
    val alertOnDone: Boolean = true,
    val alertOnCooldown: Boolean = false,
    val alertOnAttention: Boolean = true,
    val showOnLockScreen: Boolean = true,
)

@Serializable
data class GeneralConfig(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val confirmBeforeStart: Boolean = true,
    val resumeAfterReboot: Boolean = true,
    val logKeep: Int = 3000,
    val apiOrigin: String = "https://x.com",
    val dryRun: Boolean = false,
)

@Serializable
data class Settings(
    val filter: FilterConfig = FilterConfig(),
    val speed: SpeedConfig = SpeedConfig(),
    val safety: SafetyConfig = SafetyConfig(),
    val schedule: ScheduleConfig = ScheduleConfig(),
    val verify: VerifyConfig = VerifyConfig(),
    val scan: ScanConfig = ScanConfig(),
    val notify: NotifyConfig = NotifyConfig(),
    val general: GeneralConfig = GeneralConfig(),
)

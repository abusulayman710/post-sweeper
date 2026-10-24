package app.sweeper.data

import kotlinx.serialization.Serializable

/** Persisted per-endpoint rate-limit memory, so a restart (or a parked wait) never forgets a learned limit. */
@Serializable
data class BucketState(
    val windowStart: Long = 0,
    val used: Int = 0,
    val budget: Double = 0.0,
    val strikes: Int = 0,
    val okSinceStrike: Int = 0,
    val cooldownUntil: Long = 0,
    val nextAllowedAt: Long = 0,
    val cleanWindows: Int = 0,
    val hits: Int = 0,
    val hitThisWindow: Boolean = false,
    val serverRemaining: Int = -1,
    val serverLimit: Int = -1,
    val serverResetAt: Long = 0,
    val lastSendAt: Long = 0,
    val totalSent: Long = 0,
)

@Serializable
data class GovernorSnapshot(
    val buckets: Map<String, BucketState> = emptyMap(),
    val hourStart: Long = 0,
    val hourUsed: Int = 0,
    val dayStart: Long = 0,
    val dayUsed: Int = 0,
)

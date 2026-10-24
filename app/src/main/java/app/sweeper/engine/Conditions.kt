package app.sweeper.engine

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.PowerManager
import app.sweeper.data.SafetyConfig
import app.sweeper.data.ScheduleConfig
import app.sweeper.data.ScheduleMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

object ScheduleGate {
    /** 0 if running is allowed at [nowMs], otherwise the epoch ms at which the next allowed slot starts. */
    fun nextAllowedAt(nowMs: Long, cfg: ScheduleConfig, zone: ZoneId = ZoneId.systemDefault()): Long {
        if (cfg.mode == ScheduleMode.ALWAYS || cfg.daysMask == 0) return 0
        val now = Instant.ofEpochMilli(nowMs).atZone(zone)
        val minute = now.hour * 60 + now.minute
        val start = cfg.startMinute
        val end = cfg.endMinute
        if (start == end) return 0 // empty window == whole day
        fun dayOn(d: LocalDate) = (cfg.daysMask shr (d.dayOfWeek.value - 1)) and 1 == 1

        val today = now.toLocalDate()
        val inside = if (start < end) {
            dayOn(today) && minute in start until end
        } else {
            // Overnight window: the part after midnight belongs to the previous day's slot.
            (dayOn(today) && minute >= start) || (dayOn(today.minusDays(1)) && minute < end)
        }
        if (inside) return 0
        for (offset in 0..8) {
            val d = today.plusDays(offset.toLong())
            if (!dayOn(d)) continue
            val startAt = d.atStartOfDay(zone).plusMinutes(start.toLong())
            if (startAt.toInstant().toEpochMilli() > nowMs) return startAt.toInstant().toEpochMilli()
        }
        return nowMs + 3_600_000
    }
}

data class Block(val reason: String, val retryAtMs: Long)

object Conditions {
    /** Why running is not allowed right now (null = all clear). */
    fun check(ctx: Context, safety: SafetyConfig, schedule: ScheduleConfig, now: Long): Block? {
        val sched = ScheduleGate.nextAllowedAt(now, schedule)
        if (sched > 0) return Block("Outside the allowed schedule", sched)

        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        val online = caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        if (!online) return Block("No internet connection", now + 120_000)
        if (safety.wifiOnly && !caps!!.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
            !caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        ) return Block("Waiting for Wi-Fi", now + 180_000)
        if (safety.unmeteredOnly && !caps!!.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) {
            return Block("Waiting for an unmetered network", now + 180_000)
        }

        val battery: Intent? = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        if (safety.chargingOnly && !charging) return Block("Waiting for the charger", now + 180_000)
        if (safety.minBatteryPct > 0 && !charging) {
            val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, 100) ?: 100
            val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
            if (level * 100 / scale < safety.minBatteryPct) return Block("Battery below ${safety.minBatteryPct}%", now + 300_000)
        }
        if (safety.pauseInBatterySaver) {
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
            if (pm.isPowerSaveMode && !charging) return Block("Battery saver is on", now + 300_000)
        }
        return null
    }
}

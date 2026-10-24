package app.sweeper.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.sweeper.R
import app.sweeper.data.Desired
import app.sweeper.data.NotifyConfig
import app.sweeper.data.RunPhase
import app.sweeper.data.RunSnapshot
import app.sweeper.ui.MainActivity
import java.text.NumberFormat

object Notifier {
    const val CH_PROGRESS = "progress"
    const val CH_ALERT = "alerts"
    const val ID_PROGRESS = 1001
    const val ID_ALERT = 1002

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_PROGRESS, "Live progress", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Ongoing deletion progress, countdowns and controls"
                setShowBadge(false)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_ALERT, "Alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Finished, needs attention, rate-limit warnings"
            },
        )
    }

    fun canNotify(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun open(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun action(ctx: Context, act: String, req: Int): PendingIntent = PendingIntent.getBroadcast(
        ctx, req, Intent(ctx, ResumeReceiver::class.java).setAction(act),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private val nf = NumberFormat.getIntegerInstance()

    fun fmtDuration(ms: Long): String {
        val m = ms / 60_000
        return when {
            ms <= 0 -> "—"
            m >= 60 * 48 -> "${m / (60 * 24)}d ${(m / 60) % 24}h"
            m >= 60 -> "${m / 60}h ${m % 60}m"
            m >= 1 -> "${m}m"
            else -> "<1m"
        }
    }

    fun title(s: RunSnapshot): String = when (s.phase) {
        RunPhase.SCANNING -> "Scanning · ${nf.format(s.scanned)} found"
        RunPhase.DELETING, RunPhase.PACING ->
            "Deleting · ${nf.format(s.runDeleted)} / ${nf.format(s.runTotal)}" + if (s.runTotal > 0) " (${(s.progress * 100).toInt()}%)" else ""
        RunPhase.COOLDOWN -> "Rate-limited · waiting it out"
        RunPhase.PARKED -> "Next window soon · ${nf.format(s.runDeleted)} / ${nf.format(s.runTotal)}"
        RunPhase.WAITING -> "Waiting · ${s.message}"
        RunPhase.PAUSED -> "Paused · ${nf.format(s.runDeleted)} / ${nf.format(s.runTotal)}"
        RunPhase.ATTENTION -> "Needs your attention"
        RunPhase.DONE -> "Finished · ${nf.format(s.runDeleted)} done"
        RunPhase.IDLE -> "Post Sweeper"
    }

    fun progress(ctx: Context, s: RunSnapshot, n: NotifyConfig, ongoing: Boolean): android.app.Notification {
        val now = System.currentTimeMillis()
        val b = NotificationCompat.Builder(ctx, CH_PROGRESS)
            .setSmallIcon(R.drawable.ic_stat_sweep)
            .setContentTitle(title(s))
            .setOnlyAlertOnce(true)
            .setOngoing(ongoing)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setVisibility(if (n.showOnLockScreen) NotificationCompat.VISIBILITY_PUBLIC else NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(open(ctx))
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        val line = buildString {
            append("✓ ").append(nf.format(s.runDeleted))
            if (s.runFailed > 0) append("  ✗ ").append(nf.format(s.runFailed))
            if (n.showEta && s.etaMs > 0 && (s.phase == RunPhase.DELETING || s.phase == RunPhase.PACING || s.phase == RunPhase.COOLDOWN || s.phase == RunPhase.PARKED)) {
                append("  ·  ≈ ").append(fmtDuration(s.etaMs)).append(" left")
            }
            if (n.showRate && s.ratePerHour > 0) append("  ·  ").append(nf.format(s.ratePerHour.toInt())).append("/h")
        }
        val detail = buildString {
            append(s.message)
            if (s.windowBudget > 0 && s.phase != RunPhase.SCANNING) {
                append("\nWindow ").append(s.windowUsed).append('/').append(s.windowBudget).append(" · ").append(s.windowMinutes).append(" min")
                if (s.cooldownsHit > 0) append(" · limits hit ").append(s.cooldownsHit).append('×')
            }
            if (s.phase == RunPhase.ATTENTION) append('\n').append(s.attentionText)
        }
        b.setContentText(if (s.phase == RunPhase.ATTENTION) s.attentionText else line)
        b.setStyle(NotificationCompat.BigTextStyle().bigText(line + "\n" + detail).setSummaryText(s.phase.label))

        when {
            !n.liveProgress -> {}
            s.phase == RunPhase.SCANNING -> b.setProgress(0, 0, true)
            s.runTotal > 0 && s.phase != RunPhase.DONE -> b.setProgress(s.runTotal, (s.runDeleted + s.runFailed).coerceAtMost(s.runTotal), false)
        }
        if (s.resumeAt > now && s.phase in setOf(RunPhase.COOLDOWN, RunPhase.PARKED, RunPhase.WAITING)) {
            b.setShowWhen(true).setWhen(s.resumeAt).setUsesChronometer(true).setChronometerCountDown(true)
        } else {
            b.setShowWhen(false)
        }

        when (s.desired) {
            Desired.ACTIVE -> {
                b.addAction(0, "Pause", action(ctx, ResumeReceiver.ACTION_PAUSE, 1))
                b.addAction(0, "Stop", action(ctx, ResumeReceiver.ACTION_STOP, 2))
            }
            Desired.PAUSED -> {
                b.addAction(0, "Resume", action(ctx, ResumeReceiver.ACTION_RESUME, 3))
                b.addAction(0, "Stop", action(ctx, ResumeReceiver.ACTION_STOP, 2))
            }
            Desired.STOPPED -> {}
        }
        return b.build()
    }

    fun show(ctx: Context, n: android.app.Notification, id: Int = ID_PROGRESS) {
        if (!canNotify(ctx)) return
        runCatching { NotificationManagerCompat.from(ctx).notify(id, n) }
    }

    fun cancel(ctx: Context, id: Int = ID_PROGRESS) = NotificationManagerCompat.from(ctx).cancel(id)

    fun alert(ctx: Context, title: String, text: String) {
        val n = NotificationCompat.Builder(ctx, CH_ALERT)
            .setSmallIcon(R.drawable.ic_stat_sweep)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(open(ctx))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        show(ctx, n, ID_ALERT)
    }
}

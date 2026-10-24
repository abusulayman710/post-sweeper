package app.sweeper.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.sweeper.AppLog
import app.sweeper.Graph
import app.sweeper.data.AttentionKind
import app.sweeper.data.Ctl
import app.sweeper.data.Desired
import app.sweeper.data.RunMode
import app.sweeper.data.RunPhase
import app.sweeper.data.RunSnapshot
import app.sweeper.widget.WidgetUpdater
import java.util.concurrent.TimeUnit

object Alarms {
    private fun pending(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
        ctx, 77, Intent(ctx, ResumeReceiver::class.java).setAction(ResumeReceiver.ACTION_ALARM),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Exact, Doze-proof wake-up; exact alarms are also one of the few ways to start a foreground service from the background. */
    fun schedule(ctx: Context, atMs: Long) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val at = maxOf(atMs, System.currentTimeMillis() + 1_000)
        try {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(ctx))
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(ctx))
            }
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(ctx))
        }
    }

    fun cancel(ctx: Context) {
        (ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pending(ctx))
    }
}

object ServiceController {
    private fun startService(ctx: Context, fresh: Boolean) {
        val i = Intent(ctx, DeletionService::class.java).putExtra(DeletionService.EXTRA_FRESH, fresh)
        ContextCompat.startForegroundService(ctx, i)
    }

    /** User pressed Scan / Start. */
    fun start(ctx: Context, mode: RunMode) {
        Graph.control.state.value = Ctl.RUN
        Alarms.cancel(ctx)
        Graph.bus.update {
            RunSnapshot(
                desired = Desired.ACTIVE, phase = if (mode == RunMode.DELETE) RunPhase.DELETING else RunPhase.SCANNING,
                mode = mode, message = "Starting…", runStartedAt = System.currentTimeMillis(),
                lastScanAt = it.lastScanAt, windowMinutes = Graph.settings.current.speed.windowMinutes,
            )
        }
        startService(ctx, fresh = true)
    }

    fun resume(ctx: Context) {
        Graph.control.state.value = Ctl.RUN
        Alarms.cancel(ctx)
        Graph.bus.update {
            it.copy(desired = Desired.ACTIVE, phase = RunPhase.DELETING, message = "Resuming…", attention = AttentionKind.NONE, attentionText = "", resumeAt = 0)
        }
        startService(ctx, fresh = false)
    }

    fun pause(ctx: Context) {
        Alarms.cancel(ctx)
        Graph.bus.update { it.copy(desired = Desired.PAUSED, phase = RunPhase.PAUSED, message = "Paused", resumeAt = 0) }
        if (DeletionService.running) {
            Graph.control.state.value = Ctl.PAUSE
        } else {
            Notifier.show(ctx, Notifier.progress(ctx, Graph.bus.snapshot.value, Graph.settings.current.notify, true))
        }
    }

    fun stop(ctx: Context) {
        Alarms.cancel(ctx)
        Graph.bus.update { it.copy(desired = Desired.STOPPED, phase = RunPhase.IDLE, message = "", resumeAt = 0, attention = AttentionKind.NONE, attentionText = "") }
        if (DeletionService.running) Graph.control.state.value = Ctl.STOP else Notifier.cancel(ctx)
    }

    /** The scheduled wake-up fired (or the watchdog / boot decided it is time). */
    fun onAlarm(ctx: Context): Boolean {
        val s = Graph.bus.snapshot.value
        if (s.desired != Desired.ACTIVE || DeletionService.running) return false
        Graph.control.state.value = Ctl.RUN
        return try {
            startService(ctx, fresh = false)
            true
        } catch (e: Exception) {
            AppLog.w("service", "Could not start the service from the background (${e.javaClass.simpleName}); retrying shortly")
            Alarms.schedule(ctx, System.currentTimeMillis() + 30_000)
            false
        }
    }
}

class ResumeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_ALARM -> ServiceController.onAlarm(context)
            ACTION_PAUSE -> ServiceController.pause(context)
            ACTION_STOP -> ServiceController.stop(context)
            ACTION_RESUME -> ServiceController.resume(context)
        }
    }

    companion object {
        const val ACTION_ALARM = "app.sweeper.ALARM"
        const val ACTION_PAUSE = "app.sweeper.PAUSE"
        const val ACTION_STOP = "app.sweeper.STOP"
        const val ACTION_RESUME = "app.sweeper.RESUME"
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val s = Graph.bus.snapshot.value
        if (!Graph.settings.current.general.resumeAfterReboot || s.desired != Desired.ACTIVE) return
        if (s.resumeAt > System.currentTimeMillis() + 5_000) Alarms.schedule(context, s.resumeAt)
        else ServiceController.onAlarm(context)
    }
}

/** Safety net: if the service died while a run was supposed to be active, bring it back. */
class WatchdogWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val s = Graph.bus.snapshot.value
        if (s.desired == Desired.ACTIVE && !DeletionService.running && System.currentTimeMillis() >= s.resumeAt - 60_000) {
            AppLog.i("watchdog", "Run should be active but the service is not running; restarting")
            if (!ServiceController.onAlarm(applicationContext)) Alarms.schedule(applicationContext, System.currentTimeMillis() + 5_000)
        }
        WidgetUpdater.push(applicationContext)
        return Result.success()
    }

    companion object {
        fun schedule(ctx: Context) {
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
                "watchdog", ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<WatchdogWorker>(15, TimeUnit.MINUTES).build(),
            )
        }
    }
}

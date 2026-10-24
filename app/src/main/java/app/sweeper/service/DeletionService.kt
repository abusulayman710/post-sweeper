package app.sweeper.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import app.sweeper.AppLog
import app.sweeper.Graph
import app.sweeper.data.AttentionKind
import app.sweeper.data.Desired
import app.sweeper.data.RunPhase
import app.sweeper.engine.DeletionEngine
import app.sweeper.engine.Exit
import app.sweeper.widget.WidgetUpdater
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.max

/**
 * Foreground service that hosts the engine. It only holds the foreground while there is real work or a short wait;
 * long waits (window used up, cooldown) are handed to an exact alarm so the phone is not kept busy for hours.
 */
class DeletionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var ticker: Job? = null
    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        wake = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "sweeper:engine").apply { setReferenceCounted(false) }
    }

    private fun foreground() {
        val n = Notifier.progress(this, Graph.bus.snapshot.value, Graph.settings.current.notify, true)
        if (Build.VERSION.SDK_INT >= 34) {
            ServiceCompat.startForeground(this, Notifier.ID_PROGRESS, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(Notifier.ID_PROGRESS, n)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        foreground()
        val s = Graph.bus.snapshot.value
        if (s.desired != Desired.ACTIVE) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        if (job?.isActive != true) {
            startEngine(fresh = intent?.getBooleanExtra(EXTRA_FRESH, false) == true)
            startTicker()
        }
        return START_STICKY
    }

    private fun acquireWake(ms: Long) {
        runCatching { wake?.acquire(ms.coerceIn(5_000, 20 * 60_000)) }
    }

    private fun startEngine(fresh: Boolean) {
        val mode = Graph.bus.snapshot.value.mode
        job = scope.launch {
            val engine = DeletionEngine(this@DeletionService, ::acquireWake)
            val exit = try {
                engine.run(mode, fresh)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                AppLog.e("engine", "Unexpected: ${e.javaClass.simpleName}: ${e.message}")
                Exit.Park(System.currentTimeMillis() + 120_000, RunPhase.WAITING, "Unexpected error, retrying in 2 minutes")
            }
            finish(exit)
        }
    }

    private fun startTicker() {
        ticker = scope.launch {
            var n = 0
            while (isActive) {
                val cfg = Graph.settings.current.notify
                if (cfg.liveProgress) {
                    Notifier.show(this@DeletionService, Notifier.progress(this@DeletionService, Graph.bus.snapshot.value, cfg, true))
                }
                if (n++ % 3 == 0) WidgetUpdater.push(this@DeletionService)
                delay(max(1, cfg.updateIntervalSec) * 1000L)
            }
        }
    }

    private suspend fun finish(exit: Exit) {
        ticker?.cancel()
        val bus = Graph.bus
        val cfg = Graph.settings.current.notify
        when (exit) {
            Exit.Done -> bus.update { s ->
                val msg = if (s.mode.name == "SCAN") s.message else "All done"
                s.copy(desired = Desired.STOPPED, phase = RunPhase.DONE, message = msg, resumeAt = 0, etaMs = 0)
            }
            Exit.Stopped -> bus.update { it.copy(desired = Desired.STOPPED, phase = RunPhase.IDLE, message = "", resumeAt = 0) }
            Exit.Paused -> bus.update { it.copy(desired = Desired.PAUSED, phase = RunPhase.PAUSED, message = "Paused", resumeAt = 0) }
            is Exit.Park -> {
                bus.update { it.copy(phase = exit.phase, message = exit.message, resumeAt = exit.resumeAt) }
                Alarms.schedule(this, exit.resumeAt - 2_000)
                AppLog.i("service", "Parked until ${java.util.Date(exit.resumeAt)}: ${exit.message}")
            }
            is Exit.Attention -> bus.update {
                it.copy(desired = Desired.PAUSED, phase = RunPhase.ATTENTION, attention = exit.kind, attentionText = exit.text, message = exit.text, resumeAt = 0)
            }
        }
        bus.flush()
        val snap = bus.snapshot.value
        WidgetUpdater.push(this)

        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
        when (exit) {
            Exit.Stopped -> Notifier.cancel(this)
            Exit.Done -> {
                Notifier.cancel(this)
                if (cfg.alertOnDone) {
                    val text = if (snap.mode.name == "SCAN") snap.message
                    else "${snap.runDeleted} verified deleted" + if (snap.runFailed > 0) ", ${snap.runFailed} failed" else ""
                    Notifier.alert(this, "Post Sweeper finished", text)
                }
            }
            is Exit.Attention -> {
                Notifier.show(this, Notifier.progress(this, snap, Graph.settings.current.notify, true))
                if (cfg.alertOnAttention) Notifier.alert(this, "Post Sweeper needs you", exit.text)
            }
            is Exit.Park -> {
                Notifier.show(this, Notifier.progress(this, snap, Graph.settings.current.notify, true))
                if (cfg.alertOnCooldown && exit.phase == RunPhase.COOLDOWN) Notifier.alert(this, "Rate-limited by X", exit.message)
            }
            Exit.Paused -> Notifier.show(this, Notifier.progress(this, snap, Graph.settings.current.notify, true))
        }
        stopSelf()
    }

    override fun onDestroy() {
        ticker?.cancel()
        scope.cancel()
        runCatching { if (wake?.isHeld == true) wake?.release() }
        running = false
        super.onDestroy()
    }

    companion object {
        const val EXTRA_FRESH = "fresh"

        @Volatile
        var running = false
    }
}

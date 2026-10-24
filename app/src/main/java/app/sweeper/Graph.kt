package app.sweeper

import android.app.Application
import android.util.Log
import androidx.room.Room
import app.sweeper.data.AppDatabase
import app.sweeper.data.EngineControl
import app.sweeper.data.LogEntity
import app.sweeper.data.RunBus
import app.sweeper.data.SettingsRepository
import app.sweeper.data.StateStore
import app.sweeper.net.XApi
import app.sweeper.net.XTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Tiny hand-rolled service locator; everything lives for the process lifetime. */
object Graph {
    lateinit var app: Application
        private set

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val db: AppDatabase by lazy {
        Room.databaseBuilder(app, AppDatabase::class.java, "sweeper.db").fallbackToDestructiveMigration().build()
    }
    val store by lazy { StateStore(app) }
    val settings by lazy { SettingsRepository(app, appScope) }
    val bus by lazy { RunBus(store, appScope) }
    val transport by lazy {
        XTransport(app).also { tr ->
            tr.origin = settings.current.general.apiOrigin
            runCatching {
                kotlinx.coroutines.runBlocking {
                    store.account()?.let { acc ->
                        tr.restoreSessionIfPresent(acc.authToken, acc.ct0, acc.userId)
                    }
                }
            }
        }
    }
    val api by lazy { XApi(transport, store) }
    val control = EngineControl()

    fun init(a: Application) {
        app = a
    }
}

object AppLog {
    fun d(tag: String, msg: String) = write(0, tag, msg)
    fun i(tag: String, msg: String) = write(1, tag, msg)
    fun w(tag: String, msg: String) = write(2, tag, msg)
    fun e(tag: String, msg: String) = write(3, tag, msg)

    private fun write(level: Int, tag: String, msg: String) {
        Log.println(if (level >= 3) Log.ERROR else if (level == 2) Log.WARN else Log.INFO, "Sweeper/$tag", msg)
        if (level == 0) return
        Graph.appScope.launch {
            runCatching {
                val dao = Graph.db.logs()
                dao.insert(LogEntity(ts = System.currentTimeMillis(), level = level, tag = tag, msg = msg))
                if (System.nanoTime() % 25L == 0L) dao.trim(Graph.settings.current.general.logKeep.coerceAtLeast(200))
            }
        }
    }
}

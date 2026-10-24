package app.sweeper.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable

internal val Context.sweeperStore by preferencesDataStore("sweeper")

enum class Desired { STOPPED, ACTIVE, PAUSED }

enum class RunPhase(val label: String) {
    IDLE("Idle"),
    SCANNING("Scanning"),
    DELETING("Deleting"),
    PACING("Pacing"),
    COOLDOWN("Cooling down"),
    PARKED("Waiting"),
    PAUSED("Paused"),
    WAITING("Waiting for conditions"),
    DONE("Done"),
    ATTENTION("Needs attention"),
}

enum class AttentionKind { NONE, LOGIN, LOCKED, AUTOMATION, ERRORS }

enum class RunMode { SCAN, DELETE, SCAN_DELETE }

@Serializable
data class Account(
    val userId: String,
    val handle: String,
    val authToken: String = "",
    val ct0: String = "",
)

@Serializable
data class RunSnapshot(
    val desired: Desired = Desired.STOPPED,
    val phase: RunPhase = RunPhase.IDLE,
    val mode: RunMode = RunMode.DELETE,
    val message: String = "",
    val resumeAt: Long = 0,
    val updatedAt: Long = 0,
    val runStartedAt: Long = 0,
    val runTotal: Int = 0,
    val runDeleted: Int = 0,
    val runFailed: Int = 0,
    val scanned: Int = 0,
    val ratePerHour: Double = 0.0,
    val etaMs: Long = 0,
    val windowUsed: Int = 0,
    val windowBudget: Int = 0,
    val windowEndsAt: Long = 0,
    val windowMinutes: Int = 15,
    val cooldownsHit: Int = 0,
    val attention: AttentionKind = AttentionKind.NONE,
    val attentionText: String = "",
    val cursors: Map<String, String> = emptyMap(),
    /** Set when a full scan finished (never by an interrupted one). */
    val lastScanAt: Long = 0,
    /** True once the scan part of the current run is complete (so a resume does not rescan). */
    val scanComplete: Boolean = false,
) {
    val active: Boolean get() = desired == Desired.ACTIVE
    val progress: Float
        get() = if (runTotal <= 0) 0f else ((runDeleted + runFailed).toFloat() / runTotal).coerceIn(0f, 1f)
}

@Serializable
data class OpInfo(val id: String, val features: List<String> = emptyList(), val toggles: List<String> = emptyList())

@Serializable
data class QueryIdCache(
    val fetchedAt: Long = 0,
    val ops: Map<String, OpInfo> = emptyMap(),
    val bearer: String = "",
    val schemaVersion: Int = 0,
)

class StateStore(private val ctx: Context) {
    private val kSnap = stringPreferencesKey("snapshot")
    private val kGov = stringPreferencesKey("governor")
    private val kAcc = stringPreferencesKey("account")
    private val kIds = stringPreferencesKey("query_ids")

    private inline fun <reified T> decode(s: String?, default: T): T =
        if (s == null) default else runCatching { AppJson.decodeFromString<T>(s) }.getOrDefault(default)

    suspend fun loadSnapshot(): RunSnapshot = decode(ctx.sweeperStore.data.first()[kSnap], RunSnapshot())
    suspend fun saveSnapshot(s: RunSnapshot) {
        ctx.sweeperStore.edit { it[kSnap] = AppJson.encodeToString(RunSnapshot.serializer(), s) }
    }

    suspend fun loadGovernor(): GovernorSnapshot = decode(ctx.sweeperStore.data.first()[kGov], GovernorSnapshot())
    suspend fun saveGovernor(g: GovernorSnapshot) {
        ctx.sweeperStore.edit { it[kGov] = AppJson.encodeToString(GovernorSnapshot.serializer(), g) }
    }

    val accountFlow: Flow<Account?> = ctx.sweeperStore.data.map { p -> p[kAcc]?.let { decode<Account?>(it, null) } }.distinctUntilChanged()
    suspend fun account(): Account? = ctx.sweeperStore.data.first()[kAcc]?.let { decode<Account?>(it, null) }
    suspend fun saveAccount(a: Account?) {
        ctx.sweeperStore.edit { p ->
            if (a == null) p.remove(kAcc) else p[kAcc] = AppJson.encodeToString(Account.serializer(), a)
        }
    }

    suspend fun loadQueryIds(): QueryIdCache = decode(ctx.sweeperStore.data.first()[kIds], QueryIdCache())
    suspend fun saveQueryIds(c: QueryIdCache) {
        ctx.sweeperStore.edit { it[kIds] = AppJson.encodeToString(QueryIdCache.serializer(), c) }
    }
}

class SettingsRepository(private val ctx: Context, scope: CoroutineScope) {
    private val key = stringPreferencesKey("settings_json")

    private fun decode(p: Preferences): Settings =
        p[key]?.let { runCatching { AppJson.decodeFromString<Settings>(it) }.getOrNull() } ?: Settings()

    val flow: StateFlow<Settings> = ctx.sweeperStore.data
        .map { decode(it) }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, runBlocking { decode(ctx.sweeperStore.data.first()) })

    val current: Settings get() = flow.value

    suspend fun update(f: (Settings) -> Settings) {
        ctx.sweeperStore.edit { p ->
            p[key] = AppJson.encodeToString(Settings.serializer(), f(decode(p)))
        }
    }

    fun exportJson(): String = Json_pretty.encodeToString(Settings.serializer(), current)

    suspend fun importJson(text: String) {
        val s = AppJson.decodeFromString<Settings>(text)
        update { s }
    }
}

private val Json_pretty = kotlinx.serialization.json.Json(AppJson) { prettyPrint = true }

/** In-process live state shared by the service, the UI and the widgets; mirrored to disk. */
class RunBus(private val store: StateStore, scope: CoroutineScope) {
    private val _snap = MutableStateFlow(runBlocking { store.loadSnapshot() })
    val snapshot: StateFlow<RunSnapshot> = _snap

    @Volatile
    private var dirty = false

    fun update(f: (RunSnapshot) -> RunSnapshot) {
        _snap.update { f(it).copy(updatedAt = System.currentTimeMillis()) }
        dirty = true
    }

    suspend fun flush() {
        if (dirty) {
            dirty = false
            store.saveSnapshot(_snap.value)
        }
    }

    init {
        scope.launch {
            while (true) {
                delay(2000)
                flush()
            }
        }
    }
}

/** Pause/stop signalling between the UI and the running engine. */
enum class Ctl { RUN, PAUSE, STOP }

class EngineControl {
    val state = MutableStateFlow(Ctl.RUN)
}

package app.sweeper.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.sweeper.Graph
import app.sweeper.data.Account
import app.sweeper.data.CountRow
import app.sweeper.data.GovernorSnapshot
import app.sweeper.data.ItemStatus
import app.sweeper.data.LogEntity
import app.sweeper.data.PostEntity
import app.sweeper.data.PostType
import app.sweeper.data.RunMode
import app.sweeper.data.RunSnapshot
import app.sweeper.data.Settings
import app.sweeper.engine.ArchiveImporter
import app.sweeper.engine.FilterEngine
import app.sweeper.service.ServiceController
import app.sweeper.widget.WidgetUpdater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class Counts(val rows: List<CountRow>) {
    fun n(vararg s: ItemStatus) = rows.filter { it.status in s }.sumOf { it.n }
    fun n(type: PostType, vararg s: ItemStatus) = rows.filter { it.type == type && it.status in s }.sumOf { it.n }
    val total get() = rows.sumOf { it.n }
    val pulled get() = n(ItemStatus.PULLED)
    val queued get() = n(ItemStatus.QUEUED, ItemStatus.DELETING, ItemStatus.SENT)
    val verified get() = n(ItemStatus.VERIFIED)
    val failed get() = n(ItemStatus.FAILED)
}

data class Loaded(val account: Account?)

private data class PostQuery(val type: String, val view: String, val q: String, val limit: Int)

@OptIn(FlowPreview::class)
class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val dao = Graph.db.posts()
    private val ctx get() = getApplication<Application>()

    val settings: StateFlow<Settings> = Graph.settings.flow
    val snapshot: StateFlow<RunSnapshot> = Graph.bus.snapshot
    val account: StateFlow<Loaded?> =
        Graph.store.accountFlow.map { Loaded(it) }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val counts: StateFlow<Counts> =
        dao.observeCounts().map { Counts(it) }.stateIn(viewModelScope, SharingStarted.Eagerly, Counts(emptyList()))
    val willDelete: StateFlow<Int> = dao.observeWillDelete().stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    val typeFilter = MutableStateFlow("ALL")
    val viewFilter = MutableStateFlow("DELETE")
    val query = MutableStateFlow("")
    val limit = MutableStateFlow(150)

    val posts: StateFlow<List<PostEntity>> = combine(typeFilter, viewFilter, query.debounce(250), limit) { t, v, q, l ->
        PostQuery(t, v, q.trim(), l)
    }.flatMapLatest { dao.observe(it.type, it.view, it.q, it.limit) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val logs: StateFlow<List<LogEntity>> =
        Graph.db.logs().observe(400).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _toast = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val toast: SharedFlow<String> = _toast

    @Volatile
    private var verifyingLogin = false

    init {
        viewModelScope.launch {
            Graph.settings.flow.map { it.filter }.distinctUntilChanged().debounce(500).collect {
                withContext(Dispatchers.Default) { FilterEngine.recompute() }
            }
        }
        viewModelScope.launch {
            val saved = Graph.store.account() ?: return@launch
            Graph.transport.restoreSessionIfPresent(saved.authToken, saved.ct0, saved.userId)
            // If a previous session saved the broken "@me" fallback or has no handle, heal or reset it immediately.
            if (saved.handle == "me" || saved.handle.isBlank() || saved.userId.isBlank()) {
                when (val res = Graph.api.resolveIdentity(saved.userId.ifBlank { null })) {
                    is app.sweeper.net.IdentityResult.Ok -> {
                        val auth = Graph.transport.authToken() ?: saved.authToken
                        val ct0 = Graph.transport.csrf() ?: saved.ct0
                        Graph.store.saveAccount(Account(res.userId, res.handle, auth, ct0))
                    }
                    is app.sweeper.net.IdentityResult.Error -> {
                        if (saved.handle == "me" || !Graph.transport.hasSession()) {
                            Graph.store.saveAccount(null)
                        }
                    }
                }
            }
        }
    }

    fun say(msg: String) {
        _toast.tryEmit(msg)
    }

    fun update(f: (Settings) -> Settings) {
        viewModelScope.launch { Graph.settings.update(f) }
    }

    // ---------------------------------------------------------------- run control

    fun scan() {
        val mode = if (settings.value.scan.mode == app.sweeper.data.ScanMode.SCAN_AND_DELETE) RunMode.SCAN_DELETE else RunMode.SCAN
        ServiceController.start(ctx, mode)
    }

    fun startDeleting() {
        viewModelScope.launch(Dispatchers.IO) {
            FilterEngine.recompute()
            dao.queueMatching()
            ServiceController.start(ctx, RunMode.DELETE)
        }
    }

    fun pause() = ServiceController.pause(ctx)
    fun resume() = ServiceController.resume(ctx)
    fun stop() = ServiceController.stop(ctx)

    // ---------------------------------------------------------------- account

    fun loginDetected() {
        if (verifyingLogin) return
        verifyingLogin = true
        viewModelScope.launch {
            try {
                android.webkit.CookieManager.getInstance().flush()
                val cookies = Graph.transport.cookies()
                val auth = cookies["auth_token"]?.takeIf { it.isNotBlank() } ?: return@launch
                val ct0 = cookies["ct0"]?.takeIf { it.isNotBlank() } ?: return@launch
                Graph.transport.restoreSessionIfPresent(auth, ct0, Graph.transport.userIdFromCookie().orEmpty())
                when (val res = Graph.api.resolveIdentity()) {
                    is app.sweeper.net.IdentityResult.Ok -> {
                        Graph.transport.setSessionCookies(auth, ct0, res.userId)
                        Graph.store.saveAccount(Account(res.userId, res.handle, auth, ct0))
                    }
                    is app.sweeper.net.IdentityResult.Error -> {}
                }
            } finally {
                verifyingLogin = false
            }
        }
    }

    fun loginWithCookies(
        authToken: String,
        ct0: String,
        handleOrUserId: String,
        onDone: () -> Unit = {},
        onError: (String) -> Unit,
    ) {
        if (verifyingLogin) return
        verifyingLogin = true
        viewModelScope.launch {
            try {
                // Support pasting either individual values or a full "auth_token=...; ct0=..." cookie string
                val combined = "$authToken; $ct0"
                val parsedAuth = Regex("""(?:^|[;\s])auth_token=([^;\s]+)""").find(combined)?.groupValues?.get(1)
                    ?: authToken.trim().removePrefix("auth_token=").substringBefore(";").trim()
                val parsedCt0 = Regex("""(?:^|[;\s])ct0=([^;\s]+)""").find(combined)?.groupValues?.get(1)
                    ?: ct0.trim().removePrefix("ct0=").substringBefore(";").trim()
                val parsedTwid = Regex("""(?:^|[;\s])twid=u(?:%3D|=)(\d+)""").find("$combined; $handleOrUserId")?.groupValues?.get(1)

                if (parsedAuth.isBlank() || parsedCt0.isBlank()) {
                    onError("Enter both auth_token and ct0")
                    return@launch
                }

                val hint = handleOrUserId.trim().ifBlank { parsedTwid.orEmpty() }
                val numericHint = Regex("""^(?:twid=)?(?:u(?:%3D|=))?(\d{4,})$""", RegexOption.IGNORE_CASE)
                    .find(hint)?.groupValues?.get(1) ?: parsedTwid

                Graph.transport.setSessionCookies(parsedAuth, parsedCt0, numericHint)

                when (val res = Graph.api.resolveIdentity(hint.ifBlank { null })) {
                    is app.sweeper.net.IdentityResult.Ok -> {
                        Graph.transport.setSessionCookies(parsedAuth, parsedCt0, res.userId)
                        Graph.store.saveAccount(Account(res.userId, res.handle, parsedAuth, parsedCt0))
                        onDone()
                    }
                    is app.sweeper.net.IdentityResult.Error -> {
                        onError(res.message)
                    }
                }
            } finally {
                verifyingLogin = false
            }
        }
    }

    fun devSession() {
        viewModelScope.launch {
            Graph.transport.setSessionCookies("dev", "dev", "1000")
            Graph.store.saveAccount(Account("1000", "devuser", "dev", "dev"))
        }
    }

    fun logout(clearData: Boolean) {
        viewModelScope.launch {
            ServiceController.stop(ctx)
            Graph.transport.clearSession()
            Graph.store.saveAccount(null)
            Graph.store.saveGovernor(GovernorSnapshot())
            if (clearData) withContext(Dispatchers.IO) { dao.clearAll() }
            Graph.bus.update { RunSnapshot() }
            WidgetUpdater.push(ctx)
        }
    }

    // ---------------------------------------------------------------- items

    fun setManual(p: PostEntity, manual: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.setManual(p.id, p.type, manual)
            FilterEngine.recompute()
        }
    }

    fun queueOne(p: PostEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.setManual(p.id, p.type, 2)
            dao.queueOne(p.id, p.type)
        }
    }

    fun retryFailed() {
        viewModelScope.launch(Dispatchers.IO) { say("${dao.retryFailed()} failed items re-queued") }
    }

    fun recompute() {
        viewModelScope.launch(Dispatchers.Default) {
            val c = FilterEngine.recompute()
            say("Filters re-applied ($c changed)")
        }
    }

    fun clearScanned() {
        viewModelScope.launch(Dispatchers.IO) { say("Removed ${dao.clearUnfinished()} scanned items") }
    }

    fun importArchive(uri: Uri) {
        val acc = (account.value?.account) ?: return
        viewModelScope.launch(Dispatchers.IO) {
            say("Importing archive…")
            runCatching { ArchiveImporter.import(ctx, uri, acc.userId, acc.handle) }
                .onSuccess { say("Imported ${it.posts} posts and ${it.likes} likes") }
                .onFailure { say("Import failed: ${it.message}") }
        }
    }

    // ---------------------------------------------------------------- diagnostics / data

    fun resetLearnedLimits() {
        viewModelScope.launch {
            val g = Graph.store.loadGovernor()
            Graph.store.saveGovernor(g.copy(buckets = g.buckets.mapValues { it.value.copy(budget = 0.0, strikes = 0, cooldownUntil = 0, cleanWindows = 0, okSinceStrike = 0) }))
            say("Learned limits reset")
        }
    }

    fun refreshEndpoints() {
        viewModelScope.launch {
            say("Refreshing endpoints…")
            val ok = runCatching { Graph.api.refreshQueryIds(force = true) }.getOrDefault(false)
            val c = Graph.api.cacheInfo()
            say(if (ok) "All ${c.ops.size} operations found" else "Partial: ${c.ops.size} operations known. Open x.com logged in and retry.")
        }
    }

    fun testConnection() {
        viewModelScope.launch {
            val saved = account.value?.account
            val res = Graph.api.resolveIdentity(saved?.userId)
            val c = Graph.api.cacheInfo()
            when (res) {
                is app.sweeper.net.IdentityResult.Ok -> {
                    val auth = Graph.transport.authToken() ?: saved?.authToken.orEmpty()
                    val ct0 = Graph.transport.csrf() ?: saved?.ct0.orEmpty()
                    Graph.store.saveAccount(Account(res.userId, res.handle, auth, ct0))
                    say("Connected as @${res.handle} (ID ${res.userId}) · ${c.ops.size} operations cached")
                }
                is app.sweeper.net.IdentityResult.Error -> {
                    say(res.message)
                }
            }
        }
    }

    fun setOrigin(o: String) {
        Graph.transport.origin = o.trim().trimEnd('/')
        update { it.copy(general = it.general.copy(apiOrigin = o.trim().trimEnd('/'))) }
    }

    suspend fun exportSettings(): String = Graph.settings.exportJson()

    suspend fun importSettings(text: String) {
        runCatching { Graph.settings.importJson(text) }
            .onSuccess { say("Settings imported") }
            .onFailure { say("Not a valid settings file") }
    }

    suspend fun logCsv(): String = withContext(Dispatchers.IO) {
        val sb = StringBuilder("time,level,tag,message\n")
        Graph.db.logs().latest(5000).reversed().forEach {
            sb.append(it.ts).append(',').append(it.level).append(',').append(it.tag).append(',')
                .append('"').append(it.msg.replace("\"", "\"\"")).append("\"\n")
        }
        sb.toString()
    }

    suspend fun deletedCsv(): String = withContext(Dispatchers.IO) {
        val sb = StringBuilder("id,type,created,verified_at,already_gone,text\n")
        dao.allVerified().forEach {
            sb.append(it.id).append(',').append(it.type).append(',').append(it.createdAt).append(',')
                .append(it.verifiedAt ?: 0).append(',').append(it.alreadyGone).append(',')
                .append('"').append(it.text.replace("\"", "\"\"").replace("\n", " ")).append("\"\n")
        }
        sb.toString()
    }
}

package app.sweeper.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.sweeper.BuildConfig
import app.sweeper.data.DeleteOrder
import app.sweeper.data.GeneralConfig
import app.sweeper.data.NotifyConfig
import app.sweeper.data.SafetyConfig
import app.sweeper.data.ScanConfig
import app.sweeper.data.ScanMode
import app.sweeper.data.ScheduleConfig
import app.sweeper.data.ScheduleMode
import app.sweeper.data.SpeedConfig
import app.sweeper.data.SpeedProfile
import app.sweeper.data.ThemeMode
import app.sweeper.data.VerifyConfig
import app.sweeper.data.withProfile
import app.sweeper.ui.theme.Warn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SettingsScreen(vm: MainViewModel, openLogs: () -> Unit) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var clearConfirm by remember { mutableStateOf(false) }

    fun speed(g: (SpeedConfig) -> SpeedConfig) = vm.update { it.copy(speed = g(it.speed).copy(profile = SpeedProfile.CUSTOM)) }
    fun safety(g: (SafetyConfig) -> SafetyConfig) = vm.update { it.copy(safety = g(it.safety)) }
    fun sched(g: (ScheduleConfig) -> ScheduleConfig) = vm.update { it.copy(schedule = g(it.schedule)) }
    fun verify(g: (VerifyConfig) -> VerifyConfig) = vm.update { it.copy(verify = g(it.verify)) }
    fun scan(g: (ScanConfig) -> ScanConfig) = vm.update { it.copy(scan = g(it.scan)) }
    fun notify(g: (NotifyConfig) -> NotifyConfig) = vm.update { it.copy(notify = g(it.notify)) }
    fun general(g: (GeneralConfig) -> GeneralConfig) = vm.update { it.copy(general = g(it.general)) }

    val exportSettings = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let { scope.launch { ctx.contentResolver.openOutputStream(it)?.use { o -> o.write(vm.exportSettings().toByteArray()) } } }
    }
    val importSettings = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { scope.launch { ctx.contentResolver.openInputStream(it)?.use { i -> vm.importSettings(i.readBytes().decodeToString()) } } }
    }
    val exportLog = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        uri?.let { scope.launch { ctx.contentResolver.openOutputStream(it)?.use { o -> o.write(vm.logCsv().toByteArray()) } } }
    }
    val exportDeleted = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        uri?.let { scope.launch { ctx.contentResolver.openOutputStream(it)?.use { o -> o.write(vm.deletedCsv().toByteArray()) } } }
    }

    LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Text("Settings", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Hint("Every knob. The defaults are conservative on purpose.")
        }

        item {
            Section("Speed & limits", "Pacing, budget, adaptive learning", startOpen = true) {
                ChoiceRow(
                    "Profile",
                    listOf(SpeedProfile.GENTLE to "Gentle", SpeedProfile.BALANCED to "Balanced", SpeedProfile.FAST to "Fast (riskier)", SpeedProfile.CUSTOM to "Custom"),
                    s.speed.profile, { p -> vm.update { it.copy(speed = it.speed.withProfile(p)) } },
                    "Gentle ≈ 20 per window, Balanced ≈ 35, Fast ≈ 50. The app learns the real limit either way.",
                )
                SliderRow("Window length", s.speed.windowMinutes, 5..120, { v -> speed { it.copy(windowMinutes = v) } }, "X counts requests in fixed windows (usually 15 min).", step = 5, label = { "$it min" })
                SliderRow("Starting budget per window", s.speed.budget, 5..150, { v -> speed { it.copy(budget = v, ceiling = maxOf(it.ceiling, v)) } }, "Deletes allowed per window before it waits.")
                SliderRow("Learning ceiling", s.speed.ceiling, 5..300, { v -> speed { it.copy(ceiling = v) } }, "The budget never grows past this.")
                SliderRow("Learning floor", s.speed.floor, 1..50, { v -> speed { it.copy(floor = v) } }, "...and never shrinks below this.")
                ChoiceRow(
                    "Pacing", listOf(true to "Spread evenly", false to "Burst, then rest"), s.speed.spread, { v -> speed { it.copy(spread = v) } },
                    "Even spacing looks most human and is gentlest on the limiter.",
                )
                SliderRow("Shortest gap", s.speed.minGapSec, 1..60, { v -> speed { it.copy(minGapSec = v, maxGapSec = maxOf(it.maxGapSec, v)) } }, label = { "$it s" })
                SliderRow("Longest gap (burst mode)", s.speed.maxGapSec, 1..120, { v -> speed { it.copy(maxGapSec = maxOf(v, it.minGapSec)) } }, label = { "$it s" })
                SliderRow("Randomness", s.speed.jitterPct, 0..80, { v -> speed { it.copy(jitterPct = v) } }, "Each gap is varied by up to this much.", step = 5, label = { "±$it%" })
                SwitchRow("Learn the real limit", "Shrinks after a 429, grows slowly after clean windows.", s.speed.adaptive) { v -> speed { it.copy(adaptive = v) } }
                SwitchRow("Obey X's rate-limit headers", "Uses x-rate-limit-remaining/reset when X sends them.", s.speed.useHeaders) { v -> speed { it.copy(useHeaders = v) } }
                SwitchRow("Short breathers", "Every so often, pause like a person would.", s.speed.breathers) { v -> speed { it.copy(breathers = v) } }
                if (s.speed.breathers) {
                    SliderRow("Breather every (min)", s.speed.breatherEveryMin, 3..100, { v -> speed { it.copy(breatherEveryMin = v, breatherEveryMax = maxOf(it.breatherEveryMax, v)) } }, label = { "$it deletes" })
                    SliderRow("Breather every (max)", s.speed.breatherEveryMax, 3..200, { v -> speed { it.copy(breatherEveryMax = maxOf(v, it.breatherEveryMin)) } }, label = { "$it deletes" })
                    SliderRow("Breather lasts (min)", s.speed.breatherSecMin, 5..300, { v -> speed { it.copy(breatherSecMin = v, breatherSecMax = maxOf(it.breatherSecMax, v)) } }, step = 5, label = { "$it s" })
                    SliderRow("Breather lasts (max)", s.speed.breatherSecMax, 5..600, { v -> speed { it.copy(breatherSecMax = maxOf(v, it.breatherSecMin)) } }, step = 5, label = { "$it s" })
                }
                SliderRow("Hourly cap", s.speed.hourlyCap, 0..500, { v -> speed { it.copy(hourlyCap = v) } }, "Hard stop per rolling hour. 0 = off.", step = 10, label = { if (it == 0) "off" else "$it" })
                SliderRow("Daily cap", s.speed.dailyCap, 0..5000, { v -> speed { it.copy(dailyCap = v) } }, "Hard stop per rolling 24 h. 0 = off.", step = 50, label = { if (it == 0) "off" else "$it" })
                SliderRow("Scan budget per window", s.speed.readBudget, 10..200, { v -> speed { it.copy(readBudget = v) } }, "Timeline page reads.")
                SliderRow("Verify budget per window", s.speed.verifyBudget, 20..400, { v -> speed { it.copy(verifyBudget = v) } }, "Confirmation reads.", step = 10)
                GhostButton("Reset learned limits", Modifier.fillMaxWidth()) { vm.resetLearnedLimits() }
            }
        }

        item {
            Section("Safety", "Stops and guards that protect the account") {
                SwitchRow("Stop if X says \"automated\"", "Error 226. Safer to stop and wait than to push on.", s.safety.stopOnAutomationFlag) { v -> safety { it.copy(stopOnAutomationFlag = v) } }
                if (!s.safety.stopOnAutomationFlag) {
                    SliderRow("Otherwise cool down for", s.safety.automationCooldownHours, 1..48, { v -> safety { it.copy(automationCooldownHours = v) } }, label = { "$it h" })
                }
                SliderRow("Errors in a row before a 10 min pause", s.safety.maxConsecutiveErrors, 2..30, { v -> safety { it.copy(maxConsecutiveErrors = v) } })
                ChoiceRow(
                    "Delete order",
                    listOf(DeleteOrder.OLDEST_FIRST to "Oldest first", DeleteOrder.NEWEST_FIRST to "Newest first", DeleteOrder.RANDOM to "Random"),
                    s.safety.order, { v -> safety { it.copy(order = v) } },
                )
                SwitchRow("Only on Wi-Fi", checked = s.safety.wifiOnly) { v -> safety { it.copy(wifiOnly = v) } }
                SwitchRow("Only on unmetered networks", checked = s.safety.unmeteredOnly) { v -> safety { it.copy(unmeteredOnly = v) } }
                SwitchRow("Only while charging", checked = s.safety.chargingOnly) { v -> safety { it.copy(chargingOnly = v) } }
                SliderRow("Pause below battery", s.safety.minBatteryPct, 0..80, { v -> safety { it.copy(minBatteryPct = v) } }, step = 5, label = { if (it == 0) "off" else "$it%" })
                SwitchRow("Pause in battery saver", checked = s.safety.pauseInBatterySaver) { v -> safety { it.copy(pauseInBatterySaver = v) } }
            }
        }

        item {
            Section("Background behaviour", "How it survives screen-off and closing the app") {
                SliderRow(
                    "Hand long waits to an alarm after", s.safety.parkAfterSec, 30..900, { v -> safety { it.copy(parkAfterSec = v) } },
                    "Waits longer than this release the foreground service and wake up on an exact alarm.", step = 30,
                    label = { if (it >= 60) "${it / 60} min ${it % 60}s" else "$it s" },
                )
                SwitchRow("Keep CPU awake during short waits", "Prevents Doze from stalling the pacing timer.", s.safety.keepCpuAwake) { v -> safety { it.copy(keepCpuAwake = v) } }
                SwitchRow("Resume after reboot", "Picks up an unfinished run when the phone restarts.", s.general.resumeAfterReboot) { v -> general { it.copy(resumeAfterReboot = v) } }
            }
        }

        item {
            Section("Schedule", "Only delete during certain hours") {
                ChoiceRow(
                    "When to run", listOf(ScheduleMode.ALWAYS to "Any time", ScheduleMode.WINDOW to "Only in a window"),
                    s.schedule.mode, { v -> sched { it.copy(mode = v) } },
                )
                if (s.schedule.mode == ScheduleMode.WINDOW) {
                    TimeRow("From", s.schedule.startMinute) { v -> sched { it.copy(startMinute = v) } }
                    TimeRow("Until", s.schedule.endMinute) { v -> sched { it.copy(endMinute = v) } }
                    DayChips(s.schedule.daysMask) { v -> sched { it.copy(daysMask = v) } }
                    Hint("Overnight windows work (e.g. 22:00 to 06:00). Outside the window the run waits and resumes by itself.")
                }
            }
        }

        item {
            Section("Verification", "Nothing counts as deleted until a real check confirms it") {
                SliderRow("Check after (min)", s.verify.delayMinMs, 200..5000, { v -> verify { it.copy(delayMinMs = v, delayMaxMs = maxOf(it.delayMaxMs, v)) } }, step = 100, label = { "$it ms" })
                SliderRow("Check after (max)", s.verify.delayMaxMs, 200..8000, { v -> verify { it.copy(delayMaxMs = maxOf(v, it.delayMinMs)) } }, step = 100, label = { "$it ms" })
                SliderRow("Checks before giving up a round", s.verify.checksPerItem, 1..8, { v -> verify { it.copy(checksPerItem = v) } })
                SliderRow("Wait between checks", s.verify.retryDelaySec, 5..300, { v -> verify { it.copy(retryDelaySec = v) } }, step = 5, label = { "$it s" })
                SliderRow("Mark failed after attempts", s.verify.failAfterAttempts, 1..10, { v -> verify { it.copy(failAfterAttempts = v) } })
                SwitchRow("Look before deleting (archive items)", "Skips posts that are already gone so no delete budget is wasted.", s.verify.precheckArchive) { v -> verify { it.copy(precheckArchive = v) } }
                SwitchRow("\"Unavailable\" counts as gone", checked = s.verify.unavailableIsGone) { v -> verify { it.copy(unavailableIsGone = v) } }
            }
        }

        item {
            Section("Scanning", "How your posts are pulled") {
                ChoiceRow(
                    "Scan button does", listOf(ScanMode.SCAN_ONLY to "Scan, then let me review", ScanMode.SCAN_AND_DELETE to "Scan and delete straight away"),
                    s.scan.mode, { v -> scan { it.copy(mode = v) } },
                )
                SliderRow("Page size", s.scan.pageSize, 10..100, { v -> scan { it.copy(pageSize = v) } }, step = 10)
                SliderRow("Stop after", s.scan.maxItems, 0..20000, { v -> scan { it.copy(maxItems = v) } }, step = 500, label = { if (it == 0) "everything" else "$it items" })
                SwitchRow("Incremental rescans", "Stop when a rescan reaches posts it already knows.", s.scan.incremental) { v -> scan { it.copy(incremental = v) } }
                Hint("Tip: X's timeline reaches back about 3,200 posts. For older ones, import your data archive from the Posts tab.")
            }
        }

        item {
            Section("Notifications", "Live progress and alerts") {
                SwitchRow("Live progress notification", "The ongoing card with progress bar and countdown.", s.notify.liveProgress) { v -> notify { it.copy(liveProgress = v) } }
                SliderRow("Update every", s.notify.updateIntervalSec, 1..30, { v -> notify { it.copy(updateIntervalSec = v) } }, label = { "$it s" })
                SwitchRow("Show time left", checked = s.notify.showEta) { v -> notify { it.copy(showEta = v) } }
                SwitchRow("Show deletes per hour", checked = s.notify.showRate) { v -> notify { it.copy(showRate = v) } }
                SwitchRow("Alert when finished", checked = s.notify.alertOnDone) { v -> notify { it.copy(alertOnDone = v) } }
                SwitchRow("Alert when rate-limited", "Only when it has to wait a long time.", s.notify.alertOnCooldown) { v -> notify { it.copy(alertOnCooldown = v) } }
                SwitchRow("Alert when it needs me", "Login expired, account locked, automation flag.", s.notify.alertOnAttention) { v -> notify { it.copy(alertOnAttention = v) } }
                SwitchRow("Show on lock screen", checked = s.notify.showOnLockScreen) { v -> notify { it.copy(showOnLockScreen = v) } }
            }
        }

        item {
            Section("Appearance") {
                ChoiceRow("Theme", listOf(ThemeMode.SYSTEM to "System", ThemeMode.LIGHT to "Light", ThemeMode.DARK to "Dark"), s.general.theme, { v -> general { it.copy(theme = v) } })
                SwitchRow("Dynamic colour", "Use your wallpaper colours on Android 12+.", s.general.dynamicColor) { v -> general { it.copy(dynamicColor = v) } }
                SwitchRow("Confirm before starting", checked = s.general.confirmBeforeStart) { v -> general { it.copy(confirmBeforeStart = v) } }
                SwitchRow("Dry run preview mode", "Simulate sweeps without sending any delete requests to X.", s.general.dryRun) { v -> general { it.copy(dryRun = v) } }
                Hint("Widgets: long-press the home screen, Widgets, Post Sweeper. Three sizes: progress, dashboard with pause/resume, and full.")
            }
        }

        item {
            Section("Data & backup") {
                GhostButton("Export settings", Modifier.fillMaxWidth()) { exportSettings.launch("post-sweeper-settings.json") }
                GhostButton("Import settings", Modifier.fillMaxWidth()) { importSettings.launch(arrayOf("application/json", "*/*")) }
                GhostButton("Export deleted list (CSV)", Modifier.fillMaxWidth()) { exportDeleted.launch("deleted.csv") }
                GhostButton("Export activity log (CSV)", Modifier.fillMaxWidth()) { exportLog.launch("sweeper-log.csv") }
                GhostButton("Clear scanned items", Modifier.fillMaxWidth()) { clearConfirm = true }
            }
        }

        item {
            Section("Diagnostics", "Logs and connection tools") {
                GhostButton("View activity log", Modifier.fillMaxWidth()) { openLogs() }
                GhostButton("Check for updates", Modifier.fillMaxWidth()) { vm.checkUpdate(manual = true) }
                GhostButton("Test connection", Modifier.fillMaxWidth()) { vm.testConnection() }
                GhostButton("Refresh X endpoints", Modifier.fillMaxWidth()) { vm.refreshEndpoints() }
                SliderRow("Keep log lines", s.general.logKeep, 500..20000, { v -> general { it.copy(logKeep = v) } }, step = 500)
                if (BuildConfig.DEBUG) {
                    TextRow("API origin (debug builds)", s.general.apiOrigin, { vm.setOrigin(it) }, "Point at a mock server, e.g. http://10.0.2.2:8787")
                }
                Hint("Post Sweeper ${BuildConfig.VERSION_NAME}. Requests go through an in-app browser on x.com, as the website would send them.")
            }
        }
    }

    if (clearConfirm) {
        AlertDialog(
            onDismissRequest = { clearConfirm = false },
            title = { Text("Clear scanned items?") },
            text = { Text("Removes everything not yet deleted. Verified deletions stay.") },
            confirmButton = { TextButton({ clearConfirm = false; vm.clearScanned() }) { Text("Clear") } },
            dismissButton = { TextButton({ clearConfirm = false }) { Text("Cancel") } },
        )
    }
}

@Composable
fun LogScreen(vm: MainViewModel, onBack: () -> Unit) {
    val logs by vm.logs.collectAsStateWithLifecycle()
    androidx.activity.compose.BackHandler(onBack = onBack)
    val fmt = remember { SimpleDateFormat("MMM d HH:mm:ss", Locale.getDefault()) }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
            TextButton(onBack) { Text("← Back") }
            Text("Activity log", Modifier.padding(top = 10.dp), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
        LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (logs.isEmpty()) item { Hint("Nothing logged yet.") }
            items(logs, key = { it.id }) { l ->
                val c: Color = when (l.level) {
                    3 -> MaterialTheme.colorScheme.error
                    2 -> Warn
                    else -> MaterialTheme.colorScheme.onSurface
                }
                Column {
                    Text("${fmt.format(Date(l.ts))}  ${l.tag}", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, fontFamily = FontFamily.Monospace)
                    Text(l.msg, fontSize = 12.sp, color = c)
                }
            }
        }
    }
}

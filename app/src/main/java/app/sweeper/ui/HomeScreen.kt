package app.sweeper.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.sweeper.data.AttentionKind
import app.sweeper.data.Desired
import app.sweeper.data.PostType
import app.sweeper.data.RunPhase
import app.sweeper.net.UpdateStatus
import app.sweeper.ui.theme.Good
import app.sweeper.ui.theme.Warn
import kotlinx.coroutines.delay
import java.text.NumberFormat

private val nf = NumberFormat.getIntegerInstance()

@Composable
fun rememberNow(): State<Long> = produceState(System.currentTimeMillis()) {
    while (true) {
        delay(1000)
        value = System.currentTimeMillis()
    }
}

private fun countdown(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

@Composable
fun HomeScreen(vm: MainViewModel) {
    val ctx = LocalContext.current
    val snap by vm.snapshot.collectAsStateWithLifecycle()
    val counts by vm.counts.collectAsStateWithLifecycle()
    val will by vm.willDelete.collectAsStateWithLifecycle()
    val account by vm.account.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val updateStatus by vm.updateStatus.collectAsStateWithLifecycle()
    val now by rememberNow()
    var confirm by remember { mutableStateOf(false) }
    var confirmLogout by remember { mutableStateOf(false) }

    val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
    val ignoring = pm.isIgnoringBatteryOptimizations(ctx.packageName)
    val notifOk = Build.VERSION.SDK_INT < 33 || androidx.core.content.ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED

    val queued = counts.queued
    val canDelete = will + queued > 0
    val perWindow = (if (snap.windowBudget > 0) snap.windowBudget else settings.speed.budget).coerceAtLeast(1)
    val etaFor = { n: Int -> n.toLong() * settings.speed.windowMinutes * 60_000L / perWindow }

    LazyColumn(
        Modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Post Sweeper", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("@${account?.account?.handle ?: ""}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = { confirmLogout = true }) { Text("Log out") }
            }
        }

        if (updateStatus is UpdateStatus.Available) {
            val info = (updateStatus as UpdateStatus.Available).info
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Update Available: ${info.tagName}", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            IconButton(onClick = { vm.dismissUpdate() }) {
                                Icon(Icons.Filled.Close, contentDescription = "Dismiss")
                            }
                        }
                        Text("A new release of PostSweeper is available on GitHub.", style = MaterialTheme.typography.bodyMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PrimaryButton("Download APK", Modifier.weight(1f)) {
                                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(info.apkDownloadUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            }
                            GhostButton("Release Notes", Modifier.weight(1f)) {
                                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(info.htmlUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            }
                        }
                    }
                }
            }
        }

        item {
            Panel {
                val color = when (snap.phase) {
                    RunPhase.COOLDOWN, RunPhase.PARKED, RunPhase.WAITING, RunPhase.PAUSED -> Warn
                    RunPhase.ATTENTION -> MaterialTheme.colorScheme.error
                    else -> Good
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Dot(color)
                    Text(snap.phase.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.weight(1f))
                    if (snap.resumeAt > now && snap.phase in setOf(RunPhase.COOLDOWN, RunPhase.PARKED, RunPhase.WAITING)) {
                        Text("resumes in ${countdown(snap.resumeAt - now)}", color = Warn, fontWeight = FontWeight.SemiBold)
                    }
                }
                if (snap.message.isNotBlank()) Text(snap.message, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (snap.runTotal > 0) {
                    LinearProgressIndicator(progress = { snap.progress }, Modifier.fillMaxWidth().height(8.dp), color = Good)
                    Row {
                        Text("${nf.format(snap.runDeleted)} of ${nf.format(snap.runTotal)} verified deleted", Modifier.weight(1f))
                        Text("${(snap.progress * 100).toInt()}%", fontWeight = FontWeight.Bold)
                    }
                    if (snap.desired == Desired.ACTIVE && snap.etaMs > 0) Text("≈ ${fmtDur(snap.etaMs)} left at the current pace", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else if (snap.phase == RunPhase.SCANNING) {
                    LinearProgressIndicator(Modifier.fillMaxWidth().height(8.dp), color = Good)
                    Text("${nf.format(snap.scanned)} found so far")
                }
                if (snap.phase == RunPhase.ATTENTION) {
                    Text(snap.attentionText, color = MaterialTheme.colorScheme.error)
                    if (snap.attention == AttentionKind.LOGIN) Text("Log out and log back in to refresh the session.", style = MaterialTheme.typography.bodySmall)
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Dry run preview", fontWeight = FontWeight.SemiBold)
                        Text(
                            if (settings.general.dryRun) "Simulation active: preview matches without deleting" else "Live mode: deletions will permanently erase posts",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (settings.general.dryRun) Warn else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = settings.general.dryRun,
                        onCheckedChange = { vm.setDryRun(it) }
                    )
                }

                Spacer(Modifier.height(2.dp))
                when (snap.desired) {
                    Desired.ACTIVE -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PrimaryButton("Pause", Modifier.weight(1f)) { vm.pause() }
                        GhostButton("Stop", Modifier.weight(1f)) { vm.stop() }
                    }
                    Desired.PAUSED -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PrimaryButton(if (snap.phase == RunPhase.ATTENTION) "Resume anyway" else "Resume", Modifier.weight(1f)) { vm.resume() }
                        GhostButton("Stop", Modifier.weight(1f)) { vm.stop() }
                    }
                    Desired.STOPPED -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        GhostButton(if (counts.total == 0) "Scan my posts" else "Rescan", Modifier.weight(1f)) { vm.scan() }
                        PrimaryButton(
                            if (!canDelete) "Nothing to delete"
                            else if (settings.general.dryRun) "Preview ${nf.format(will + queued)} (Dry Run)"
                            else "Delete ${nf.format(will + queued)}",
                            Modifier.weight(1f), enabled = canDelete,
                        ) { if (settings.general.confirmBeforeStart) confirm = true else vm.startDeleting() }
                    }
                }
            }
        }

        if (!notifOk || !ignoring) {
            item {
                Panel {
                    Text("Keep it running in the background", fontWeight = FontWeight.SemiBold)
                    Hint("For a smooth, uninterrupted run, allow notifications and exempt the app from battery optimisation.")
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (!notifOk && Build.VERSION.SDK_INT >= 33) GhostButton("Allow notifications", Modifier.weight(1f)) { notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS) }
                        if (!ignoring) GhostButton("Battery: unrestricted", Modifier.weight(1f)) {
                            ctx.startActivity(
                                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                    }
                }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile("Will delete", nf.format(will), MaterialTheme.colorScheme.onSurface, Modifier.weight(1f))
                StatTile("Queued", nf.format(queued), MaterialTheme.colorScheme.onSurface, Modifier.weight(1f))
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile("Deleted ✓ (verified)", nf.format(counts.verified), Good, Modifier.weight(1f))
                StatTile("Failed", nf.format(counts.failed), if (counts.failed > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface, Modifier.weight(1f))
            }
        }
        if (counts.failed > 0 && snap.desired == Desired.STOPPED) {
            item { GhostButton("Retry ${counts.failed} failed", Modifier.fillMaxWidth()) { vm.retryFailed() } }
        }

        item {
            Panel {
                Text("Rate-limit brain", fontWeight = FontWeight.SemiBold)
                val used = snap.windowUsed
                val budget = if (snap.windowBudget > 0) snap.windowBudget else settings.speed.budget
                Row {
                    Text("This window", Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("$used / $budget  ·  ${settings.speed.windowMinutes} min")
                }
                LinearProgressIndicator(progress = { (used.toFloat() / budget.coerceAtLeast(1)).coerceIn(0f, 1f) }, Modifier.fillMaxWidth().height(6.dp), color = Warn)
                Row {
                    Text("Learned limit", Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("$budget per ${settings.speed.windowMinutes} min")
                }
                Row {
                    Text("Limits hit", Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${snap.cooldownsHit}×")
                }
                Row {
                    Text("Last hour", Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${snap.ratePerHour.toInt()} deleted")
                }
                Hint("It spreads requests over each window, obeys X's own limit headers, backs off with random slack when limited, and never sends while cooling down.")
            }
        }

        item {
            Panel {
                Text("By type", fontWeight = FontWeight.SemiBold)
                PostType.entries.forEach { t ->
                    val total = counts.n(t, *app.sweeper.data.ItemStatus.entries.toTypedArray())
                    if (total > 0) Row {
                        Text(t.plural, Modifier.weight(1f))
                        Text("${nf.format(counts.n(t, app.sweeper.data.ItemStatus.VERIFIED))} deleted / ${nf.format(total)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (counts.total == 0) Hint("Nothing scanned yet. Pick what to delete in Filters, then tap Scan.")
            }
        }
    }

    if (confirm) {
        val n = will + queued
        val isDry = settings.general.dryRun
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(if (isDry) "Run simulation preview ($n items)?" else "Delete ${nf.format(n)} items?") },
            text = {
                Text(
                    if (isDry) "Dry run mode will test all your filter rules and queue matching posts without sending any delete requests to X. Zero posts will be deleted."
                    else "This cannot be undone. At the current pace it takes about ${fmtDur(etaFor(n))}. " +
                        "It keeps going in the background and each item is only counted after X confirms it is gone.",
                )
            },
            confirmButton = { TextButton({ confirm = false; vm.startDeleting() }) { Text(if (isDry) "Run preview" else "Start") } },
            dismissButton = { TextButton({ confirm = false }) { Text("Cancel") } },
        )
    }
    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            title = { Text("Log out?") },
            text = { Text("This stops any run and wipes the session. Keep the scanned list for later?") },
            confirmButton = { TextButton({ confirmLogout = false; vm.logout(false) }) { Text("Log out, keep list") } },
            dismissButton = { TextButton({ confirmLogout = false; vm.logout(true) }) { Text("Log out, clear all") } },
        )
    }
}

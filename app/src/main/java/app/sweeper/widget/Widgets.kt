package app.sweeper.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.Button
import androidx.glance.ButtonDefaults
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import app.sweeper.Graph
import app.sweeper.data.Desired
import app.sweeper.data.ItemStatus
import app.sweeper.data.PostType
import app.sweeper.data.RunMode
import app.sweeper.data.RunPhase
import app.sweeper.data.RunSnapshot
import app.sweeper.service.ServiceController
import app.sweeper.ui.MainActivity
import kotlinx.coroutines.flow.first
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class WidgetData(
    val snap: RunSnapshot,
    val verified: Int,
    val queued: Int,
    val failed: Int,
    val willDelete: Int,
    val verifiedByType: Map<PostType, Int>,
    val recent: List<String>,
)

suspend fun loadWidgetData(): WidgetData {
    val dao = Graph.db.posts()
    val rows = runCatching { dao.observeCounts().first() }.getOrDefault(emptyList())
    fun sum(vararg st: ItemStatus) = rows.filter { it.status in st }.sumOf { it.n }
    val recent = runCatching { dao.recentVerified(5) }.getOrDefault(emptyList())
        .map { it.text.replace('\n', ' ').take(46).ifBlank { it.type.label + " " + it.id } }
    return WidgetData(
        snap = Graph.bus.snapshot.value,
        verified = sum(ItemStatus.VERIFIED),
        queued = sum(ItemStatus.QUEUED, ItemStatus.DELETING, ItemStatus.SENT),
        failed = sum(ItemStatus.FAILED),
        willDelete = runCatching { dao.willDelete() }.getOrDefault(0),
        verifiedByType = rows.filter { it.status == ItemStatus.VERIFIED }.groupBy { it.type }.mapValues { e -> e.value.sumOf { it.n } },
        recent = recent,
    )
}

object WidgetUpdater {
    suspend fun push(ctx: Context) {
        runCatching { SmallWidget().updateAll(ctx) }
        runCatching { WideWidget().updateAll(ctx) }
        runCatching { LargeWidget().updateAll(ctx) }
    }
}

private val Bg = ColorProvider(Color(0xFF12161C))
private val Card = ColorProvider(Color(0xFF1B212A))
private val Accent = ColorProvider(Color(0xFF12B886))
private val Hi = ColorProvider(Color(0xFFF1F3F5))
private val Lo = ColorProvider(Color(0xFF9AA4B2))
private val Warn = ColorProvider(Color(0xFFFFA94D))
private val Bad = ColorProvider(Color(0xFFFF6B6B))
private val OnAccent = ColorProvider(Color(0xFF04261C))

private val nf = NumberFormat.getIntegerInstance()
private fun hhmm(ms: Long) = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))

private fun statusText(s: RunSnapshot): String = when (s.phase) {
    RunPhase.IDLE -> "Idle"
    RunPhase.SCANNING -> "Scanning · ${nf.format(s.scanned)} found"
    RunPhase.DELETING, RunPhase.PACING -> "Deleting"
    RunPhase.COOLDOWN -> "Rate-limited · back ${hhmm(s.resumeAt)}"
    RunPhase.PARKED -> "Next window ${hhmm(s.resumeAt)}"
    RunPhase.WAITING -> "Waiting · ${s.message}"
    RunPhase.PAUSED -> "Paused"
    RunPhase.ATTENTION -> "Needs attention"
    RunPhase.DONE -> "Done"
}

private fun statusColor(s: RunSnapshot): ColorProvider = when (s.phase) {
    RunPhase.COOLDOWN, RunPhase.PARKED, RunPhase.WAITING, RunPhase.PAUSED -> Warn
    RunPhase.ATTENTION -> Bad
    else -> Accent
}

private fun eta(ms: Long): String {
    val m = ms / 60_000
    return when {
        ms <= 0 -> ""
        m >= 60 * 48 -> "≈ ${m / 1440}d"
        m >= 60 -> "≈ ${m / 60}h ${m % 60}m"
        else -> "≈ ${maxOf(1, m)}m"
    }
}

private fun pct(d: WidgetData): Int {
    val s = d.snap
    return if (s.runTotal > 0) (s.progress * 100).toInt() else 0
}

private fun progressOf(d: WidgetData): Float = if (d.snap.runTotal > 0) d.snap.progress else 0f

private fun countsLine(d: WidgetData): String =
    if (d.snap.runTotal > 0) "${nf.format(d.snap.runDeleted)} / ${nf.format(d.snap.runTotal)}"
    else "${nf.format(d.verified)} deleted · ${nf.format(d.queued)} queued"

@Composable
private fun Label(text: String, color: ColorProvider = Lo, size: Int = 11, bold: Boolean = false) {
    Text(text, style = TextStyle(color = color, fontSize = size.sp, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal), maxLines = 1)
}

@Composable
private fun ControlButton(d: WidgetData) {
    val (label, show) = when (d.snap.desired) {
        Desired.ACTIVE -> "Pause" to true
        Desired.PAUSED -> "Resume" to true
        Desired.STOPPED -> (if (d.queued > 0 || d.willDelete > 0) "Start" else "") to (d.queued > 0 || d.willDelete > 0)
    }
    if (show) {
        Button(
            text = label,
            onClick = actionRunCallback<PauseResumeAction>(),
            colors = ButtonDefaults.buttonColors(backgroundColor = Accent, contentColor = OnAccent),
        )
    }
}

// ------------------------------------------------------------------------------------------- small

class SmallWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Single
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val d = loadWidgetData()
        provideContent {
            Column(
                modifier = GlanceModifier.fillMaxSize().background(Bg).cornerRadius(22.dp).padding(12.dp)
                    .clickable(actionStartActivity<MainActivity>()),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Label("POST SWEEPER", Lo, 10, true)
                Spacer(GlanceModifier.height(2.dp))
                Text(
                    if (d.snap.runTotal > 0) "${pct(d)}%" else nf.format(d.verified),
                    style = TextStyle(color = Hi, fontSize = 30.sp, fontWeight = FontWeight.Bold),
                    maxLines = 1,
                )
                Spacer(GlanceModifier.height(4.dp))
                LinearProgressIndicator(progress = progressOf(d), modifier = GlanceModifier.fillMaxWidth().height(6.dp), color = Accent, backgroundColor = Card)
                Spacer(GlanceModifier.height(6.dp))
                Label(countsLine(d), Hi, 11)
                Label(statusText(d.snap), statusColor(d.snap), 11, true)
            }
        }
    }
}

class SmallWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = SmallWidget()
}

// ------------------------------------------------------------------------------------------- wide

class WideWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Single
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val d = loadWidgetData()
        provideContent {
            Row(
                modifier = GlanceModifier.fillMaxSize().background(Bg).cornerRadius(22.dp).padding(14.dp)
                    .clickable(actionStartActivity<MainActivity>()),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = GlanceModifier.defaultWeight()) {
                    Label("POST SWEEPER", Lo, 10, true)
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            if (d.snap.runTotal > 0) "${pct(d)}%" else nf.format(d.verified),
                            style = TextStyle(color = Hi, fontSize = 28.sp, fontWeight = FontWeight.Bold), maxLines = 1,
                        )
                        Spacer(GlanceModifier.width(8.dp))
                        Label(if (d.snap.runTotal > 0) countsLine(d) else "deleted", Lo, 12)
                    }
                    Spacer(GlanceModifier.height(4.dp))
                    LinearProgressIndicator(progress = progressOf(d), modifier = GlanceModifier.fillMaxWidth().height(6.dp), color = Accent, backgroundColor = Card)
                    Spacer(GlanceModifier.height(6.dp))
                    Label(statusText(d.snap), statusColor(d.snap), 12, true)
                    val extra = buildList {
                        eta(d.snap.etaMs).takeIf { it.isNotEmpty() && d.snap.desired == Desired.ACTIVE }?.let { add("$it left") }
                        if (d.snap.windowBudget > 0) add("window ${d.snap.windowUsed}/${d.snap.windowBudget}")
                        if (d.failed > 0) add("${d.failed} failed")
                    }.joinToString(" · ")
                    if (extra.isNotEmpty()) Label(extra, Lo, 11)
                }
                Spacer(GlanceModifier.width(10.dp))
                ControlButton(d)
            }
        }
    }
}

class WideWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = WideWidget()
}

// ------------------------------------------------------------------------------------------- large

class LargeWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Single
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val d = loadWidgetData()
        provideContent {
            Column(
                modifier = GlanceModifier.fillMaxSize().background(Bg).cornerRadius(22.dp).padding(14.dp)
                    .clickable(actionStartActivity<MainActivity>()),
            ) {
                Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = GlanceModifier.defaultWeight()) {
                        Label("POST SWEEPER", Lo, 10, true)
                        Label(statusText(d.snap), statusColor(d.snap), 13, true)
                    }
                    ControlButton(d)
                }
                Spacer(GlanceModifier.height(8.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        if (d.snap.runTotal > 0) "${pct(d)}%" else nf.format(d.verified),
                        style = TextStyle(color = Hi, fontSize = 34.sp, fontWeight = FontWeight.Bold), maxLines = 1,
                    )
                    Spacer(GlanceModifier.width(8.dp))
                    Label(if (d.snap.runTotal > 0) countsLine(d) else "verified deleted", Lo, 13)
                }
                Spacer(GlanceModifier.height(4.dp))
                LinearProgressIndicator(progress = progressOf(d), modifier = GlanceModifier.fillMaxWidth().height(8.dp), color = Accent, backgroundColor = Card)
                Spacer(GlanceModifier.height(10.dp))
                Row(modifier = GlanceModifier.fillMaxWidth()) {
                    Stat("Queued", nf.format(d.queued), Hi, GlanceModifier.defaultWeight())
                    Stat("Deleted", nf.format(d.verified), Accent, GlanceModifier.defaultWeight())
                    Stat("Failed", nf.format(d.failed), if (d.failed > 0) Bad else Hi, GlanceModifier.defaultWeight())
                    Stat("Per hour", nf.format(d.snap.ratePerHour.toInt()), Hi, GlanceModifier.defaultWeight())
                }
                Spacer(GlanceModifier.height(8.dp))
                val win = if (d.snap.windowBudget > 0) "Window ${d.snap.windowUsed}/${d.snap.windowBudget} (${d.snap.windowMinutes} min)" else "No window used yet"
                val lim = if (d.snap.cooldownsHit > 0) " · limits hit ${d.snap.cooldownsHit}×" else ""
                Label(win + lim, Lo, 11)
                eta(d.snap.etaMs).takeIf { it.isNotEmpty() && d.snap.desired == Desired.ACTIVE }?.let { Label("$it left at the current pace", Lo, 11) }
                val types = d.verifiedByType.entries.sortedByDescending { it.value }.joinToString("  ") { "${it.key.plural} ${nf.format(it.value)}" }
                if (types.isNotEmpty()) {
                    Spacer(GlanceModifier.height(6.dp))
                    Label(types, Hi, 11)
                }
                if (d.recent.isNotEmpty()) {
                    Spacer(GlanceModifier.height(8.dp))
                    Label("RECENTLY DELETED", Lo, 10, true)
                    d.recent.take(4).forEach { Label("✓ $it", Hi, 11) }
                }
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String, color: ColorProvider, modifier: GlanceModifier) {
    Column(modifier = modifier) {
        Text(value, style = TextStyle(color = color, fontSize = 17.sp, fontWeight = FontWeight.Bold), maxLines = 1)
        Label(label, Lo, 10)
    }
}

class LargeWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = LargeWidget()
}

// ------------------------------------------------------------------------------------------- actions

class PauseResumeAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val s = Graph.bus.snapshot.value
        runCatching {
            when (s.desired) {
                Desired.ACTIVE -> ServiceController.pause(context)
                Desired.PAUSED -> ServiceController.resume(context)
                Desired.STOPPED -> {
                    if (Graph.db.posts().willDelete() > 0) Graph.db.posts().queueMatching()
                    ServiceController.start(context, RunMode.DELETE)
                }
            }
        }
        WidgetUpdater.push(context)
    }
}

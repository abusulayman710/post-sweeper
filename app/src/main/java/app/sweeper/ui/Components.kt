package app.sweeper.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.sweeper.data.TriState
import java.text.DateFormat
import java.util.Date

@Composable
fun Panel(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { content() }
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        modifier = modifier.padding(top = 6.dp),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
    )
}

@Composable
fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Collapsible group, used to keep the long settings list scannable. */
@Composable
fun Section(title: String, subtitle: String = "", startOpen: Boolean = false, content: @Composable () -> Unit) {
    var open by remember { mutableStateOf(startOpen) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().clickable { open = !open }.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    if (subtitle.isNotEmpty()) Hint(subtitle)
                }
                Icon(if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, null)
            }
            if (open) Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) { content() }
        }
    }
}

@Composable
fun SwitchRow(title: String, subtitle: String = "", checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle.isNotEmpty()) Hint(subtitle)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked, onChange)
    }
}

@Composable
fun SliderRow(
    title: String,
    value: Int,
    range: IntRange,
    onChange: (Int) -> Unit,
    subtitle: String = "",
    step: Int = 1,
    label: (Int) -> String = { it.toString() },
) {
    var local by remember(value) { mutableStateOf(value.toFloat()) }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Text(label(local.toInt()), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
        }
        if (subtitle.isNotEmpty()) Hint(subtitle)
        val steps = ((range.last - range.first) / step - 1).coerceAtLeast(0)
        Slider(
            value = local,
            onValueChange = { local = (Math.round(it / step) * step).toFloat() },
            onValueChangeFinished = { onChange(local.toInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = steps,
        )
    }
}

@Composable
fun <T> ChoiceRow(title: String, options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, subtitle: String = "") {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        if (subtitle.isNotEmpty()) Hint(subtitle)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { (v, l) -> FilterChip(selected = v == selected, onClick = { onSelect(v) }, label = { Text(l) }) }
        }
    }
}

@Composable
fun TriRow(title: String, value: TriState, onChange: (TriState) -> Unit, yes: String = "Yes", no: String = "No") {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(value == TriState.ANY, { onChange(TriState.ANY) }, { Text("Any") })
            FilterChip(value == TriState.YES, { onChange(TriState.YES) }, { Text(yes) })
            FilterChip(value == TriState.NO, { onChange(TriState.NO) }, { Text(no) })
        }
    }
}

@Composable
fun TextRow(
    title: String,
    value: String,
    onChange: (String) -> Unit,
    hint: String = "",
    singleLine: Boolean = true,
    numeric: Boolean = false,
) {
    var local by remember(title) { mutableStateOf(value) }
    OutlinedTextField(
        value = local,
        onValueChange = { local = it; onChange(it) },
        label = { Text(title) },
        supportingText = if (hint.isNotEmpty()) ({ Text(hint) }) else null,
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 2,
        keyboardOptions = if (numeric) KeyboardOptions(keyboardType = KeyboardType.Number) else KeyboardOptions.Default,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
fun NumberRow(title: String, value: Long, onChange: (Long) -> Unit, hint: String = "") {
    TextRow(title, if (value == 0L) "" else value.toString(), { onChange(it.filter(Char::isDigit).toLongOrNull() ?: 0L) }, hint, numeric = true)
}

@Composable
fun DateRow(title: String, ms: Long, onChange: (Long) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        TextButton(onClick = { open = true }) {
            Text(if (ms == 0L) "Not set" else DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(ms)))
        }
        if (ms != 0L) TextButton(onClick = { onChange(0) }) { Text("Clear") }
    }
    if (open) {
        val state = rememberDatePickerState(initialSelectedDateMillis = if (ms == 0L) System.currentTimeMillis() else ms)
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = { TextButton({ state.selectedDateMillis?.let(onChange); open = false }) { Text("OK") } },
            dismissButton = { TextButton({ open = false }) { Text("Cancel") } },
        ) { DatePicker(state) }
    }
}

@Composable
fun TimeRow(title: String, minuteOfDay: Int, onChange: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        TextButton(onClick = { open = true }) { Text("%02d:%02d".format(minuteOfDay / 60, minuteOfDay % 60)) }
    }
    if (open) {
        val state = rememberTimePickerState(minuteOfDay / 60, minuteOfDay % 60, true)
        AlertDialog(
            onDismissRequest = { open = false },
            confirmButton = { TextButton({ onChange(state.hour * 60 + state.minute); open = false }) { Text("OK") } },
            dismissButton = { TextButton({ open = false }) { Text("Cancel") } },
            text = { TimePicker(state) },
        )
    }
}

@Composable
fun DayChips(mask: Int, onChange: (Int) -> Unit) {
    val names = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        names.forEachIndexed { i, n ->
            val on = (mask shr i) and 1 == 1
            FilterChip(on, { onChange(mask xor (1 shl i)) }, { Text(n) })
        }
    }
}

@Composable
fun StatTile(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = color)
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun PrimaryButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled, modifier = modifier.height(48.dp), shape = RoundedCornerShape(14.dp)) { Text(text) }
}

@Composable
fun GhostButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    androidx.compose.material3.OutlinedButton(onClick = onClick, enabled = enabled, modifier = modifier.height(48.dp), shape = RoundedCornerShape(14.dp)) { Text(text) }
}

@Composable
fun Dot(color: Color) {
    Box(Modifier.padding(end = 6.dp).width(8.dp).height(8.dp).background(color, RoundedCornerShape(50)))
}

fun fmtDateTime(ms: Long): String = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(ms))
fun fmtDate(ms: Long): String = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(ms))
fun fmtDur(ms: Long): String {
    val m = ms / 60_000
    return when {
        ms <= 0 -> "—"
        m >= 60 * 48 -> "${m / 1440}d ${(m / 60) % 24}h"
        m >= 60 -> "${m / 60}h ${m % 60}m"
        m >= 1 -> "${m}m"
        else -> "<1m"
    }
}

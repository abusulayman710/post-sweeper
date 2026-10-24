package app.sweeper.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.sweeper.data.ItemStatus
import app.sweeper.data.PostEntity
import app.sweeper.data.PostType
import app.sweeper.ui.theme.Good
import app.sweeper.ui.theme.Warn
import java.text.NumberFormat

private val nf = NumberFormat.getIntegerInstance()

@Composable
fun PostsScreen(vm: MainViewModel) {
    val ctx = LocalContext.current
    val posts by vm.posts.collectAsStateWithLifecycle()
    val type by vm.typeFilter.collectAsStateWithLifecycle()
    val view by vm.viewFilter.collectAsStateWithLifecycle()
    val q by vm.query.collectAsStateWithLifecycle()
    val limit by vm.limit.collectAsStateWithLifecycle()
    val counts by vm.counts.collectAsStateWithLifecycle()
    var menu by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<PostEntity?>(null) }
    var clearConfirm by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? -> uri?.let(vm::importArchive) }

    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Posts", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("${nf.format(counts.total)} scanned", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column {
                IconButton({ menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text("Re-apply filters") }, onClick = { menu = false; vm.recompute() })
                    DropdownMenuItem(text = { Text("Import X data archive…") }, onClick = {
                        menu = false
                        picker.launch(arrayOf("application/zip", "text/javascript", "application/javascript", "application/x-javascript", "*/*"))
                    })
                    DropdownMenuItem(text = { Text("Retry failed") }, onClick = { menu = false; vm.retryFailed() })
                    DropdownMenuItem(text = { Text("Clear scanned (keep deleted)") }, onClick = { menu = false; clearConfirm = true })
                }
            }
        }

        OutlinedTextField(
            value = q, onValueChange = { vm.query.value = it }, singleLine = true,
            leadingIcon = { Icon(Icons.Filled.Search, null) }, placeholder = { Text("Search text or @handle") },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            shape = RoundedCornerShape(14.dp),
        )
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(type == "ALL", { vm.typeFilter.value = "ALL" }, { Text("All types") })
            PostType.entries.forEach { t -> FilterChip(type == t.name, { vm.typeFilter.value = t.name }, { Text(t.plural) }) }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("DELETE" to "Will delete", "KEEP" to "Keeping", "QUEUED" to "Queued", "DONE" to "Deleted ✓", "FAILED" to "Failed", "ALL" to "Everything").forEach { (k, l) ->
                FilterChip(view == k, { vm.viewFilter.value = k }, { Text(l) })
            }
        }

        if (posts.isEmpty()) {
            Column(Modifier.padding(24.dp)) {
                Text("Nothing here", fontWeight = FontWeight.SemiBold)
                Hint(if (counts.total == 0) "Scan your account from Home, or import your X data archive for posts older than the timeline reaches." else "Nothing matches this view.")
            }
        }
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(posts, key = { it.type.name + it.id }) { p -> PostCard(p) { selected = p } }
            if (posts.size >= limit) item {
                TextButton({ vm.limit.value = limit + 150 }, Modifier.fillMaxWidth()) { Text("Load more") }
            }
        }
    }

    selected?.let { p ->
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text("${p.type.label} · ${fmtDate(p.createdAt)}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(p.text.ifBlank { "(no text)" }, maxLines = 8, overflow = TextOverflow.Ellipsis)
                    Hint("♥ ${p.likeCount}  ↻ ${p.repostCount}  💬 ${p.replyCount}  👁 ${nf.format(p.viewCount)}")
                    p.lastError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    TextButton({
                        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://x.com/i/status/${p.sourceId ?: p.id}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }) { Text("Open on X") }
                }
            },
            confirmButton = {
                if (p.status == ItemStatus.PULLED || p.status == ItemStatus.QUEUED) {
                    if (p.manual == 1) TextButton({ vm.setManual(p, 0); selected = null }) { Text("Follow filters") }
                    else TextButton({ vm.setManual(p, 1); selected = null }) { Text("Always keep") }
                } else if (p.status == ItemStatus.FAILED) {
                    TextButton({ vm.queueOne(p); selected = null }) { Text("Retry") }
                }
            },
            dismissButton = {
                Row {
                    if (p.status == ItemStatus.PULLED && p.manual != 2) TextButton({ vm.setManual(p, 2); selected = null }) { Text("Always delete") }
                    TextButton({ selected = null }) { Text("Close") }
                }
            },
        )
    }
    if (clearConfirm) {
        AlertDialog(
            onDismissRequest = { clearConfirm = false },
            title = { Text("Clear scanned items?") },
            text = { Text("Removes everything not yet deleted from the list. Verified deletions are kept. You can scan again any time.") },
            confirmButton = { TextButton({ clearConfirm = false; vm.clearScanned() }) { Text("Clear") } },
            dismissButton = { TextButton({ clearConfirm = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun PostCard(p: PostEntity, onClick: () -> Unit) {
    val willDelete = (p.matches && p.manual != 1) || p.manual == 2
    val (badge, color) = when (p.status) {
        ItemStatus.PULLED -> if (willDelete) "WILL DELETE" to Warn else "KEEP" to MaterialTheme.colorScheme.onSurfaceVariant
        ItemStatus.QUEUED -> "QUEUED" to Warn
        ItemStatus.DELETING -> "DELETING" to Warn
        ItemStatus.SENT -> "VERIFYING" to Warn
        ItemStatus.VERIFIED -> (if (p.alreadyGone) "ALREADY GONE ✓" else "DELETED ✓") to Good
        ItemStatus.FAILED -> "FAILED" to MaterialTheme.colorScheme.error
    }
    Card(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${p.type.label} · ${fmtDate(p.createdAt)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                Text(badge, style = MaterialTheme.typography.labelSmall, color = color, fontWeight = FontWeight.Bold)
            }
            Text(p.text.ifBlank { "(no text)" }, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Hint("♥ ${p.likeCount}")
                Hint("↻ ${p.repostCount}")
                Hint("💬 ${p.replyCount}")
                if (p.hasMedia) Hint("🖼")
                if (p.pinned) Hint("📌")
                if (p.manual == 1) Hint("kept by you")
                if (p.manual == 2) Hint("marked by you")
                p.authorHandle?.let { if (p.type != PostType.POST && p.type != PostType.REPLY) Hint("@$it") }
            }
        }
    }
}

package app.sweeper.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.sweeper.data.DateMode
import app.sweeper.data.FilterConfig
import app.sweeper.data.TypeRule
import java.text.NumberFormat

private val nf = NumberFormat.getIntegerInstance()

@Composable
fun FiltersScreen(vm: MainViewModel) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val counts by vm.counts.collectAsStateWithLifecycle()
    val will by vm.willDelete.collectAsStateWithLifecycle()
    val f = s.filter
    fun upd(g: (FilterConfig) -> FilterConfig) = vm.update { it.copy(filter = g(it.filter)) }

    LazyColumn(
        Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Text("Filters", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Hint("Everything below narrows what gets deleted. An item is deleted only if it passes every rule.")
        }
        item {
            Panel {
                Text("Live preview", fontWeight = FontWeight.SemiBold)
                Text(
                    if (counts.pulled + counts.queued == 0) "Nothing scanned yet. Scan from Home to see matches."
                    else "${nf.format(will)} of ${nf.format(counts.pulled)} scanned items will be deleted.",
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        item {
            Section("What to delete", "Types, each with its own minimum age", startOpen = true) {
                TypeRow("Posts", f.posts) { r -> upd { it.copy(posts = r) } }
                TypeRow("Replies", f.replies) { r -> upd { it.copy(replies = r) } }
                TypeRow("Reposts", f.reposts) { r -> upd { it.copy(reposts = r) } }
                TypeRow("Quote posts", f.quotes) { r -> upd { it.copy(quotes = r) } }
                TypeRow("Likes", f.likes) { r -> upd { it.copy(likes = r) } }
                Hint("Likes use the age of the liked post (X does not expose when you liked it). Posts older than the timeline's ~3,200 reach need the data archive import (Posts tab, menu).")
            }
        }

        item {
            Section("When", "Age or date range", startOpen = true) {
                ChoiceRow(
                    "Date rule", listOf(DateMode.ALL to "Any date", DateMode.OLDER_THAN to "Older than", DateMode.RANGE to "Between dates"),
                    f.dateMode, { m -> upd { it.copy(dateMode = m) } },
                )
                when (f.dateMode) {
                    DateMode.OLDER_THAN -> SliderRow(
                        "Older than", f.olderThanDays.coerceIn(0, 3650), 0..3650, { v -> upd { it.copy(olderThanDays = v) } }, step = 1,
                        label = { d -> if (d == 0) "today" else if (d >= 365) "%.1f years".format(d / 365.0) else "$d days" },
                    )
                    DateMode.RANGE -> {
                        DateRow("From", f.afterMs) { v -> upd { it.copy(afterMs = v) } }
                        DateRow("Until", f.beforeMs) { v -> upd { it.copy(beforeMs = v) } }
                    }
                    DateMode.ALL -> {}
                }
                SliderRow(
                    "Always keep the newest", f.keepNewest, 0..1000, { v -> upd { it.copy(keepNewest = v) } }, step = 10,
                    subtitle = "Per type. 0 turns this off.", label = { if (it == 0) "off" else "$it" },
                )
            }
        }

        item {
            Section("Words", "Keywords, phrases, regex") {
                TextRow("Delete only if it contains", f.includeWords, { v -> upd { it.copy(includeWords = v) } }, "One per line or comma-separated. Empty = any post.", singleLine = false)
                SwitchRow("Must contain all of them", "Off = any one is enough", f.includeAll) { v -> upd { it.copy(includeAll = v) } }
                TextRow("Keep if it contains", f.excludeWords, { v -> upd { it.copy(excludeWords = v) } }, "Anything with these words is never deleted.", singleLine = false)
                SwitchRow("Treat as regular expressions", "One pattern per line", f.useRegex) { v -> upd { it.copy(useRegex = v) } }
                SwitchRow("Case sensitive", checked = f.caseSensitive) { v -> upd { it.copy(caseSensitive = v) } }
                SwitchRow("Whole words only", checked = f.wholeWord) { v -> upd { it.copy(wholeWord = v) } }
            }
        }

        item {
            Section("Protect popular posts", "Keep anything that did well") {
                SliderRow("Keep if likes ≥", f.keepLikes, 0..1000, { v -> upd { it.copy(keepLikes = v) } }, step = 5, label = { if (it == 0) "off" else "$it" })
                SliderRow("Keep if reposts ≥", f.keepReposts, 0..500, { v -> upd { it.copy(keepReposts = v) } }, step = 5, label = { if (it == 0) "off" else "$it" })
                SliderRow("Keep if replies ≥", f.keepReplies, 0..500, { v -> upd { it.copy(keepReplies = v) } }, step = 5, label = { if (it == 0) "off" else "$it" })
                NumberRow("Keep if views ≥", f.keepViews, { v -> upd { it.copy(keepViews = v) } }, "0 = off")
            }
        }

        item {
            Section("Content", "Media, links, hashtags, mentions, language") {
                TriRow("Has media", f.hasMedia, { v -> upd { it.copy(hasMedia = v) } })
                TriRow("Has links", f.hasLinks, { v -> upd { it.copy(hasLinks = v) } })
                TriRow("Has hashtags", f.hasHashtags, { v -> upd { it.copy(hasHashtags = v) } })
                TriRow("Has @mentions", f.hasMentions, { v -> upd { it.copy(hasMentions = v) } })
                TriRow("Replies to yourself (threads)", f.selfReply, { v -> upd { it.copy(selfReply = v) } })
                TextRow("Languages", f.languages, { v -> upd { it.copy(languages = v) } }, "Codes like en, fr, ar. Empty = all.")
            }
        }

        item {
            Section("People", "Replies, reposts and likes by who they involve") {
                TextRow("Replies: only to these accounts", f.replyToHandles, { v -> upd { it.copy(replyToHandles = v) } }, "@handles, comma-separated. Empty = any.")
                TextRow("Likes/reposts: only from these authors", f.authorsInclude, { v -> upd { it.copy(authorsInclude = v) } }, "Empty = any.")
                TextRow("Likes/reposts: keep these authors", f.authorsExclude, { v -> upd { it.copy(authorsExclude = v) } }, "Never un-like or un-repost these.")
            }
        }

        item {
            Section("Protected", "Never delete these", startOpen = true) {
                SwitchRow("Keep my pinned post", checked = f.keepPinned) { v -> upd { it.copy(keepPinned = v) } }
                TextRow("Post links or ids to keep forever", f.keepIds, { v -> upd { it.copy(keepIds = v) } }, "Paste links or ids, any separator.", singleLine = false)
                Hint("You can also tap any post in the Posts tab and choose Always keep.")
            }
        }
    }
}

@Composable
private fun TypeRow(label: String, rule: TypeRule, onChange: (TypeRule) -> Unit) {
    SwitchRow(label, checked = rule.enabled) { onChange(rule.copy(enabled = it)) }
    if (rule.enabled) {
        SliderRow(
            "   $label older than", rule.olderThanDays, 0..3650, { onChange(rule.copy(olderThanDays = it)) },
            label = { d -> if (d == 0) "no extra limit" else if (d >= 365) "%.1f years".format(d / 365.0) else "$d days" },
        )
    }
}

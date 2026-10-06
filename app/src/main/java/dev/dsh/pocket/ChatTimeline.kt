package dev.dsh.pocket

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.json.JSONObject

/**
 * One row of the transcript. The desktop client renders tool activity as
 * collapsible groups rather than one line per call, so a turn reads as
 * "read files and ran code" instead of a wall of raw tool names.
 */
sealed interface Timeline {
    val seq: Int
    data class Bubble(override val seq: Int, val role: String, val text: String) : Timeline
    data class Thinking(override val seq: Int, val text: String) : Timeline
    data class Tools(override val seq: Int, val label: String, val calls: List<ToolCall>) : Timeline
}

data class ToolCall(val seq: Int, val callId: String, val name: String, val arguments: String,
                    val output: String = "", val error: Boolean = false)

// Same accent as the rest of the app; MainActivity's Blue is file-private.
private val Accent = Color(0xFF2563EB)
private val Muted = Color(0xFF64748B)

private enum class Action { READ, WRITE, RUN, SEARCH, OTHER }

private fun actionOf(call: ToolCall): Action {
    val name = call.name.lowercase()
    return when {
        Regex("read|view|cat|open|fetch").containsMatchIn(name) -> Action.READ
        Regex("write|edit|create|save|patch|apply|replace").containsMatchIn(name) -> Action.WRITE
        Regex("bash|shell|exec|command|run|terminal|spawn").containsMatchIn(name) -> Action.RUN
        Regex("grep|search|find|glob|list|ls|browse").containsMatchIn(name) -> Action.SEARCH
        else -> Action.OTHER
    }
}

private fun pick(call: ToolCall, vararg keys: String): String {
    val arguments = runCatching { JSONObject(call.arguments) }.getOrNull() ?: return ""
    for (key in keys) {
        val value = arguments.optString(key, "")
        if (value.isNotBlank()) return value
    }
    return ""
}

/** Header text for a group, e.g. "已读取文件并运行了代码". */
fun summarizeTools(calls: List<ToolCall>): String {
    val actions = calls.map(::actionOf).toSet()
    val phrases = ArrayList<String>()
    if (Action.READ in actions) phrases.add(tr("已读取文件"))
    if (Action.WRITE in actions) phrases.add(tr("已写入文件"))
    if (Action.RUN in actions) phrases.add(tr("运行了代码"))
    if (Action.SEARCH in actions) phrases.add(tr("已搜索文件"))
    if (phrases.isEmpty()) phrases.add(tr("执行了工具"))
    if (phrases.size == 1) return phrases[0]
    return phrases.dropLast(1).joinToString(tr("、")) + tr("并") + phrases.last()
}

/** One line describing a single call. */
fun detailOf(call: ToolCall): String = when (actionOf(call)) {
    Action.READ -> tr("读取 ") + pick(call, "path", "file_path", "filePath", "file", "target").ifBlank { call.name }
    Action.WRITE -> tr("写入 ") + pick(call, "path", "file_path", "filePath", "file", "target").ifBlank { call.name }
    Action.RUN -> tr("运行 ") + pick(call, "command", "cmd", "script")
        .lineSequence().firstOrNull().orEmpty().trim().ifBlank { call.name }
    Action.SEARCH -> tr("搜索 ") + pick(call, "pattern", "query", "path", "glob").ifBlank { call.name }
    Action.OTHER -> call.name + "  " + call.arguments.take(90)
}

/**
 * Fold messages and tool events into one ordered transcript. Entries carry a
 * sequence written by the bridge; records from older versions fall back to time,
 * and tool calls group by their turn and step exactly like the desktop does.
 */
fun buildTimeline(chat: JSONObject): List<Timeline> {
    data class Entry(val seq: Int, val time: Long, val message: JSONObject?, val event: JSONObject?)
    val entries = ArrayList<Entry>()
    chat.objects("messages").forEach { entries.add(Entry(it.optInt("seq", 0), it.optLong("time"), it, null)) }
    chat.objects("events").forEach { entries.add(Entry(it.optInt("seq", 0), it.optLong("time"), null, it)) }
    val ordered = if (entries.any { it.seq > 0 }) entries.sortedBy { it.seq } else entries.sortedBy { it.time }

    val result = ArrayList<Timeline>()
    val calls = ArrayList<ToolCall>()
    var turn = Int.MIN_VALUE
    var step = Int.MIN_VALUE
    // Rows are keyed by this, so it has to be unique even for records written
    // before the bridge started numbering entries (those all report seq 0).
    var identity = 0
    fun flush() {
        if (calls.isNotEmpty()) {
            result.add(Timeline.Tools(++identity, summarizeTools(calls), calls.toList()))
            calls.clear()
        }
    }
    for (entry in ordered) {
        val message = entry.message
        if (message != null) {
            flush()
            result.add(Timeline.Bubble(++identity, message.string("role"), message.string("text")))
            continue
        }
        val event = entry.event ?: continue
        val data = event.optJSONObject("data") ?: JSONObject()
        when (event.string("type")) {
            "assistant/thinking" -> {
                val text = data.string("text")
                flush()
                if (text.isNotBlank()) result.add(Timeline.Thinking(++identity, text))
            }
            "tool/call" -> {
                val eventTurn = data.optInt("turn")
                val eventStep = data.optInt("step")
                if (eventTurn != turn || eventStep != step) { flush(); turn = eventTurn; step = eventStep }
                calls.add(ToolCall(++identity, data.string("callId"), data.string("name", "tool"), data.string("arguments")))
            }
            "tool/result" -> {
                val id = data.string("callId")
                val index = calls.indexOfLast { it.callId == id }
                if (index >= 0) calls[index] = calls[index].copy(output = data.string("text"), error = data.optBoolean("error"))
            }
        }
    }
    flush()
    return result
}

@Composable
fun TimelineRow(item: Timeline) {
    when (item) {
        is Timeline.Bubble -> BubbleRow(item)
        is Timeline.Thinking -> Card(key = "think", seq = item.seq, icon = Icons.Outlined.Psychology,
            title = tr("思考"), note = "", expandedByDefault = false) {
            SelectionContainer { Text(item.text, style = MaterialTheme.typography.bodySmall, color = Color(0xFF475569)) }
        }
        is Timeline.Tools -> Card(key = "tools", seq = item.seq, icon = Icons.Outlined.Build,
            title = item.label, note = item.calls.size.toString() + tr(" 步"), expandedByDefault = false) {
            ToolCalls(item.calls)
        }
    }
}

@Composable
private fun BubbleRow(item: Timeline.Bubble) {
    val user = item.role == "user"
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (user) Arrangement.End else Arrangement.Start) {
        Surface(color = if (user) Color(0xFFE8EFFF) else Color.White, shape = RoundedCornerShape(18.dp),
            modifier = Modifier.widthIn(max = 740.dp)) {
            Column(Modifier.padding(18.dp)) {
                Text(if (user) tr("你") else "DSH", style = MaterialTheme.typography.labelMedium,
                    color = if (user) Accent else Muted, modifier = Modifier.padding(bottom = 8.dp))
                SelectionContainer { Text(item.text, style = MaterialTheme.typography.bodyLarge) }
            }
        }
    }
}

@Composable
private fun Card(key: String, seq: Int, icon: ImageVector, title: String, note: String,
                 expandedByDefault: Boolean, body: @Composable () -> Unit) {
    var open by remember(key, seq) { mutableStateOf(expandedByDefault) }
    Surface(color = Color.White, shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, Color(0xFFE5EAF1)), modifier = Modifier.fillMaxWidth().widthIn(max = 740.dp)) {
        Column {
            Row(Modifier.fillMaxWidth().clickable { open = !open }.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = Color(0xFF64748B), modifier = Modifier.size(18.dp))
                Text(title, modifier = Modifier.padding(start = 10.dp).weight(1f),
                    style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (note.isNotBlank()) Text(note, style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF94A3B8), modifier = Modifier.padding(end = 8.dp))
                Icon(if (open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    if (open) tr("收起") else tr("展开"), tint = Color(0xFF94A3B8), modifier = Modifier.size(18.dp))
            }
            if (open) {
                HorizontalDivider(color = Color(0xFFEFF2F7))
                Box(Modifier.padding(14.dp)) { body() }
            }
        }
    }
}

@Composable
private fun ToolCalls(calls: List<ToolCall>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        calls.forEach { call -> ToolCallRow(call) }
    }
}

@Composable
private fun ToolCallRow(call: ToolCall) {
    var open by remember(call.seq) { mutableStateOf(false) }
    val icon = when (actionOf(call)) {
        Action.READ -> Icons.Outlined.Description
        Action.WRITE -> Icons.Outlined.Edit
        Action.RUN -> Icons.Outlined.PlayArrow
        Action.SEARCH -> Icons.Outlined.Search
        Action.OTHER -> Icons.Outlined.Build
    }
    Column(Modifier.fillMaxWidth().clickable(enabled = call.output.isNotBlank()) { open = !open }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = if (call.error) MaterialTheme.colorScheme.error else Accent,
                modifier = Modifier.size(16.dp))
            Text(detailOf(call), modifier = Modifier.padding(start = 8.dp).weight(1f),
                style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                maxLines = if (open) 3 else 1, overflow = TextOverflow.Ellipsis)
        }
        if (open && call.output.isNotBlank()) Surface(color = Color(0xFF0F172A), shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp).heightIn(max = 220.dp)) {
            SelectionContainer {
                Text(call.output, color = Color(0xFFE2E8F0), fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(10.dp).verticalScroll(rememberScrollState()))
            }
        }
    }
}

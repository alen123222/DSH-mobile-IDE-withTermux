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
import androidx.compose.material3.CircularProgressIndicator
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

private val Accent = Color(0xFF2563EB)
private val Muted = Color(0xFF64748B)
private val Faint = Color(0xFF94A3B8)

/**
 * One row of the transcript.
 *
 * A whole turn folds into a single row: collapsed it is just "DSH is working",
 * and expanding it reveals one small fold per kind of work (thinking, reading,
 * writing, running), which in turn reveal the individual commands and paths.
 * One card per tool call was what made this transcript unreadable.
 */
sealed interface Timeline {
    val seq: Int
    data class Bubble(override val seq: Int, val role: String, val text: String) : Timeline
    data class Work(override val seq: Int, val turn: Int, val thinking: String, val calls: List<ToolCall>) : Timeline
}

data class ToolCall(val seq: Int, val callId: String, val name: String, val arguments: String,
                    val output: String = "", val error: Boolean = false)

enum class Action { READ, WRITE, RUN, SEARCH, OTHER }

private val READ_COMMANDS = Regex("^(cat|head|tail|less|more|grep|egrep|rg|wc|file|stat|xxd|od|tree|ls|find|diff|du|df|readlink|realpath|which|type|env|printenv)\\b")
private val WRITE_COMMANDS = Regex("^(tee|sed|cp|mv|rm|mkdir|rmdir|touch|chmod|chown|ln|truncate|dd|pip|npm|pnpm|yarn|apt|pkg)\\b")
private val GIT_WRITE = Regex("^git\\s+(apply|add|commit|checkout|restore|reset|clean|clone|pull|init)\\b")
private val GIT_READ = Regex("^git\\s+(diff|status|log|show|branch|remote|blame|ls-files)\\b")

private fun commandOf(call: ToolCall): String {
    val arguments = runCatching { JSONObject(call.arguments) }.getOrNull() ?: return ""
    for (key in listOf("command", "cmd", "script")) {
        val value = arguments.optString(key, "")
        if (value.isNotBlank()) return value
    }
    return ""
}

/**
 * Classify a call. Everything the harness runs goes through bash, so the tool
 * name alone says nothing; the command it was given is what tells reading from
 * writing from plain execution.
 */
fun actionOf(call: ToolCall): Action {
    val name = call.name.lowercase()
    if (Regex("read|view|cat|open|fetch").containsMatchIn(name)) return Action.READ
    if (Regex("write|edit|create|save|patch|apply|replace").containsMatchIn(name)) return Action.WRITE
    if (Regex("grep|search|find|glob|ls|list|browse").containsMatchIn(name)) return Action.SEARCH
    val command = commandOf(call).trim()
    if (command.isBlank()) {
        return if (Regex("bash|shell|exec|command|run|terminal|spawn").containsMatchIn(name)) Action.RUN else Action.OTHER
    }
    val head = command.lineSequence().firstOrNull().orEmpty().trim()
    if (READ_COMMANDS.containsMatchIn(head) || GIT_READ.containsMatchIn(head)) return Action.READ
    if (WRITE_COMMANDS.containsMatchIn(head) || GIT_WRITE.containsMatchIn(head)) return Action.WRITE
    if (Regex("(^|[^0-9&])>>?[^&]").containsMatchIn(command)) return Action.WRITE
    return Action.RUN
}

private fun pick(call: ToolCall, vararg keys: String): String {
    val arguments = runCatching { JSONObject(call.arguments) }.getOrNull() ?: return ""
    for (key in keys) {
        val value = arguments.optString(key, "")
        if (value.isNotBlank()) return value
    }
    return ""
}

/** One line describing a single call. */
fun detailOf(call: ToolCall): String {
    val command = commandOf(call).lineSequence().firstOrNull().orEmpty().trim()
    return when (actionOf(call)) {
        Action.READ -> {
            val path = pick(call, "path", "file_path", "filePath", "file", "target")
            if (path.isNotBlank()) tr("读取 ") + path else command.ifBlank { call.name }
        }
        Action.WRITE -> {
            val path = pick(call, "path", "file_path", "filePath", "file", "target")
            if (path.isNotBlank()) tr("写入 ") + path else command.ifBlank { call.name }
        }
        Action.SEARCH -> tr("搜索 ") + pick(call, "pattern", "query", "path", "glob").ifBlank { command.ifBlank { call.name } }
        else -> command.ifBlank { call.name + "  " + call.arguments.take(90) }
    }
}

/**
 * Fold messages and tool events into one ordered transcript. Entries carry a
 * sequence written by the bridge; records from older versions fall back to time.
 * All the work of one turn becomes a single item, placed where that turn began.
 */
fun buildTimeline(chat: JSONObject): List<Timeline> {
    data class Entry(val index: Int, val seq: Int, val time: Long, val message: JSONObject?, val event: JSONObject?)
    val entries = ArrayList<Entry>()
    var counter = 0
    chat.objects("messages").forEach { entries.add(Entry(++counter, it.optInt("seq", 0), it.optLong("time"), it, null)) }
    chat.objects("events").forEach { entries.add(Entry(++counter, it.optInt("seq", 0), it.optLong("time"), null, it)) }
    val ordered = if (entries.any { it.seq > 0 }) entries.sortedBy { it.seq } else entries.sortedBy { it.time }

    // First pass: collect each turn's work and remember where that turn started.
    class Turn(val first: Int) { val calls = ArrayList<ToolCall>(); val thinking = StringBuilder() }
    val turns = LinkedHashMap<Int, Turn>()
    // Old bridge records omit the thinking turn. The next numbered event in
    // the same user interval identifies reasoning emitted before a tool call.
    val nextTurn = HashMap<Int, Int>()
    var upcoming: Int? = null
    for (entry in ordered.asReversed()) {
        if (entry.message?.string("role") == "user") upcoming = null
        val data = entry.event?.optJSONObject("data")
        if (data?.has("turn") == true && !data.isNull("turn")) upcoming = data.optInt("turn")
        upcoming?.let { nextTurn[entry.index] = it }
    }
    val entryTurn = HashMap<Int, Int>()
    var current = Int.MIN_VALUE
    var unnumbered = Int.MIN_VALUE
    for (entry in ordered) {
        if (entry.message?.string("role") == "user") current = ++unnumbered
        val event = entry.event ?: continue
        val data = event.optJSONObject("data") ?: JSONObject()
        when (event.string("type")) {
            "tool/call" -> {
                val turn = data.optInt("turn")
                current = turn
                entryTurn[entry.index] = turn
                turns.getOrPut(turn) { Turn(entry.index) }.calls.add(
                    ToolCall(entry.index, data.string("callId"), data.string("name", "tool"), data.string("arguments")))
            }
            "tool/result" -> {
                val id = data.string("callId")
                val turn = turns.entries.lastOrNull { it.value.calls.any { call -> call.callId == id } } ?: continue
                val index = turn.value.calls.indexOfLast { it.callId == id }
                if (index >= 0) turn.value.calls[index] = turn.value.calls[index].copy(
                    output = data.string("text"), error = data.optBoolean("error"))
            }
            "assistant/thinking" -> {
                val text = data.string("text")
                if (text.isNotBlank()) {
                    val turn = if (data.has("turn") && !data.isNull("turn")) data.optInt("turn")
                        else nextTurn[entry.index] ?: current
                    current = turn
                    entryTurn[entry.index] = turn
                    val work = turns.getOrPut(turn) { Turn(entry.index) }
                    if (work.thinking.isNotEmpty()) work.thinking.append('\n')
                    work.thinking.append(text)
                }
            }
        }
    }

    // Second pass: emit one work row per turn, at the turn's first position.
    val emitted = HashSet<Int>()
    val result = ArrayList<Timeline>()
    var identity = 0
    for (entry in ordered) {
        val message = entry.message
        if (message != null) {
            result.add(Timeline.Bubble(++identity, message.string("role"), message.string("text")))
            continue
        }
        val event = entry.event ?: continue
        val type = event.string("type")
        if (type != "tool/call" && type != "assistant/thinking") continue
        val turn = entryTurn[entry.index] ?: continue
        val work = turns[turn] ?: continue
        if (!emitted.add(turn)) continue
        if (work.calls.isEmpty() && work.thinking.isEmpty()) continue
        result.add(Timeline.Work(++identity, turn, work.thinking.toString(), work.calls.toList()))
    }
    return result
}

@Composable
fun TimelineRow(item: Timeline, running: Boolean) {
    when (item) {
        is Timeline.Bubble -> BubbleRow(item)
        is Timeline.Work -> WorkCard(item, running)
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

/** The one big fold: collapsed it is a single "DSH is working" row. */
/** A turn says whether it is still running or has finished. */
private fun workTitle(running: Boolean): String = if (running) tr("DSH 正在工作") else tr("DSH 运行完毕")

@Composable
private fun WorkCard(work: Timeline.Work, running: Boolean) {
    var open by remember(work.seq) { mutableStateOf(false) }
    val count = work.calls.size + (if (work.thinking.isNotEmpty()) 1 else 0)
    Fold(open = open, onToggle = { open = !open }, icon = Icons.Outlined.Build,
        title = workTitle(running), note = count.toString() + tr(" 项"), strong = true, busy = running) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (work.thinking.isNotEmpty()) {
                SubFold(keyText = "think", seq = work.seq, icon = Icons.Outlined.Psychology,
                    title = tr("思考"), count = 1) {
                    SelectionContainer {
                        Text(work.thinking.toString(), style = MaterialTheme.typography.bodySmall, color = Color(0xFF475569))
                    }
                }
            }
            WorkGroup(work, Action.READ, Icons.Outlined.Description, tr("读取文件"))
            WorkGroup(work, Action.WRITE, Icons.Outlined.Edit, tr("写入文件"))
            WorkGroup(work, Action.RUN, Icons.Outlined.PlayArrow, tr("运行命令"))
            WorkGroup(work, Action.SEARCH, Icons.Outlined.Search, tr("搜索"))
            WorkGroup(work, Action.OTHER, Icons.Outlined.Build, tr("其它工具"))
        }
    }
}

@Composable
private fun WorkGroup(work: Timeline.Work, action: Action, icon: ImageVector, label: String) {
    val calls = work.calls.filter { actionOf(it) == action }
    if (calls.isEmpty()) return
    SubFold(keyText = label, seq = work.seq, icon = icon, title = label, count = calls.size) {
        ToolCalls(calls)
    }
}

@Composable
private fun SubFold(keyText: String, seq: Int, icon: ImageVector, title: String, count: Int,
                    body: @Composable () -> Unit) {
    var open by remember(keyText, seq) { mutableStateOf(false) }
    Column {
        Fold(open = open, onToggle = { open = !open }, icon = icon, title = title,
            note = count.toString(), strong = false, busy = false)
        if (open) Box(Modifier.padding(start = 14.dp, top = 6.dp, bottom = 4.dp)) { body() }
    }
}

@Composable
private fun Fold(open: Boolean, onToggle: () -> Unit, icon: ImageVector, title: String, note: String,
                 strong: Boolean, busy: Boolean, body: @Composable () -> Unit = {}) {
    Surface(color = if (strong) Color.White else Color(0xFFF8FAFC), shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, Color(0xFFE5EAF1)), modifier = Modifier.fillMaxWidth().widthIn(max = 740.dp)) {
        Column {
            Row(Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = if (strong) Muted else Faint, modifier = Modifier.size(18.dp))
                Text(title, modifier = Modifier.padding(start = 10.dp).weight(1f),
                    style = if (strong) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodySmall,
                    fontWeight = if (strong) FontWeight.Medium else FontWeight.Normal,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (busy) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                if (note.isNotBlank()) Text(note, style = MaterialTheme.typography.labelSmall,
                    color = Faint, modifier = Modifier.padding(start = 8.dp, end = 8.dp))
                Icon(if (open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    if (open) tr("收起") else tr("展开"), tint = Faint, modifier = Modifier.size(18.dp))
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
    Column(Modifier.fillMaxWidth().clickable(enabled = call.output.isNotBlank()) { open = !open }) {
        Text(detailOf(call), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
            color = if (call.error) MaterialTheme.colorScheme.error else Color(0xFF334155),
            maxLines = if (open) 3 else 1, overflow = TextOverflow.Ellipsis)
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

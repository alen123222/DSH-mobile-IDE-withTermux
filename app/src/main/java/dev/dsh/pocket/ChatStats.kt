package dev.dsh.pocket

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import java.text.NumberFormat
import java.util.Locale

private fun JSONObject?.number(key: String): Double? = this?.optDouble(key)?.takeIf { it.isFinite() && it >= 0 }
private fun exact(value: Double?): String = value?.let { NumberFormat.getIntegerInstance().format(it.toLong()) } ?: "—"
private fun compact(value: Double?): String = when {
    value == null -> "—"
    value >= 1_000_000 -> String.format(Locale.getDefault(), "%.1fM", value / 1_000_000)
    value >= 1000 -> String.format(Locale.getDefault(), "%.1fK", value / 1000)
    else -> exact(value)
}
private fun duration(value: Double?): String = value?.let {
    if (it < 60_000) String.format(Locale.getDefault(), "%.1f s", it / 1000)
    else "${(it / 60000).toInt()} m ${(it / 1000).toInt() % 60} s"
} ?: "—"

/**
 * What the run has cost so far, as the window title's second line. This is the summary
 * the reader sees without opening anything, and the entry point to the detail table.
 */
fun chatHeadlineStats(chat: JSONObject?): String {
    val metrics = chat?.optJSONObject("metrics")
    val stats = metrics?.optJSONObject("sessionStats")
    val usage = metrics?.optJSONObject("tokenUsage")
    val context = metrics?.optJSONObject("contextPressure")
    fun token(key: String): Double? = usage?.takeIf { it.has(key) }?.optDouble(key)?.takeIf { it.isFinite() && it > 0 }
    val spent = listOf("uncachedInputTokens", "cacheReadTokens", "cacheWriteTokens", "outputTokens").mapNotNull(::token).takeIf { it.isNotEmpty() }?.sum()
    // The window reports pressure and the window size, not a percentage.
    val percent = context?.let { row ->
        when {
            row.has("percent") -> row.optDouble("percent")
            row.optDouble("pressureTokens") > 0 && row.optDouble("contextWindow") > 0 ->
                100 * row.optDouble("pressureTokens") / row.optDouble("contextWindow")
            else -> null
        }
    }?.takeIf { it.isFinite() && it > 0 }
    return listOfNotNull(
        spent?.let { compact(it) + " tok" },
        percent?.let { String.format(Locale.getDefault(), "%.0f%%", it) },
        stats?.let { exact(it.number("turns")) + " " + tr("轮") + " " + exact(it.number("steps")) + " " + tr("步") },
    ).joinToString(" · ")
}

@Composable
fun ChatStats(chat: JSONObject?) {
    val metrics = chat?.optJSONObject("metrics")
    val stats = metrics?.optJSONObject("sessionStats")
    val usage = metrics?.optJSONObject("tokenUsage")
    val context = metrics?.optJSONObject("contextPressure")
    val breakdown = metrics?.optJSONObject("contextBreakdown")
    val reported = context.number("pressureTokens") != null
    val input = if (reported) usage.number("uncachedInputTokens") else null
    val read = if (reported) usage.number("cacheReadTokens") else null
    val write = if (reported) usage.number("cacheWriteTokens") else null
    val output = if (reported) usage.number("outputTokens") else null
    val prompt = input?.let { it + (read ?: 0.0) + (write ?: 0.0) }
    val total = prompt?.let { it + (output ?: 0.0) }
    val hit = if (prompt != null && prompt > 0 && read != null) 100 * read / prompt else null
    val occupied = context.number("projectedTokens") ?: context.number("pressureTokens")
    val capacity = context.number("contextWindow")?.takeIf { it > 0 }
    val percent = if (occupied != null && capacity != null) 100 * occupied / capacity else null
    val ttft = stats.number("ttftSteps")?.takeIf { it > 0 }?.let { count -> stats.number("ttftMs")?.div(count) }
    val speed = stats.number("decodeMs")?.takeIf { it > 0 }?.let { ms -> stats.number("decodeTokens")?.times(1000)?.div(ms) }
    fun percentage(value: Double?) = value?.let { String.format(Locale.getDefault(), "%.1f%%", it) } ?: "—"
    var dialog by remember { mutableIntStateOf(-1) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        listOf(
            if (stats != null) "${exact(stats.number("turns"))} ${tr("轮")} · ${exact(stats.number("steps"))} ${tr("步")}" else tr("会话统计"),
            "${compact(total)} tok",
            tr("上下文") + " " + percentage(percent),
        ).forEachIndexed { index, label ->
            TextButton(onClick = { dialog = index }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 4.dp)) {
                Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if (dialog >= 0) AlertDialog(onDismissRequest = { dialog = -1 },
        title = { Text(tr(listOf("会话统计", "Token 用量", "上下文占用")[dialog])) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (metrics == null) Text(tr("本会话下次运行后显示统计，包含引擎保留的历史记录。"))
                else when (dialog) {
                    0 -> {
                        StatRow(tr("轮数 / 步数"), "${exact(stats.number("turns"))} / ${exact(stats.number("steps"))}")
                        StatRow(tr("模型用时"), duration(stats.number("llmMs")))
                        StatRow(tr("工具调用用时"), duration(stats.number("toolMs")))
                        StatRow(tr("平均首 Token（TTFT）"), duration(ttft))
                        StatRow(tr("输出速度（TPS）"), speed?.let { String.format(Locale.getDefault(), "%.1f tok/s", it) } ?: "—")
                    }
                    1 -> {
                        StatRow(tr("总用量"), exact(total) + " tok")
                        StatRow(tr("缓存命中"), percentage(hit))
                        StatRow(tr("未缓存输入"), exact(input) + " tok")
                        StatRow(tr("缓存读取"), exact(read) + " tok")
                        StatRow(tr("缓存写入"), exact(write) + " tok")
                        StatRow(tr("输出"), exact(output) + " tok")
                        if (!reported) Text(tr("提供商尚未返回 Token 用量。"))
                    }
                    2 -> {
                        StatRow(tr("上下文已用"), "${percentage(percent)} · ~${compact(occupied)} / ${compact(capacity)}")
                        if (percent != null) LinearProgressIndicator(progress = { (percent / 100).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                        StatRow(tr("系统提示词"), "~" + compact(breakdown.number("systemTokens")))
                        StatRow(tr("工具定义"), "~" + compact(breakdown.number("toolsTokens")))
                        StatRow(tr("对话消息"), "~" + compact(breakdown.number("messageTokens")))
                        Text(tr("组成数值为 DSH 估算；占用基于最近用量和后续消息变化，与组成之和可能不同。"), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = { dialog = -1 }) { Text(tr("关闭")) } })
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value)
    }
}

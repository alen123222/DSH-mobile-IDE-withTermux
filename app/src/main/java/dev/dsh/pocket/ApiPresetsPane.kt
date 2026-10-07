package dev.dsh.pocket

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

fun reasoningLevels(protocol: String): List<String> = if (protocol == "deepseek-messages")
    listOf("", "off", "low", "high", "max") else listOf("", "off", "minimal", "low", "medium", "high", "xhigh", "max")

fun reasoningLabel(value: String): String = tr(when (value) {
    "off" -> "关闭"; "minimal" -> "最低"; "low" -> "低"; "medium" -> "中"
    "high" -> "高"; "xhigh" -> "更高"; "max" -> "最高"; else -> "默认"
})

@Composable
fun ReasoningSelector(settings: EngineSettings, enabled: Boolean = true, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(enabled = enabled, onClick = { open = true }) { Text(tr("思考深度") + " · " + reasoningLabel(settings.reasoningEffort) + " ▾") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            reasoningLevels(settings.protocol).forEach { effort ->
                DropdownMenuItem(text = { Text(reasoningLabel(effort)) }, onClick = { open = false; onSelect(effort) })
            }
        }
    }
}

@Composable
fun ChatModelControls(state: PocketState, model: PocketModel, running: Boolean) {
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Box(Modifier.weight(1f)) {
            TextButton(enabled = !running, onClick = { open = true }) {
                Text(state.presets.firstOrNull { it.id == state.activePresetId }?.name ?: state.settings.model,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(" ▾")
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.heightIn(max = 320.dp)) {
                state.presets.forEach { preset -> DropdownMenuItem(
                    text = { Column { Text(preset.name); Text(preset.settings.model, style = MaterialTheme.typography.bodySmall) } },
                    onClick = { open = false; model.selectPreset(preset.id) }) }
            }
        }
        ReasoningSelector(state.settings, enabled = !running, onSelect = model::selectReasoning)
        PhoneChatControl(running)
    }
}

@Composable
fun ApiPresetsPane(state: PocketState, model: PocketModel) {
    var editing by remember { mutableStateOf(false) }
    var editingId by remember { mutableStateOf<String?>(null) }
    var name by remember { mutableStateOf("") }
    var draft by remember { mutableStateOf(EngineSettings()) }
    var advanced by remember { mutableStateOf(false) }
    var protocolMenu by remember { mutableStateOf(false) }
    var deleteId by remember { mutableStateOf<String?>(null) }
    var contextWindow by remember { mutableStateOf("") }
    var maxTokens by remember { mutableStateOf("") }
    val protocols = linkedMapOf("openai-chat" to "OpenAI Chat Completions", "openai-responses" to "OpenAI Responses", "deepseek-messages" to "DeepSeek Messages")
    fun begin(preset: ApiPreset?) {
        editingId = preset?.id; name = preset?.name.orEmpty()
        draft = preset?.settings ?: EngineSettings(model = "", protocol = "openai-chat", provider = "pocket-openai")
        contextWindow = draft.contextWindow.toString(); maxTokens = draft.maxTokens.toString()
        advanced = false; editing = true
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(tr("模型"), fontWeight = FontWeight.SemiBold)
        if (!editing) {
            state.presets.forEach { preset ->
                Row(Modifier.fillMaxWidth()) {
                    RadioButton(selected = preset.id == state.activePresetId, onClick = { model.selectPreset(preset.id) })
                    Column(Modifier.weight(1f).padding(top = 10.dp)) {
                        Text(preset.name, fontWeight = FontWeight.Medium)
                        Text(preset.settings.model, style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = { begin(preset) }) { Text(tr("编辑")) }
                }
            }
            TextButton(onClick = { begin(null) }) { Text(tr("添加模型")) }
        } else {
            OutlinedTextField(draft.model, { draft = draft.copy(model = it) }, label = { Text(tr("模型 ID")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(draft.baseUrl, { draft = draft.copy(baseUrl = it) }, label = { Text(tr("API 地址")) },
                placeholder = { Text(if (draft.protocol == "deepseek-messages") "https://api.deepseek.com/anthropic" else "https://api.openai.com/v1") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(draft.apiKey, { draft = draft.copy(apiKey = it) }, label = { Text("API Key") },
                visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
            ReasoningSelector(draft) { draft = draft.copy(reasoningEffort = it) }
            Text(tr("默认使用模型自身设置；可选深度取决于 API 的支持。"), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { advanced = !advanced }) { Text(tr("高级选项") + if (advanced) " ▴" else " ▾") }
            if (advanced) {
                Row { Checkbox(draft.vision, onCheckedChange = { draft = draft.copy(vision = it) }); Text("模型支持图片（手机截图）", Modifier.padding(top = 12.dp)) }
                OutlinedTextField(name, { name = it }, label = { Text(tr("显示名称（可选）")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Box {
                    OutlinedButton(onClick = { protocolMenu = true }) { Text(tr("协议") + " · " + protocols[draft.protocol]) }
                    DropdownMenu(expanded = protocolMenu, onDismissRequest = { protocolMenu = false }) {
                        protocols.forEach { (id, label) -> DropdownMenuItem(text = { Text(label) }, onClick = {
                            protocolMenu = false
                            draft = draft.copy(protocol = id, reasoningEffort = draft.reasoningEffort.takeIf { it in reasoningLevels(id) } ?: "")
                        }) }
                    }
                }
                Row {
                    Checkbox(draft.autoVersion, { draft = draft.copy(autoVersion = it) })
                    Text(tr("仅填域名时补 /v1"), modifier = Modifier.padding(top = 12.dp))
                }
                OutlinedTextField(contextWindow, { contextWindow = it.filter(Char::isDigit).take(7) }, label = { Text(tr("上下文长度")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(maxTokens, { maxTokens = it.filter(Char::isDigit).take(6) }, label = { Text(tr("单次输出上限")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text(tr("上下文范围 4096–4194304；输出范围 256–131072。"), style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(enabled = !state.probing && draft.baseUrl.isNotBlank(),
                        onClick = { model.probeModels(draft) }) {
                        Text(if (state.probing) tr("正在测试…") else tr("测试全部 API"))
                    }
                    Text(tr("列出这个地址可用的模型"), style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(start = 8.dp))
                }
                if (state.discovered.isNotEmpty()) {
                    var selected by remember(state.discovered) { mutableStateOf(state.discovered.toSet()) }
                    Text(tr("发现的模型") + " · " + state.discovered.size, fontWeight = FontWeight.Medium)
                    Surface(shape = RoundedCornerShape(12.dp), color = Color(0xFFF8FAFC),
                        modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp)) {
                        Column(Modifier.verticalScroll(rememberScrollState()).padding(4.dp)) {
                            state.discovered.forEach { id ->
                                Row(Modifier.fillMaxWidth().clickable {
                                    selected = if (id in selected) selected - id else selected + id
                                }, verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(checked = id in selected, onCheckedChange = { on ->
                                        selected = if (on) selected + id else selected - id
                                    })
                                    Text(id, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(enabled = selected.isNotEmpty(), onClick = {
                            if (model.addPresetsFor(selected.toList(), draft) > 0) {
                                model.clearDiscovered()
                                editing = false
                            }
                        }) { Text(tr("添加选中")) }
                        TextButton(onClick = { selected = state.discovered.toSet() }) { Text(tr("全选")) }
                        TextButton(onClick = { model.clearDiscovered() }) { Text(tr("关闭")) }
                    }
                }
                if (editingId != null && state.presets.size > 1) TextButton(onClick = { deleteId = editingId }) { Text(tr("删除此预设")) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = draft.model.isNotBlank(), onClick = {
                    val window = contextWindow.toIntOrNull()
                    val tokens = maxTokens.toIntOrNull()
                    if (window == null || window !in 4096..4194304 || tokens == null || tokens !in 256..131072) {
                        model.error(tr("请检查高级选项中的数值范围"))
                    } else if (model.savePreset(editingId, name.ifBlank { draft.model }, draft.copy(contextWindow = window, maxTokens = tokens))) {
                        editing = false
                    }
                }) { Text(tr("保存")) }
                TextButton(onClick = { editing = false }) { Text(tr("取消")) }
            }
        }
    }
    if (deleteId != null) AlertDialog(onDismissRequest = { deleteId = null }, title = { Text(tr("删除预设？")) },
        confirmButton = { TextButton(onClick = { deleteId?.let(model::deletePreset); deleteId = null; editing = false }) { Text(tr("删除")) } },
        dismissButton = { TextButton(onClick = { deleteId = null }) { Text(tr("取消")) } })
}

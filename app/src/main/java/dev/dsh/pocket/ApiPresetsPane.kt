package dev.dsh.pocket

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
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
                val active = state.presets.firstOrNull { it.id == state.activePresetId }
                Text(active?.settings?.model ?: state.settings.model, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(" ▾")
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.heightIn(max = 320.dp)) {
                state.presets.forEach { preset -> DropdownMenuItem(
                    text = { Column { Text(preset.settings.model); Text(providerLabel(preset), style = MaterialTheme.typography.bodySmall) } },
                    onClick = { open = false; model.selectPreset(preset.id) }) }
            }
        }
        ReasoningSelector(state.settings, enabled = !running, onSelect = model::selectReasoning)
        PhoneChatControl(running)
    }
}

/**
 * Providers first, models underneath: one chip per saved endpoint, and the models it
 * carries with the ones that are not wanted any more removable in place. Address, key
 * and protocol belong to the provider; the model id is all a model adds, and the engine
 * decides the context window for itself.
 */
@Composable
fun ApiPresetsPane(state: PocketState, model: PocketModel) {
    val groups = providerGroups(state.presets)
    var open by remember { mutableStateOf("") }
    var adding by remember { mutableStateOf<String?>(null) }
    var rename by remember { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }
    var dropping by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<String?>(null) }
    var modelId by remember { mutableStateOf("") }
    var label by remember { mutableStateOf("") }
    var baseUrl by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    val current = groups.firstOrNull { it.first == open } ?: groups.firstOrNull()

    fun startCreate() {
        creating = true; label = ""; baseUrl = ""; apiKey = ""; modelId = ""
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(tr("模型"), fontWeight = FontWeight.SemiBold)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            groups.forEach { (name, items) ->
                FilterChip(selected = name == current?.first, onClick = { open = name; adding = null },
                    label = { Text(name + " · " + items.size) })
            }
        }
        if (current != null) {
            val (name, items) = current
            Surface(shape = RoundedCornerShape(14.dp), color = Color(0xFFF8FAFC), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 8.dp, vertical = 2.dp)) {
                    items.forEach { preset ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = preset.id == state.activePresetId, onClick = { model.selectPreset(preset.id) })
                            Text(preset.settings.model, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            IconButton(onClick = { deleting = preset.id }) {
                                Icon(Icons.Outlined.Delete, tr("删除模型"), tint = Color(0xFF94A3B8))
                            }
                        }
                    }
                }
            }
            if (adding == name) {
                OutlinedTextField(modelId, { modelId = it }, label = { Text(tr("模型 ID")) }, singleLine = true,
                    modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(enabled = modelId.isNotBlank(), onClick = {
                        if (model.addPresetsFor(listOf(modelId.trim()), items.first().settings, name) > 0) {
                            adding = null; modelId = ""; model.clearDiscovered()
                        }
                    }) { Text(tr("添加")) }
                    OutlinedButton(enabled = !state.probing && items.first().settings.baseUrl.isNotBlank(),
                        onClick = { model.probeModels(items.first().settings.copy(model = "")) }) {
                        Text(if (state.probing) tr("正在获取…") + " " + state.probeSeconds + tr(" 秒") else tr("从端点获取"))
                    }
                    // An endpoint that never answers should not hold the button hostage.
                    if (state.probing) TextButton(onClick = { model.cancelProbe() }) { Text(tr("取消")) }
                    TextButton(onClick = { adding = null; model.clearDiscovered() }) { Text(tr("取消")) }
                }
                // One tap adds; what is already here is not offered again.
                val offered = state.discovered.filter { found -> items.none { it.settings.model == found } }
                if (offered.isNotEmpty()) {
                    Surface(shape = RoundedCornerShape(12.dp), color = Color.White, modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp)) {
                        Column(Modifier.verticalScroll(rememberScrollState())) {
                            offered.forEach { found ->
                                Text(found, style = MaterialTheme.typography.bodyMedium, maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.fillMaxWidth().clickable {
                                        model.addPresetsFor(listOf(found), items.first().settings, name)
                                    }.padding(horizontal = 10.dp, vertical = 10.dp))
                            }
                        }
                    }
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { adding = name; modelId = ""; model.clearDiscovered() }) { Text(tr("＋ 添加模型")) }
                    TextButton(onClick = {
                        rename = name; label = name; baseUrl = items.first().settings.baseUrl; apiKey = items.first().settings.apiKey
                    }) { Text(tr("编辑")) }
                    if (groups.size > 1) TextButton(onClick = { dropping = name }) { Text(tr("删除")) }
                }
            }
        }
        TextButton(onClick = { startCreate() }) { Text(tr("＋ 新建供应商")) }
    }

    if (deleting != null) AlertDialog(onDismissRequest = { deleting = null },
        title = { Text(tr("删除模型？")) },
        text = { Text(state.presets.firstOrNull { it.id == deleting }?.settings?.model.orEmpty()) },
        confirmButton = { TextButton(onClick = { deleting?.let(model::deletePreset); deleting = null }) { Text(tr("删除")) } },
        dismissButton = { TextButton(onClick = { deleting = null }) { Text(tr("取消")) } })

    if (dropping != null) AlertDialog(onDismissRequest = { dropping = null },
        title = { Text(tr("删除供应商？")) },
        text = { Text(dropping.orEmpty()) },
        confirmButton = { TextButton(onClick = {
            val ids = groups.firstOrNull { it.first == dropping }?.second?.map { it.id }.orEmpty()
            model.deleteProvider(ids); dropping = null
        }) { Text(tr("删除")) } },
        dismissButton = { TextButton(onClick = { dropping = null }) { Text(tr("取消")) } })

    if (creating || rename != null) {
        val editing = groups.firstOrNull { it.first == rename }
        AlertDialog(onDismissRequest = { creating = false; rename = null },
            title = { Text(if (creating) tr("新建供应商") else tr("编辑供应商")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(label, { label = it }, label = { Text(tr("名称")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(baseUrl, { baseUrl = it }, label = { Text(tr("API 地址")) },
                        placeholder = { Text("https://api.openai.com/v1") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(apiKey, { apiKey = it }, label = { Text("API Key") },
                        visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
                    if (creating) OutlinedTextField(modelId, { modelId = it }, label = { Text(tr("第一个模型 ID")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Text(tr("协议按地址自动判断，默认 OpenAI 格式；其它交给模型自己。"), style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = {
                if (creating) {
                    val settings = EngineSettings(model = modelId.trim(), protocol = inferProtocol(baseUrl),
                        baseUrl = baseUrl.trim(), apiKey = apiKey.trim())
                    val name = label.ifBlank { providerLabel(ApiPreset("", "", settings)) }
                    if (model.savePreset(null, modelId.trim(), settings, name)) { creating = false; open = name }
                } else if (editing != null) {
                    val protocol = editing.second.first().settings.protocol
                    if (model.updateProvider(editing.second.map { it.id }, label.ifBlank { editing.first }, baseUrl, apiKey, protocol)) {
                        open = label.ifBlank { editing.first }; rename = null
                    }
                }
            }) { Text(tr("保存")) } },
            dismissButton = { TextButton(onClick = { creating = false; rename = null }) { Text(tr("取消")) } })
    }
}

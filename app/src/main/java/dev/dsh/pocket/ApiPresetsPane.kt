package dev.dsh.pocket

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@Composable
fun ApiPresetsPane(state: PocketState, model: PocketModel) {
    var editingId by remember(state.activePresetId) { mutableStateOf<String?>(state.activePresetId) }
    var name by remember(state.activePresetId, state.settings) { mutableStateOf(state.presets.firstOrNull { it.id == state.activePresetId }?.name.orEmpty()) }
    var draft by remember(state.activePresetId, state.settings) { mutableStateOf(state.settings) }
    var presetsMenu by remember { mutableStateOf(false) }
    var protocolMenu by remember { mutableStateOf(false) }
    var modelsMenu by remember { mutableStateOf(false) }
    var deleteConfirm by remember { mutableStateOf(false) }
    val protocols = linkedMapOf("openai-chat" to "OpenAI Chat Completions（常用）", "openai-responses" to "OpenAI Responses", "deepseek-messages" to "DeepSeek Messages")
    fun edit(value: EngineSettings) { draft = value; model.invalidateApiCheck() }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("API 连接预设", fontWeight = FontWeight.SemiBold)
        Text("可保存多个端点和密钥；选择协议后自动配置 DSH 适配器。", style = MaterialTheme.typography.bodySmall)
        Box {
            OutlinedButton(onClick = { presetsMenu = true }) { Text("切换预设 · ${state.presets.firstOrNull { it.id == state.activePresetId }?.name.orEmpty()}") }
            DropdownMenu(expanded = presetsMenu, onDismissRequest = { presetsMenu = false }) {
                state.presets.forEach { preset -> DropdownMenuItem(text = { Text(preset.name) }, onClick = {
                    presetsMenu = false; editingId = preset.id; name = preset.name; draft = preset.settings; model.selectPreset(preset.id)
                }) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { editingId = null; name = ""; draft = EngineSettings(model = "", protocol = "openai-chat", provider = "pocket-openai"); model.invalidateApiCheck() }) { Text("新增预设") }
            TextButton(enabled = editingId != null && state.presets.size > 1, onClick = { deleteConfirm = true }) { Text("删除此预设") }
        }
        OutlinedTextField(name, { name = it }, label = { Text("预设名称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Box {
            OutlinedButton(onClick = { protocolMenu = true }) { Text("协议 · ${protocols[draft.protocol] ?: draft.protocol}") }
            DropdownMenu(expanded = protocolMenu, onDismissRequest = { protocolMenu = false }) {
                protocols.forEach { (id, label) -> DropdownMenuItem(text = { Text(label) }, onClick = { protocolMenu = false; edit(draft.copy(protocol = id)) }) }
            }
        }
        OutlinedTextField(draft.baseUrl, { edit(draft.copy(baseUrl = it)) }, label = { Text("API 端点 / 基础地址") },
            placeholder = { Text(if (draft.protocol == "deepseek-messages") "https://api.deepseek.com/anthropic" else "https://api.example.com/v1") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        if (draft.protocol != "deepseek-messages") Row {
            Checkbox(draft.autoVersion, { edit(draft.copy(autoVersion = it)) })
            Text("仅填域名时补 /v1", modifier = Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodySmall)
        }
        Text(runCatching { "实际请求：${ProviderEndpoint.request(draft)}" }.getOrElse { "地址无效：${it.message}" }, style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(draft.apiKey, { edit(draft.copy(apiKey = it)) }, label = { Text("API Key") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(draft.model, { edit(draft.copy(model = it)) }, label = { Text("模型 ID（可手动填写）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        // Numeric fields keep their own text draft. Binding the value straight to an Int
    // and coercing on every keystroke made the field impossible to edit: clearing
    // it did nothing and typing "3" snapped to 4096 before the next digit arrived.
    var contextWindow by remember(draft.contextWindow) { mutableStateOf(draft.contextWindow.toString()) }
    var maxTokens by remember(draft.maxTokens) { mutableStateOf(draft.maxTokens.toString()) }
    fun commitLimits() {
        val window = contextWindow.trim().toIntOrNull()
        val tokens = maxTokens.trim().toIntOrNull()
        val next = draft.copy(
            contextWindow = window?.coerceIn(4096, 4194304) ?: draft.contextWindow,
            maxTokens = tokens?.coerceIn(256, 131072) ?: draft.maxTokens)
        if (next != draft) edit(next)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(contextWindow, { contextWindow = it.filter(Char::isDigit).take(7) },
            label = { Text("上下文长度") }, singleLine = true, modifier = Modifier.weight(1f),
            supportingText = { Text("4096 – 4194304") },
            isError = contextWindow.trim().toIntOrNull()?.let { it < 4096 || it > 4194304 } == true)
        OutlinedTextField(maxTokens, { maxTokens = it.filter(Char::isDigit).take(6) },
            label = { Text("单次输出上限") }, singleLine = true, modifier = Modifier.weight(1f),
            supportingText = { Text("256 – 131072") },
            isError = maxTokens.trim().toIntOrNull()?.let { it < 256 || it > 131072 } == true)
    }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = !state.apiChecking && draft.apiKey.isNotBlank(), onClick = { model.checkApi(draft, true) }) { Text("查询模型") }
            OutlinedButton(enabled = !state.apiChecking && draft.apiKey.isNotBlank() && draft.model.isNotBlank(), onClick = { model.checkApi(draft, false) }) { Text("测试所选模型") }
        }
        if (state.apiModels.isNotEmpty()) Box {
            OutlinedButton(onClick = { modelsMenu = true }) { Text("选择查询到的模型 · ${state.apiModels.size} 个") }
            DropdownMenu(expanded = modelsMenu, onDismissRequest = { modelsMenu = false }, modifier = Modifier.heightIn(max = 320.dp)) {
                state.apiModels.forEach { id -> DropdownMenuItem(text = { Text(id) }, onClick = { modelsMenu = false; edit(draft.copy(model = id)) }) }
            }
        }
        if (state.apiChecking) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (state.apiResult.isNotBlank()) Text(state.apiResult, style = MaterialTheme.typography.bodySmall)
        Text("测试会发送一条简短生成请求，可能消耗少量额度。密钥使用 Android Keystore 加密保存；切换连接后请新建会话。", style = MaterialTheme.typography.bodySmall)
        Button(enabled = name.isNotBlank() && draft.model.isNotBlank(),
            onClick = { commitLimits(); model.savePreset(editingId, name, draft) }) { Text("保存并启用预设") }
    }
    if (deleteConfirm) AlertDialog(onDismissRequest = { deleteConfirm = false }, title = { Text("删除预设？") }, text = { Text("删除此连接的已保存端点和密钥。") },
        confirmButton = { TextButton(onClick = { editingId?.let(model::deletePreset); deleteConfirm = false }) { Text("删除") } },
        dismissButton = { TextButton(onClick = { deleteConfirm = false }) { Text("取消") } })
}

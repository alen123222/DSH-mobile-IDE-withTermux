package dev.dsh.pocket

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import okhttp3.Call
import org.json.JSONObject
import java.util.UUID

data class PocketState(
    val connected: Boolean = false, val connecting: Boolean = false, val health: JSONObject? = null,
    val workspaces: List<Workspace> = emptyList(), val selected: Workspace? = null,
    val browserPath: String = "", val browserParent: String = "", val entries: List<FileEntry> = emptyList(),
    val browserLoading: Boolean = false, val chats: List<JSONObject> = emptyList(), val chat: JSONObject? = null,
    val settings: EngineSettings = EngineSettings(), val error: String? = null, val preview: JSONObject? = null,
    val consent: Boolean = false, val terminalId: String? = null,
    val presets: List<ApiPreset> = emptyList(), val activePresetId: String = "",
    val apiChecking: Boolean = false, val apiModels: List<String> = emptyList(), val apiResult: String = "",
)

class PocketModel(application: Application) : AndroidViewModel(application) {
    private val secrets = Secrets(application)
    val token = secrets.token()
    val api = BridgeApi(token)
    private val savedPresets = secrets.presets()
    private val mutable = MutableStateFlow(PocketState(settings = savedPresets.second.firstOrNull { it.id == savedPresets.first }?.settings ?: savedPresets.second.first().settings,
        activePresetId = savedPresets.first, presets = savedPresets.second))
    val state = mutable.asStateFlow()
    private var monitor: Job? = null
    private var streamCall: Call? = null
    private var streaming: String? = null
    private val streamEnded = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private var connecting: Job? = null
    private var selectionRevision = 0
    private var browserRevision = 0
    private var apiCheck: Job? = null
    private var apiRevision = 0
    private val providerClient = ProviderClient()

    init { connect(false) }
    fun dismissError() = mutable.update { it.copy(error = null) }
    fun error(message: String) = mutable.update { it.copy(error = message) }
    fun consent(value: Boolean) = mutable.update { it.copy(consent = value) }
    fun savePreset(id: String?, name: String, settings: EngineSettings) {
        try {
            ProviderEndpoint.base(settings)
            require(name.isNotBlank() && settings.model.isNotBlank()) { "请填写预设名称和模型 ID" }
            val preset = ApiPreset(id ?: UUID.randomUUID().toString(), name.trim(), settings.copy(apiKey = settings.apiKey.trim(), baseUrl = settings.baseUrl.trim(), model = settings.model.trim(), provider = settings.route))
            val items = state.value.presets.filterNot { it.id == preset.id } + preset
            secrets.savePresets(preset.id, items)
            invalidateApiCheck()
            mutable.update { it.copy(presets = items, activePresetId = preset.id, settings = preset.settings, apiResult = "已保存并启用；请在新会话中使用。") }
        } catch (e: Exception) { error(e.message ?: "保存失败") }
    }
    fun selectPreset(id: String) {
        val preset = state.value.presets.firstOrNull { it.id == id } ?: return
        secrets.savePresets(id, state.value.presets)
        invalidateApiCheck()
        mutable.update { it.copy(activePresetId = id, settings = preset.settings) }
    }
    fun deletePreset(id: String) {
        val items = state.value.presets.filterNot { it.id == id }
        if (items.isEmpty()) { error("请至少保留一个预设"); return }
        val active = items.firstOrNull { it.id == state.value.activePresetId } ?: items.first()
        secrets.savePresets(active.id, items)
        invalidateApiCheck()
        mutable.update { it.copy(presets = items, activePresetId = active.id, settings = active.settings) }
    }
    fun invalidateApiCheck() {
        apiRevision++
        apiCheck?.cancel()
        mutable.update { it.copy(apiChecking = false, apiModels = emptyList(), apiResult = "") }
    }
    fun checkApi(settings: EngineSettings, listModels: Boolean) {
        val revision = ++apiRevision
        apiCheck?.cancel()
        mutable.update { it.copy(apiChecking = true, apiResult = if (listModels) "正在查询模型…" else "正在发送简短测试请求…", apiModels = emptyList()) }
        apiCheck = viewModelScope.launch {
            try {
                if (listModels) {
                    val models = withContext(Dispatchers.IO) { providerClient.models(settings) }
                    if (revision == apiRevision) mutable.update { it.copy(apiModels = models, apiResult = "发现 ${models.size} 个模型。列表不代表每个模型均可调用，请选择后测试。") }
                } else {
                    val result = withContext(Dispatchers.IO) { providerClient.test(settings) }
                    if (revision == apiRevision) mutable.update { it.copy(apiResult = result) }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (revision == apiRevision) mutable.update { it.copy(apiResult = (e.message ?: "测试失败").let { message -> if (settings.apiKey.isNotEmpty()) message.replace(settings.apiKey, "[已隐藏]") else message }) } }
            finally { if (revision == apiRevision) mutable.update { it.copy(apiChecking = false) } }
        }
    }
    /** The address actually in use, so a loopback workaround stays visible. */
    fun bridgeAddress(): String = api.address()

    fun connect(start: Boolean = true) {
        if (connecting?.isActive == true) return
        connecting = viewModelScope.launch {
            mutable.update { it.copy(connecting = true) }
            // Keep the real reason. "服务未连接" on its own made a healthy
            // service look broken when the real fault was elsewhere.
            var lastFailure = "尚未尝试连接"
            var failure = { value: String -> lastFailure = value }
            try {
                var health = runCatching { withContext(Dispatchers.IO) { api.call("health") } }
                    .onFailure { failure(describe(it)) }.getOrNull()
                if (health == null && start) {
                    TermuxConnection.bootstrap(getApplication(), token)
                    // Bootstrap returns immediately when a healthy service is
                    // already up, so retry against every candidate address rather
                    // than assuming loopback. A VPN can route the app's 127.0.0.1
                    // away from the device while Termux still reaches it.
                    for (attempt in 0 until 30) {
                        delay(500)
                        health = runCatching { withContext(Dispatchers.IO) { api.call("health") } }
                            .onFailure { failure(describe(it)) }.getOrNull()
                        if (health != null) break
                        if (attempt == 6 || attempt == 14) {
                            val reachable = runCatching {
                                withContext(Dispatchers.IO) {
                                    api.probeHosts().also { api.preferHost(it) }
                                }
                            }.onFailure { failure(describe(it)) }.getOrNull()
                            if (reachable != null) health = api.call("health")
                            if (health != null) break
                        }
                    }
                }
                if (health != null && health.string("version") != BRIDGE_VERSION && start) {
                    TermuxConnection.upgrade(getApplication(), token)
                    health = null
                    for (attempt in 0 until 40) {
                        delay(500)
                        val next = runCatching { withContext(Dispatchers.IO) { api.call("health") } }
                            .onFailure { failure(describe(it)) }.getOrNull()
                        if (next?.string("version") == BRIDGE_VERSION) { health = next; break }
                    }
                }
                if (health != null) {
                    // Mark connected before listing workspaces: the health call is
                    // the real proof the service is up, and a failure while listing
                    // must not leave the UI claiming the service is unreachable.
                    mutable.update { it.copy(connected = true, health = health) }
                    val workspaces = runCatching {
                        withContext(Dispatchers.IO) { api.call("workspaces").objects("items").map(Workspace::from) }
                    }.onFailure { failure(describe(it)) }.getOrDefault(state.value.workspaces)
                    mutable.update { it.copy(workspaces = workspaces) }
                    if (state.value.selected == null && workspaces.isNotEmpty()) select(workspaces.first())
                } else {
                    mutable.update { it.copy(connected = false) }
                    if (start) error("本地服务无响应（$lastFailure；已尝试 ${api.candidates.joinToString("/")}）。" +
                        "若 Termux 显示 local service already running，说明服务在跑但 App 连不上：" +
                        "请检查 VPN/代理是否接管了本应用，或在 Termux 执行 pkill -f server.mjs 后重试。")
                }
            } catch (e: Exception) { error(describe(e)) }
            finally { mutable.update { it.copy(connecting = false) } }
        }
    }
    private fun task(block: suspend () -> Unit) = viewModelScope.launch {
        try { block() } catch (e: Exception) { error(e.message ?: "操作失败") }
    }
    fun select(workspace: Workspace) {
        selectionRevision++
        monitor?.cancel()
        mutable.update { it.copy(selected = workspace, chat = null, chats = emptyList(), terminalId = null, consent = false, entries = emptyList(), browserPath = workspace.path) }
        browse(workspace.path)
        val revision = selectionRevision
        task {
            val chats = withContext(Dispatchers.IO) { api.call("chats", query = mapOf("workspaceId" to workspace.id)).objects("items") }
            if (revision == selectionRevision) {
                mutable.update { it.copy(chats = chats) }
                chats.firstOrNull()?.let { openChat(it.getString("id")) }
            }
        }
    }
    fun browse(path: String = "", dirsOnly: Boolean = false) {
        val revision = ++browserRevision
        mutable.update { it.copy(browserLoading = true) }
        task {
            try {
                val result = withContext(Dispatchers.IO) { api.call("browse", query = mapOf("path" to path, "dirs" to dirsOnly.toString())) }
                if (revision == browserRevision) mutable.update { it.copy(browserPath = result.getString("path"), browserParent = result.getString("parent"), entries = result.objects("entries").map(FileEntry::from)) }
            } finally { if (revision == browserRevision) mutable.update { it.copy(browserLoading = false) } }
        }
    }
    fun addWorkspace(path: String) = task {
        val workspace = withContext(Dispatchers.IO) { Workspace.from(api.call("workspaces", "POST", JSONObject().put("path", path))) }
        val all = withContext(Dispatchers.IO) { api.call("workspaces").objects("items").map(Workspace::from) }
        mutable.update { it.copy(workspaces = all) }
        select(workspace)
    }
    fun createDirectory(name: String) = task {
        val parent = state.value.browserPath
        withContext(Dispatchers.IO) { api.call("directories", "POST", JSONObject().put("parent", parent).put("name", name)) }
        browse(parent)
    }
    fun preview(entry: FileEntry) = task {
        val workspace = state.value.selected ?: return@task
        val value = withContext(Dispatchers.IO) { api.call("file", query = mapOf("workspaceId" to workspace.id, "path" to entry.path)) }
        mutable.update { it.copy(preview = value) }
    }
    fun closePreview() = mutable.update { it.copy(preview = null) }
    fun newChat() = task {
        val workspace = state.value.selected ?: return@task
        val value = withContext(Dispatchers.IO) { api.call("chats", "POST", JSONObject().put("workspaceId", workspace.id)) }
        if (state.value.selected?.id != workspace.id) return@task
        mutable.update { it.copy(chat = value, chats = listOf(value) + it.chats, consent = false) }
        monitor(value.getString("id"))
    }
    fun openChat(id: String) = task {
        val workspaceId = state.value.selected?.id
        val value = withContext(Dispatchers.IO) { api.call("chats/$id") }
        if (value.string("workspaceId") != workspaceId || workspaceId != state.value.selected?.id) return@task
        mutable.update { it.copy(chat = value, consent = false) }
        monitor(id)
    }
    // Live updates: prefer the SSE push channel, and keep polling as a floor.
    // The first version stopped supervising as soon as the first frame arrived,
    // so a stream that dropped later (screen lock, Termux restart) never
    // reconnected and never fell back — the chat silently froze.
    private fun monitor(id: String) {
        monitor?.cancel()
        streamCall?.cancel()
        streaming = id
        monitor = viewModelScope.launch {
            while (streaming == id) {
                val since = mutable.value.chat?.optInt("revision", -1) ?: -1
                val delivered = if (openStream(id)) awaitStreamEnd(id) else false
                if (streaming != id) return@launch
                if (delivered) {
                    // Stream worked at least once. Reconnect promptly, but do not
                    // spin: give the service a moment to come back.
                    delay(1200)
                } else {
                    // Never got a usable stream: poll, and retry the stream later.
                    delay(2500)
                }
                if (streaming != id) return@launch
                if (delivered) continue
                try {
                    val chat = withContext(Dispatchers.IO) { api.call("chats/$id", query = mapOf("since" to since.toString())) }
                    if (streaming != id || state.value.chat?.string("id") != id) return@launch
                    if (!chat.optBoolean("unchanged", false)) mutable.update { it.copy(chat = chat, connected = true) }
                } catch (e: Exception) {
                    mutable.update { it.copy(connected = false) }
                    delay(2000)
                }
            }
        }
    }
    // Returns true once the session was deleted; false when the channel closed
    // for any other reason and should be re-established.
    private suspend fun awaitStreamEnd(id: String): Boolean = suspendCancellableCoroutine { cont ->
        val job = viewModelScope.launch {
            while (streaming == id && !streamEnded.contains(id)) delay(150)
            if (cont.isActive) cont.resume(streamEnded.remove(id)) {}
        }
        cont.invokeOnCancellation { job.cancel() }
    }
    // Resolves true once the first frame arrives (the bridge sends the current
    // snapshot immediately on subscribe), false if the stream dies before that.
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private suspend fun openStream(id: String): Boolean = suspendCancellableCoroutine { cont ->
        var settled = false
        val call = api.stream(id,
            onFrame = { frame ->
                if (streaming != id) return@stream
                if (!settled) { settled = true; if (cont.isActive) cont.resume(true) {} }
                when {
                    frame.deleted -> {
                        mutable.update { it.copy(chats = it.chats.filterNot { c -> c.string("id") == id },
                            chat = if (it.chat?.string("id") == id) null else it.chat) }
                        streaming = null
                        streamEnded.add(id)
                    }
                    frame.chat != null -> {
                        val chat = frame.chat
                        mutable.update { state ->
                            val index = state.chats.indexOfFirst { it.string("id") == id }
                            val chats = if (index >= 0) state.chats.toMutableList().also { it[index] = chat } else state.chats
                            state.copy(chat = if (state.chat?.string("id") == id) chat else state.chat, chats = chats, connected = true)
                        }
                    }
                }
            },
            onClosed = {
                if (streaming != id) return@stream
                mutable.update { it.copy(connected = false) }
                streamEnded.add(id)
                if (!settled) { settled = true; if (cont.isActive) cont.resume(false) {} }
            })
        streamCall = call
        cont.invokeOnCancellation { call.cancel() }
    }
    fun send(prompt: String) = task {
        val state = state.value
        val chat = state.chat ?: return@task
        val settings = state.settings
        val value = withContext(Dispatchers.IO) {
            api.call("chats/${chat.getString("id")}/prompt", "POST", JSONObject().put("prompt", prompt)
                .put("model", settings.model).put("provider", settings.route).put("apiKey", settings.apiKey)
                .put("protocol", settings.protocol).put("autoVersion", settings.autoVersion)
                .put("contextWindow", settings.contextWindow).put("maxTokens", settings.maxTokens)
                .put("baseUrl", settings.baseUrl).put("allowExecution", state.consent))
        }
        if (mutable.value.chat?.string("id") == value.string("id")) mutable.update { it.copy(chat = value) }
    }
    fun stop() = task {
        val id = state.value.chat?.string("id") ?: return@task
        val value = withContext(Dispatchers.IO) { api.call("chats/$id/stop", "POST") }
        if (mutable.value.chat?.string("id") == id) mutable.update { it.copy(chat = value) }
    }
    fun starChat(chat: JSONObject) = task {
        val id = chat.getString("id")
        val next = !chat.optBoolean("starred", false)
        val value = withContext(Dispatchers.IO) { api.call("chats/$id/star", "POST", JSONObject().put("starred", next)) }
        mutable.update { state ->
            val items = state.chats.map { if (it.string("id") == id) value else it }
                .sortedByDescending { it.optBoolean("starred", false) }
            state.copy(chats = items, chat = if (state.chat?.string("id") == id) value else state.chat)
        }
    }
    fun deleteChat(chat: JSONObject) = task {
        val id = chat.getString("id")
        withContext(Dispatchers.IO) { api.call("chats/$id", "DELETE") }
        val wasOpen = state.value.chat?.string("id") == id
        mutable.update { state -> state.copy(
            chats = state.chats.filterNot { it.string("id") == id },
            chat = if (wasOpen) null else state.chat, consent = if (wasOpen) false else state.consent) }
        if (wasOpen) streaming = null
    }
    fun starWorkspace(workspace: Workspace) = task {
        val value = withContext(Dispatchers.IO) {
            Workspace.from(api.call("workspaces/${workspace.id}/star", "POST", JSONObject().put("starred", !workspace.starred)))
        }
        val all = mutable.value.workspaces.map { if (it.id == value.id) value else it }
            .sortedByDescending { it.starred }
        mutable.update { it.copy(workspaces = all) }
    }
    fun deleteWorkspace(workspace: Workspace) = task {
        withContext(Dispatchers.IO) { api.call("workspaces/${workspace.id}", "DELETE") }
        val all = withContext(Dispatchers.IO) { api.call("workspaces").objects("items").map(Workspace::from) }
        val wasSelected = state.value.selected?.id == workspace.id
        mutable.update { it.copy(workspaces = all, selected = if (wasSelected) null else it.selected,
            chat = if (wasSelected) null else it.chat, chats = if (wasSelected) emptyList() else it.chats,
            entries = if (wasSelected) emptyList() else it.entries, browserPath = if (wasSelected) "" else it.browserPath,
            terminalId = if (wasSelected) null else it.terminalId) }
        if (wasSelected) {
            streaming = null
            streamCall?.cancel()
            // Only when the removed project was the active one do we move on to
            // a replacement. Removing some other workspace must leave the
            // current project, chat and terminal exactly as they were.
            val next = mutable.value.workspaces.firstOrNull()
            if (next != null) select(next)
        }
    }
    fun terminal() = task {
        val workspace = state.value.selected ?: return@task
        val value = withContext(Dispatchers.IO) { api.call("terminals", "POST", JSONObject().put("workspaceId", workspace.id)) }
        if (state.value.selected?.id == workspace.id) mutable.update { it.copy(terminalId = value.getString("id")) }
    }
    fun closeTerminal() = task {
        val id = state.value.terminalId ?: return@task
        withContext(Dispatchers.IO) { api.call("terminals/$id", "DELETE") }
        mutable.update { if (it.terminalId == id) it.copy(terminalId = null) else it }
    }
}

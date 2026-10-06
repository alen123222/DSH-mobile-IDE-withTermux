package dev.dsh.pocket

import android.app.Application
import android.os.Build
import android.os.storage.StorageManager
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

/** A well-known starting point, with whether this Termux can actually read it. */
data class Shortcut(val label: String, val path: String, val available: Boolean, val reason: String = "")

data class PocketState(
    val connected: Boolean = false, val connecting: Boolean = false, val health: JSONObject? = null,
    val workspaces: List<Workspace> = emptyList(), val selected: Workspace? = null,
    val browserPath: String = "", val browserParent: String = "", val entries: List<FileEntry> = emptyList(),
    val browserLoading: Boolean = false, val browserTruncated: Boolean = false,
    val shortcuts: List<Shortcut> = emptyList(), val chats: List<JSONObject> = emptyList(), val chat: JSONObject? = null,
    val settings: EngineSettings = EngineSettings(), val error: String? = null, val viewer: OpenFile? = null,
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
    private var monitorRevision = 0
    private var connecting: Job? = null
    private var selectionRevision = 0
    private var browserRevision = 0
    private var browseCall: Job? = null
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
            require(name.isNotBlank() && settings.model.isNotBlank()) { tr("请填写预设名称和模型 ID") }
            val preset = ApiPreset(id ?: UUID.randomUUID().toString(), name.trim(), settings.copy(apiKey = settings.apiKey.trim(), baseUrl = settings.baseUrl.trim(), model = settings.model.trim(), provider = settings.route))
            val items = state.value.presets.filterNot { it.id == preset.id } + preset
            secrets.savePresets(preset.id, items)
            invalidateApiCheck()
            mutable.update { it.copy(presets = items, activePresetId = preset.id, settings = preset.settings, apiResult = tr("已保存并启用；下一条消息生效。")) }
        } catch (e: Exception) { error(e.message ?: tr("保存失败")) }
    }
    fun selectPreset(id: String) {
        val preset = state.value.presets.firstOrNull { it.id == id } ?: return
        secrets.savePresets(id, state.value.presets)
        invalidateApiCheck()
        mutable.update { it.copy(activePresetId = id, settings = preset.settings) }
    }
    fun deletePreset(id: String) {
        val items = state.value.presets.filterNot { it.id == id }
        if (items.isEmpty()) { error(tr("请至少保留一个预设")); return }
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
        mutable.update { it.copy(apiChecking = true, apiResult = if (listModels) tr("正在查询模型…") else tr("正在发送简短测试请求…"), apiModels = emptyList()) }
        apiCheck = viewModelScope.launch {
            try {
                if (listModels) {
                    val models = withContext(Dispatchers.IO) { providerClient.models(settings) }
                    if (revision == apiRevision) mutable.update { it.copy(apiModels = models, apiResult = tr("发现 ") + models.size + tr(" 个模型。列表不代表每个模型均可调用，请选择后测试。")) }
                } else {
                    val result = withContext(Dispatchers.IO) { providerClient.test(settings) }
                    if (revision == apiRevision) mutable.update { it.copy(apiResult = result) }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (revision == apiRevision) mutable.update { it.copy(apiResult = (e.message ?: tr("测试失败")).let { message -> if (settings.apiKey.isNotEmpty()) message.replace(settings.apiKey, tr("[已隐藏]")) else message }) } }
            finally { if (revision == apiRevision) mutable.update { it.copy(apiChecking = false) } }
        }
    }
    /** The address actually in use, so a loopback workaround stays visible. */
    fun bridgeAddress(): String = api.address()

    // Verbatim transport diagnostics. Termux can prove the service is healthy
    // while the app still cannot reach it, and guessing between the possible
    // causes has been wrong twice; this reports what the app really observes.
    fun probeReport(onDone: (String) -> Unit) = viewModelScope.launch {
        val report = withContext(Dispatchers.IO) { api.probeReport() }
        onDone(report)
    }

    fun connect(start: Boolean = true) {
        if (connecting?.isActive == true) return
        connecting = viewModelScope.launch {
            mutable.update { it.copy(connecting = true) }
            // Keep the real reason. "服务未连接" on its own made a healthy
            // service look broken when the real fault was elsewhere.
            var lastFailure = tr("尚未尝试连接")
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
                            if (reachable != null) health = withContext(Dispatchers.IO) { api.call("health") }
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
                    if (start) error(tr("本地服务无响应（") + lastFailure + tr("；已尝试 ") + api.candidates.joinToString("/") + tr("）。") +
                        tr("若 Termux 显示 local service already running，说明服务在跑但 App 连不上；") +
                        tr("请到 设置 → WLAN → 当前网络 → 高级 → 代理 改为「无」，或重启手机后再试。"))
                }
            } catch (e: Exception) { error(describe(e)) }
            finally { mutable.update { it.copy(connecting = false) } }
        }
    }
    private fun task(block: suspend () -> Unit) = viewModelScope.launch {
        try { block() } catch (e: CancellationException) { throw e } catch (e: Exception) { error(e.message ?: tr("操作失败")) }
    }
    fun select(workspace: Workspace) {
        selectionRevision++
        stopMonitor()
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
    // Browsing shared storage is slow, and every tap used to fire a fresh
    // request while the previous one was still running. The responses then
    // arrived out of order, each discarded by the revision check, and the pane
    // sat on a permanent spinner with every folder looking unresponsive.
    // Cancel the in-flight call, keep one request at a time, and always clear
    // the loading state.
    fun browse(path: String = "", dirsOnly: Boolean = false) {
        if (browseCall?.isActive == true) browseCall?.cancel()
        val revision = ++browserRevision
        mutable.update { it.copy(browserLoading = true) }
        browseCall = viewModelScope.launch {
            var loaded: JSONObject? = null
            var failure: String? = null
            try {
                loaded = withContext(Dispatchers.IO) {
                    api.call("browse", query = mapOf("path" to path, "dirs" to dirsOnly.toString()))
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                failure = describe(error)
            }
            if (revision != browserRevision) return@launch
            if (loaded != null) mutable.update { it.copy(
                browserPath = loaded!!.getString("path"), browserParent = loaded!!.getString("parent"),
                entries = loaded!!.objects("entries").map(FileEntry::from), browserTruncated = loaded!!.optBoolean("truncated")) }
            else mutable.update { it.copy(entries = emptyList(), browserTruncated = false) }
            failure?.let { error(it) }
            mutable.update { it.copy(browserLoading = false) }
        }
    }
    /** Go up one level. Kept separate so the parent path is never blank. */
    fun browseParent() {
        val parent = state.value.browserParent
        if (parent.isBlank() || parent == state.value.browserPath) return
        browse(parent)
    }

    /**
     * Picker shortcuts, fetched off the main thread and cached in state.
     *
     * The fallback must not claim a shortcut is unavailable: the server answers
     * for the running Termux, and if this call fails the honest answer is "not
     * known", not "no permission". A previous fallback marked every entry
     * unavailable with an empty path, so a failed request rendered 主目录 as
     * permanently disabled, which is why home appeared to have no permission.
     */
    fun loadShortcuts() = viewModelScope.launch {
        val items = runCatching {
            withContext(Dispatchers.IO) {
                val storage = getApplication<Application>().getSystemService(StorageManager::class.java)
                val volumes = storage.storageVolumes.filter { it.isRemovable }.mapNotNull {
                    if (Build.VERSION.SDK_INT >= 30) it.directory?.absolutePath
                    else it.uuid?.let { uuid -> "/storage/$uuid" }
                }
                api.call("shortcuts", query = mapOf("external" to volumes.joinToString("\n"))).objects("items").map {
                    Shortcut(it.getString("label"), it.getString("path"), it.optBoolean("available"), it.string("reason"))
                }
            }
        }.getOrElse {
            // Unknown, not forbidden: keep them tappable so tapping still tries,
            // and the browse call reports the real error if there is one. An
            // empty path means "the server default", which is the same thing as
            // home for this bridge, so it is not an empty target after all.
            listOf(Shortcut(tr("主目录"), "", true), Shortcut(tr("内部存储"), "/storage/emulated/0", true))
        }
        mutable.update { it.copy(shortcuts = items) }
    }

    fun addWorkspace(path: String, onSuccess: () -> Unit = {}) = task {
        val workspace = withContext(Dispatchers.IO) { Workspace.from(api.call("workspaces", "POST", JSONObject().put("path", path))) }
        val all = withContext(Dispatchers.IO) { api.call("workspaces").objects("items").map(Workspace::from) }
        mutable.update { it.copy(workspaces = all) }
        select(workspace)
        onSuccess()
    }
    fun createDirectory(name: String, parent: String = state.value.browserPath, onSuccess: () -> Unit = {}) = task {
        withContext(Dispatchers.IO) { api.call("directories", "POST", JSONObject().put("parent", parent).put("name", name)) }
        browse(parent, true)
        onSuccess()
    }
    /**
     * Open one file. Text comes back inline; images, PDFs and archives are
     * streamed to the app cache, because rendering them needs a real file.
     */
    fun openFile(entry: FileEntry) = task {
        val workspace = state.value.selected ?: return@task
        val info = withContext(Dispatchers.IO) {
            api.call("file", query = mapOf("workspaceId" to workspace.id, "path" to entry.path))
        }
        val kind = info.string("kind", "binary")
        var cache: java.io.File? = null
        if (kind != "text") {
            val directory = java.io.File(getApplication<Application>().cacheDir, "open").apply { mkdirs() }
            val target = java.io.File(directory, info.string("name", entry.name))
            withContext(Dispatchers.IO) {
                api.download("raw", mapOf("workspaceId" to workspace.id, "path" to entry.path), target)
            }
            cache = target
        }
        mutable.update { it.copy(viewer = OpenFile.from(info, cache)) }
    }
    fun closeFile() = mutable.update { it.copy(viewer = null) }
    /** Unpack a zip next to itself, then show the folder that now holds it. */
    fun extractArchive(path: String, onResult: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            val workspace = state.value.selected ?: return@launch
            try {
                val result = withContext(Dispatchers.IO) {
                    api.call("extract", "POST", JSONObject().put("workspaceId", workspace.id).put("path", path))
                }
                val destination = result.optString("path")
                mutable.update { it.copy(viewer = null) }
                browse(destination.substringBeforeLast('/'))
                onResult(true, tr("已解压 ") + result.optInt("entries") + tr(" 个文件到 ") + destination)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { onResult(false, e.message ?: tr("解压失败")) }
        }
    }
    /** Save an edit. force skips the mtime fence once the user has seen the conflict. */
    fun saveFile(path: String, content: String, ifMtime: Long, force: Boolean, onResult: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            val workspace = state.value.selected
            if (workspace == null) { onResult(false, tr("未选择工作区")); return@launch }
            try {
                val body = JSONObject().put("workspaceId", workspace.id).put("path", path).put("content", content)
                if (!force && ifMtime > 0) body.put("ifMtime", ifMtime)
                val saved = withContext(Dispatchers.IO) { api.call("file", "POST", body) }
                mutable.update { current ->
                    val viewer = current.viewer
                    if (viewer == null || viewer.path != path) current
                    else current.copy(viewer = viewer.copy(text = content,
                        size = saved.optLong("size", viewer.size), mtime = saved.optLong("mtime", viewer.mtime)))
                }
                onResult(true, tr("已保存"))
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { onResult(false, e.message ?: tr("保存失败")) }
        }
    }
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
    private fun stopMonitor() {
        monitorRevision++
        streaming = null
        monitor?.cancel()
        streamCall?.cancel()
        streamCall = null
    }
    override fun onCleared() {
        stopMonitor()
        super.onCleared()
    }
    private fun monitor(id: String) {
        stopMonitor()
        streaming = id
        val revision = monitorRevision
        monitor = viewModelScope.launch {
            while (streaming == id && revision == monitorRevision) {
                openStream(id, revision) // Wait for closure, not merely the first frame.
                if (streaming != id || revision != monitorRevision) return@launch
                delay(1200)
                try {
                    val since = state.value.chat?.optInt("revision", -1) ?: -1
                    val chat = withContext(Dispatchers.IO) { api.call("chats/$id", query = mapOf("since" to since.toString())) }
                    if (streaming != id || revision != monitorRevision) return@launch
                    mutable.update { it.copy(connected = true, chat = if (chat.optBoolean("unchanged")) it.chat else chat) }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    mutable.update { it.copy(connected = false) }
                    delay(2000)
                }
            }
        }
    }
    private suspend fun openStream(id: String, revision: Int): Unit = suspendCancellableCoroutine { cont ->
        val call = api.stream(id,
            onFrame = { frame -> viewModelScope.launch {
                if (streaming != id || revision != monitorRevision) return@launch
                if (frame.deleted) {
                    mutable.update { it.copy(chats = it.chats.filterNot { c -> c.string("id") == id }, chat = null, consent = false) }
                    stopMonitor()
                } else frame.chat?.let { chat ->
                    mutable.update { state -> state.copy(chat = chat, connected = true,
                        chats = state.chats.map { if (it.string("id") == id) chat else it }) }
                }
            } },
            onClosed = {
                if (cont.isActive) cont.resume(Unit)
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
        if (wasOpen) stopMonitor()
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
        // Only remove local state after the server confirms deletion. A failed
        // request preserves the project and any conversation the user is viewing.
        withContext(Dispatchers.IO) { api.call("workspaces/${workspace.id}", "DELETE") }
        val wasSelected = state.value.selected?.id == workspace.id
        if (wasSelected) {
            selectionRevision++
            stopMonitor()
            browseCall?.cancel()
            browserRevision++
        }
        mutable.update { it.copy(
            workspaces = it.workspaces.filterNot { item -> item.id == workspace.id },
            selected = if (wasSelected) null else it.selected,
            chat = if (wasSelected) null else it.chat, chats = if (wasSelected) emptyList() else it.chats,
            entries = if (wasSelected) emptyList() else it.entries, browserPath = if (wasSelected) "" else it.browserPath,
            browserParent = if (wasSelected) "" else it.browserParent,
            browserLoading = if (wasSelected) false else it.browserLoading,
            consent = if (wasSelected) false else it.consent,
            terminalId = if (wasSelected) null else it.terminalId) }
        if (wasSelected) state.value.workspaces.firstOrNull()?.let(::select)
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
    /**
     * "Open in terminal" from the viewer. An existing shell for this workspace is
     * reused with a cd, so terminal history survives the jump; otherwise a new
     * one starts directly in that folder.
     */
    fun openTerminalAt(directory: String, command: String? = null) {
        viewModelScope.launch {
            val workspace = state.value.selected ?: return@launch
            val line = "cd " + shellQuote(directory) + NEWLINE + (command?.let { it + NEWLINE } ?: "")
            val existing = state.value.terminalId
            if (existing != null) {
                val moved = runCatching {
                    withContext(Dispatchers.IO) { api.call("terminals/$existing", "POST", terminalInput(line)) }
                }.isSuccess
                if (moved) return@launch
            }
            runCatching {
                withContext(Dispatchers.IO) {
                    api.call("terminals", "POST", JSONObject().put("workspaceId", workspace.id).put("cwd", directory))
                }
            }.onSuccess { value ->
                val id = value.getString("id")
                mutable.update { it.copy(terminalId = id) }
                // A fresh shell already starts in that directory; only the command
                // still has to be typed into it.
                if (command != null) runCatching {
                    withContext(Dispatchers.IO) { api.call("terminals/$id", "POST", terminalInput(command + NEWLINE)) }
                }
            }.onFailure { error(it.message ?: tr("打开终端失败")) }
        }
    }
    private fun terminalInput(text: String) = JSONObject().put("type", "input")
        .put("data", android.util.Base64.encodeToString(text.toByteArray(), android.util.Base64.NO_WRAP))
}

// Quoting built from char codes: a backslash literal in the source was exactly
// the kind of escaping that broke this project's launch script once already.
private val NEWLINE = 0x0a.toChar()
internal fun shellQuote(value: String): String {
    val quote = 0x27.toChar()
    val backslash = 0x5c.toChar()
    return quote + value.replace(quote.toString(), quote.toString() + backslash + quote + quote) + quote
}

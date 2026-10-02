package dev.dsh.pocket

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.json.JSONObject

private val Blue = Color(0xFF2563EB)
private val Surface = Color(0xFFF7F8FC)

class MainActivity : ComponentActivity() {
    private val model: PocketModel by viewModels()
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) model.error("请在系统设置 → DSH Pocket → 权限中允许在 Termux 运行命令")
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Blue, background = Surface, surface = Color.White,
                surfaceVariant = Color(0xFFF0F3F9), outline = Color(0xFFCBD5E1))) {
                PocketApp(model) { permission.launch(TermuxConnection.PERMISSION) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PocketApp(model: PocketModel, grant: () -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var workspacePicker by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val safe: (() -> Unit) -> Unit = { block -> try { block() } catch (e: Exception) { model.error(e.message ?: "操作失败") } }
    LaunchedEffect(state.error) { state.error?.let { snackbar.showSnackbar(it); model.dismissError() } }
    val addWorkspace = { model.browse(state.health?.string("home").orEmpty(), true); workspacePicker = true }
    BoxWithConstraints(Modifier.fillMaxSize().background(Surface).statusBarsPadding()) {
        val wide = maxWidth >= 840.dp
        val panel: @Composable () -> Unit = {
            Sidebar(state, onSelect = { model.select(it); scope.launch { drawer.close() }; tab = 0 },
                onAdd = addWorkspace, onChat = { model.openChat(it); tab = 0; scope.launch { drawer.close() } },
                onNew = { model.newChat(); tab = 0; scope.launch { drawer.close() } }, onSettings = { tab = 3; scope.launch { drawer.close() } },
                onStarChat = model::starChat, onDeleteChat = model::deleteChat,
                onStarWorkspace = model::starWorkspace, onDeleteWorkspace = model::deleteWorkspace)
        }
        val content: @Composable () -> Unit = {
            Scaffold(containerColor = Surface, snackbarHost = { SnackbarHost(snackbar) }, topBar = {
                TopAppBar(title = {
                    Column {
                        Text(state.selected?.name ?: "DSH Pocket", fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(if (state.connected) "● 本地已连接 · Termux" else "○ 等待连接 Termux", style = MaterialTheme.typography.labelSmall,
                            color = if (state.connected) Color(0xFF16835F) else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }, navigationIcon = { if (!wide) IconButton(onClick = { scope.launch { drawer.open() } }) { Icon(Icons.Outlined.Menu, "工作区与会话") } },
                    actions = {
                        if (state.selected != null) IconButton(onClick = { model.newChat(); tab = 0 }) { Icon(Icons.Outlined.AddComment, "新建会话") }
                        IconButton(onClick = { model.connect(); tab = if (state.connected) tab else 3 }) { Icon(Icons.Outlined.Refresh, "连接或刷新") }
                    }, colors = TopAppBarDefaults.topAppBarColors(containerColor = Surface))
            }, bottomBar = {
                NavigationBar(containerColor = Color.White, tonalElevation = 0.dp) {
                    val tabs = listOf("对话" to Icons.Outlined.ChatBubbleOutline, "文件" to Icons.Outlined.FolderOpen, "终端" to Icons.Outlined.Terminal, "环境" to Icons.Outlined.Tune)
                    tabs.forEachIndexed { index, (label, icon) ->
                        NavigationBarItem(selected = tab == index, onClick = {
                            tab = index
                            if (index == 1) state.selected?.let { model.browse(it.path) }
                            if (index == 2 && state.selected != null && state.terminalId == null) model.terminal()
                        }, icon = { Icon(icon, label) }, label = { Text(label) })
                    }
                }
            }) { padding ->
                Box(Modifier.padding(padding).fillMaxSize().imePadding()) {
                    when (tab) {
                        0 -> ChatPane(state, model, onSetup = { tab = 3 }, onWorkspace = addWorkspace)
                        1 -> FilesPane(state, model, addWorkspace)
                        2 -> if (state.terminalId != null) key(state.terminalId) {
                            TerminalPane(state.terminalId!!, model.api, model::error, { model.closeTerminal() }, Modifier.fillMaxSize())
                        } else EmptyPanel(Icons.Outlined.Terminal, "你的完整终端", "进入项目目录，运行熟悉的命令。", if (state.selected == null) "选择工作区" else "打开终端") {
                            if (state.selected == null) addWorkspace() else model.terminal()
                        }
                        3 -> EnvironmentPane(state, model, grant, { block -> safe(block) })
                    }
                }
            }
        }
        if (wide) Row(Modifier.fillMaxSize()) {
            Box(Modifier.width(264.dp).fillMaxHeight()) { panel() }
            VerticalDivider(color = Color(0xFFE5EAF1))
            Box(Modifier.weight(1f).fillMaxHeight()) { content() }
        } else ModalNavigationDrawer(drawerState = drawer, drawerContent = { ModalDrawerSheet { Box(Modifier.width(288.dp)) { panel() } } }) { content() }
    }
    if (workspacePicker) WorkspaceDialog(state, model, { workspacePicker = false }, { workspacePicker = false; safe { TermuxConnection.setupStorage(context) } })
    state.preview?.let { preview ->
        AlertDialog(onDismissRequest = model::closePreview, title = { Text(preview.string("path").substringAfterLast('/')) },
            text = { SelectionContainer { Text(preview.string("content"), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) } },
            confirmButton = { TextButton(onClick = model::closePreview) { Text("关闭") } })
    }
}

@Composable
private fun Sidebar(state: PocketState, onSelect: (Workspace) -> Unit, onAdd: () -> Unit, onChat: (String) -> Unit,
                    onNew: () -> Unit, onSettings: () -> Unit, onStarChat: (JSONObject) -> Unit,
                    onDeleteChat: (JSONObject) -> Unit, onStarWorkspace: (Workspace) -> Unit, onDeleteWorkspace: (Workspace) -> Unit) {
    var chatMenu by remember { mutableStateOf<JSONObject?>(null) }
    var workspaceMenu by remember { mutableStateOf<Workspace?>(null) }
    var chatDelete by remember { mutableStateOf<JSONObject?>(null) }
    var workspaceDelete by remember { mutableStateOf<Workspace?>(null) }
    Column(Modifier.fillMaxSize().background(Color(0xFFF0F3F9)).padding(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 12.dp)) {
            Surface(color = Blue, shape = RoundedCornerShape(12.dp)) { Icon(Icons.Outlined.Terminal, null, tint = Color.White, modifier = Modifier.padding(10.dp)) }
            Column(Modifier.padding(start = 12.dp)) {
                Text("DSH Pocket", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                Text("你的移动开发空间", color = Color(0xFF64748B), style = MaterialTheme.typography.labelSmall)
            }
        }
        Spacer(Modifier.height(22.dp))
        Button(onClick = onNew, enabled = state.connected && state.selected != null, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
            Icon(Icons.Outlined.Add, null); Spacer(Modifier.width(8.dp)); Text("新建对话")
        }
        Row(Modifier.fillMaxWidth().padding(top = 24.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text("工作区", style = MaterialTheme.typography.labelMedium, color = Color(0xFF64748B))
            IconButton(onClick = onAdd, enabled = state.connected) { Icon(Icons.Outlined.CreateNewFolder, "添加工作区", modifier = Modifier.size(20.dp)) }
        }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(state.workspaces, key = { it.id }) { workspace ->
                Surface(color = if (state.selected?.id == workspace.id) Color.White else Color.Transparent, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth().clickable { onSelect(workspace) }) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (workspace.starred) Icons.Outlined.Star else Icons.Outlined.Folder, null,
                            tint = if (workspace.starred) Color(0xFFE0A800) else if (workspace.available) Blue else Color.Gray, modifier = Modifier.size(20.dp))
                        Column(Modifier.padding(start = 10.dp).weight(1f)) {
                            Text(workspace.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                            Text(workspace.path, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Color(0xFF64748B), style = MaterialTheme.typography.labelSmall)
                        }
                        IconButton(onClick = { workspaceMenu = workspace }, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Outlined.MoreVert, "工作区操作", modifier = Modifier.size(17.dp), tint = Color(0xFF94A3B8))
                        }
                    }
                }
            }
            item { Text("最近对话", color = Color(0xFF64748B), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 24.dp, bottom = 8.dp)) }
            items(state.chats, key = { it.string("id") }) { chat ->
                val starred = chat.optBoolean("starred", false)
                Row(Modifier.fillMaxWidth().clickable { onChat(chat.getString("id")) }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (starred) Icons.Outlined.Star else Icons.Outlined.ChatBubbleOutline, null,
                        modifier = Modifier.size(16.dp), tint = if (starred) Color(0xFFE0A800) else Color(0xFF64748B))
                    Column(Modifier.padding(start = 10.dp).weight(1f)) {
                        Text(chat.string("title"), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                        val status = chat.string("status")
                        if (status == "running" || status == "error") Text(
                            if (status == "running") "运行中" else "出错", style = MaterialTheme.typography.labelSmall,
                            color = if (status == "running") Blue else MaterialTheme.colorScheme.error)
                    }
                    IconButton(onClick = { chatMenu = chat }, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Outlined.MoreVert, "对话操作", modifier = Modifier.size(16.dp), tint = Color(0xFF94A3B8))
                    }
                }
            }
        }
        HorizontalDivider(color = Color(0xFFE0E6F0))
        TextButton(onClick = onSettings, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Outlined.Tune, null); Spacer(Modifier.width(8.dp)); Text("连接与模型设置") }
        Text("${BuildConfig.VERSION_NAME} · 开发预览", style = MaterialTheme.typography.labelSmall, color = Color(0xFF94A3B8), modifier = Modifier.padding(12.dp))
    }
    chatMenu?.let { chat ->
        DropdownMenu(expanded = true, onDismissRequest = { chatMenu = null }) {
            DropdownMenuItem(text = { Text(if (chat.optBoolean("starred", false)) "取消星标" else "星标置顶") },
                leadingIcon = { Icon(if (chat.optBoolean("starred", false)) Icons.Outlined.Star else Icons.Outlined.StarBorder, null) },
                onClick = { chatMenu = null; onStarChat(chat) })
            DropdownMenuItem(text = { Text("删除对话") }, leadingIcon = { Icon(Icons.Outlined.Delete, null) },
                onClick = { chatMenu = null; chatDelete = chat })
        }
    }
    workspaceMenu?.let { workspace ->
        DropdownMenu(expanded = true, onDismissRequest = { workspaceMenu = null }) {
            DropdownMenuItem(text = { Text(if (workspace.starred) "取消星标" else "星标置顶") },
                leadingIcon = { Icon(if (workspace.starred) Icons.Outlined.Star else Icons.Outlined.StarBorder, null) },
                onClick = { workspaceMenu = null; onStarWorkspace(workspace) })
            DropdownMenuItem(text = { Text("从列表移除") },
                leadingIcon = { Icon(Icons.Outlined.FolderOff, null) },
                onClick = { workspaceMenu = null; workspaceDelete = workspace })
        }
    }
    chatDelete?.let { chat -> AlertDialog(onDismissRequest = { chatDelete = null },
        title = { Text("删除这个对话？") },
        text = { Text("「${chat.string("title")}」的消息与工具记录会一并删除，项目文件不受影响。") },
        confirmButton = { TextButton(onClick = { chatDelete = null; onDeleteChat(chat) }) { Text("删除", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { chatDelete = null }) { Text("取消") } }) }
    workspaceDelete?.let { workspace -> AlertDialog(onDismissRequest = { workspaceDelete = null },
        title = { Text("从列表移除工作区？") },
        text = { Text("${workspace.path}\n\n只会移除这条记录，磁盘上的项目文件不会被删除。") },
        confirmButton = { TextButton(onClick = { workspaceDelete = null; onDeleteWorkspace(workspace) }) { Text("移除", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { workspaceDelete = null }) { Text("取消") } }) }
}

@Composable
private fun ChatPane(state: PocketState, model: PocketModel, onSetup: () -> Unit, onWorkspace: () -> Unit) {
    if (!state.connected) { EmptyPanel(Icons.Outlined.Hub, "把开发环境带在身边", "连接本机 Termux，在真实项目中对话、修改文件和运行命令。", "配置本地环境", onSetup); return }
    if (state.selected == null) { EmptyPanel(Icons.Outlined.FolderOpen, "从一个文件夹开始", "选择已有项目，或创建一个新的目录。文件保留在原位置。", "选择工作区", onWorkspace); return }
    if (state.chat == null) { EmptyPanel(Icons.Outlined.AutoAwesome, "今天想做些什么？", state.selected.path, "在此项目新建对话") { model.newChat() }; return }
    val chat = state.chat
    val messages = chat.objects("messages")
    val events = chat.objects("events")
    val status = chat.string("status")
    val running = status == "running"
    var input by rememberSaveable(chat.string("id")) { mutableStateOf("") }
    var showEvents by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    LaunchedEffect(messages.size) { if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1) }
    Column(Modifier.fillMaxSize()) {
        LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(horizontal = 24.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
            if (messages.isEmpty()) item {
                Column(Modifier.fillMaxWidth().padding(top = 56.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Outlined.Code, null, tint = Blue, modifier = Modifier.size(42.dp))
                    Text("从想法，到运行", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 18.dp))
                    Text("描述任务，DSH 会在当前项目里工作。", color = Color(0xFF64748B), modifier = Modifier.padding(top = 10.dp))
                }
            }
            items(messages, key = { it.string("id") }) { message ->
                val user = message.string("role") == "user"
                Row(Modifier.fillMaxWidth(), horizontalArrangement = if (user) Arrangement.End else Arrangement.Start) {
                    Surface(color = if (user) Color(0xFFE8EFFF) else Color.White, shape = RoundedCornerShape(18.dp), modifier = Modifier.widthIn(max = 740.dp)) {
                        Column(Modifier.padding(18.dp)) {
                            Text(if (user) "你" else "DSH", style = MaterialTheme.typography.labelMedium, color = if (user) Blue else Color(0xFF64748B), modifier = Modifier.padding(bottom = 8.dp))
                            SelectionContainer { Text(message.string("text"), style = MaterialTheme.typography.bodyLarge) }
                        }
                    }
                }
            }
            if (running) item { Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp); Text("DSH 正在工作…", modifier = Modifier.padding(start = 10.dp), color = Color(0xFF64748B)) } }
            if (chat.string("error").isNotBlank()) item { Text(chat.string("error"), color = MaterialTheme.colorScheme.error) }
        }
        if (events.isNotEmpty()) TextButton(onClick = { showEvents = true }, modifier = Modifier.padding(start = 16.dp)) {
            Icon(Icons.Outlined.DataObject, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("查看工具活动 · ${events.size}")
        }
        // The composer used to live in the final else branch, so a stopped
        // session showed "keep typing" with no way to actually type.
        val composable = status != "archived"
        if (!composable) {
            Surface(color = Color(0xFFF0F3F9), modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("这是上次运行留下的记录，只读。", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { model.newChat() }) { Text("新建会话") }
                }
            }
        }
        if (composable) Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp).widthIn(max = 1000.dp).fillMaxWidth()) {
            if (status in listOf("stopped", "error")) Surface(color = Color(0xFFFFF7E6), shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (status == "stopped") "任务已停止，记录已保存。继续提问会在同一目录重新启动 DSH。" else "上次运行出错。继续提问会重启 DSH。",
                        modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = Color(0xFF8A6D3B))
                    TextButton(onClick = { model.newChat() }) { Text("新建会话") }
                }
            }
            if (!state.consent) Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = state.consent, onCheckedChange = model::consent)
                Text("允许此会话在 Termux 权限范围内修改文件、执行命令", style = MaterialTheme.typography.bodySmall, color = Color(0xFF64748B), modifier = Modifier.clickable { model.consent(!state.consent) })
            }
            OutlinedTextField(value = input, onValueChange = { input = it }, placeholder = { Text("描述你想完成的任务…") }, modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 6,
                shape = RoundedCornerShape(18.dp), trailingIcon = {
                    if (running) IconButton(onClick = model::stop) { Icon(Icons.Outlined.StopCircle, "停止任务", tint = MaterialTheme.colorScheme.error) }
                    else IconButton(enabled = input.isNotBlank() && state.consent, onClick = { model.send(input); input = "" }) { Icon(Icons.AutoMirrored.Outlined.Send, "发送", tint = if (state.consent) Blue else Color.Gray) }
                })
            Text("${state.settings.model}  ·  ${state.selected.name}", style = MaterialTheme.typography.labelSmall, color = Color(0xFF94A3B8), modifier = Modifier.padding(top = 8.dp))
        }
    }
    if (showEvents) AlertDialog(onDismissRequest = { showEvents = false }, title = { Text("工具与运行活动") }, text = {
        LazyColumn(Modifier.heightIn(max = 480.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(events) { event -> Column { Text(event.string("type"), fontWeight = FontWeight.SemiBold, color = Blue); SelectionContainer { Text(event.opt("data")?.toString().orEmpty(), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) } } }
        }
    }, confirmButton = { TextButton(onClick = { showEvents = false }) { Text("关闭") } })
}

@Composable
private fun EmptyPanel(icon: ImageVector, title: String, subtitle: String, action: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Surface(color = Color(0xFFE8EFFF), shape = RoundedCornerShape(24.dp)) { Icon(icon, null, tint = Blue, modifier = Modifier.padding(24.dp).size(40.dp)) }
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 24.dp))
        Text(subtitle, color = Color(0xFF64748B), modifier = Modifier.padding(top = 12.dp, bottom = 24.dp), style = MaterialTheme.typography.bodyMedium)
        Button(onClick = onClick, shape = RoundedCornerShape(12.dp)) { Text(action) }
    }
}

@Composable
private fun FilesPane(state: PocketState, model: PocketModel, onWorkspace: () -> Unit) {
    if (state.selected == null) { EmptyPanel(Icons.Outlined.FolderOpen, "项目文件", "浏览真实文件，查看代码。", "选择工作区", onWorkspace); return }
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { model.browse(state.browserParent) }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "上一级") }
            Text(state.browserPath, modifier = Modifier.weight(1f), maxLines = 2, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            IconButton(onClick = { model.browse(state.browserPath) }) { Icon(Icons.Outlined.Refresh, "刷新文件") }
        }
        if (state.browserLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
        LazyColumn(Modifier.fillMaxSize()) {
            items(state.entries, key = { it.path }) { entry -> FileRow(entry) { if (entry.directory) model.browse(entry.path) else model.preview(entry) } }
        }
    }
}

@Composable
private fun FileRow(entry: FileEntry, onClick: () -> Unit) {
    ListItem(headlineContent = { Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(if (entry.directory) "文件夹" else "${entry.size} B", style = MaterialTheme.typography.labelSmall) },
        leadingContent = { Icon(if (entry.directory) Icons.Outlined.Folder else Icons.Outlined.Description, null, tint = if (entry.directory) Blue else Color(0xFF64748B)) },
        modifier = Modifier.clickable(onClick = onClick), colors = ListItemDefaults.colors(containerColor = Color.Transparent))
}

@Composable
private fun WorkspaceDialog(state: PocketState, model: PocketModel, dismiss: () -> Unit, grantStorage: () -> Unit) {
    var path by remember(state.browserPath) { mutableStateOf(state.browserPath) }
    var create by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = dismiss, title = { Text("选择工作区目录") }, text = {
        Column(Modifier.fillMaxWidth()) {
            OutlinedTextField(path, { path = it }, label = { Text("绝对路径") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                trailingIcon = { IconButton(onClick = { model.browse(path, true) }) { Icon(Icons.Outlined.ArrowForward, "打开路径") } })
            Row {
                TextButton(onClick = { model.browse(state.health?.string("home").orEmpty(), true) }) { Text("主目录") }
                TextButton(onClick = { model.browse("/sdcard", true) }) { Text("共享存储") }
                TextButton(onClick = { model.browse("/storage/emulated/0", true) }) { Text("内部存储") }
                TextButton(onClick = { model.browse(state.browserParent, true) }) { Text("上一级") }
            }
            // /sdcard only exists inside Termux after termux-setup-storage; without
            // that grant the picker could not see any ordinary phone folder.
            TextButton(onClick = grantStorage) {
                Icon(Icons.Outlined.SdCard, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                Text("手机里看不到其他文件夹？授权共享存储")
            }
            if (state.browserLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
            LazyColumn(Modifier.height(260.dp)) {
                items(state.entries.filter { it.directory }, key = { it.path }) { entry -> FileRow(entry) { model.browse(entry.path, true) } }
            }
            TextButton(onClick = { create = true }) { Icon(Icons.Outlined.CreateNewFolder, null); Spacer(Modifier.width(8.dp)); Text("在此新建文件夹") }
        }
    }, confirmButton = { Button(onClick = { model.addWorkspace(path.trim().ifBlank { state.browserPath }); dismiss() }, enabled = path.isNotBlank() || state.browserPath.isNotBlank()) { Text("使用此目录") } },
        dismissButton = { TextButton(onClick = dismiss) { Text("取消") } })
    if (create) AlertDialog(onDismissRequest = { create = false }, title = { Text("新建文件夹") }, text = { OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("文件夹名称") }) },
        confirmButton = { TextButton(onClick = { model.createDirectory(name); create = false }) { Text("创建") } })
}

@Composable
private fun EnvironmentPane(state: PocketState, model: PocketModel, grant: () -> Unit, safe: (() -> Unit) -> Unit) {
    val context = LocalContext.current
    val result by TermuxConnection.lastResult.collectAsStateWithLifecycle()
    // Compare against the real constant. A hardcoded "0.2.0" here made the button
    // read 更新本地服务 forever, even on a current service.
    val needsUpdate = state.health?.string("version") != BRIDGE_VERSION
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Text("本地环境", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Text("DSH 和命令运行在手机上的完整 Termux 中。", color = Color(0xFF64748B))
        Surface(shape = RoundedCornerShape(18.dp), color = Color.White) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (state.connected) "● Termux 已连接" else "○ 完成首次连接", fontWeight = FontWeight.SemiBold, color = if (state.connected) Color(0xFF16835F) else Blue)
                if (!state.connected) {
                    Text("1. 允许本应用在 Termux 中运行命令。\n2. 复制配置命令，在 Termux 中执行一次。\n3. 安装基础环境，然后启动服务。", style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = grant) { Text("授予权限") }
                        OutlinedButton(onClick = {
                            (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Termux 配置", TermuxConnection.setupCommand))
                            safe { TermuxConnection.openApp(context) }
                        }) { Text("复制配置并打开 Termux") }
                    }
                    OutlinedButton(onClick = { safe { TermuxConnection.installBase(context) } }) { Text("安装 Node / Python") }
                }
                Button(onClick = { model.connect() }, enabled = !state.connecting) {
                    if (state.connecting) { CircularProgressIndicator(Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)) }
                    Text(if (state.connecting) "正在连接…" else if (state.connected && needsUpdate) "更新本地服务" else if (state.connected) "刷新状态" else "启动本地服务")
                }
                if (state.connected && needsUpdate) Text("本地服务版本落后，将自动更新。更新会结束终端连接，保留工作区和对话记录。", style = MaterialTheme.typography.bodySmall)
                state.health?.let { health -> Text("${health.string("arch")} · Node ${health.string("node")}\n${health.string("home")}", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
                if (result.isNotBlank()) SelectionContainer { Text(result, style = MaterialTheme.typography.bodySmall, modifier = Modifier.heightIn(max = 160.dp).verticalScroll(rememberScrollState())) }
            }
        }
        Surface(shape = RoundedCornerShape(18.dp), color = Color.White) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("DSH 引擎", fontWeight = FontWeight.SemiBold)
                Text(if (state.health?.string("dsh").isNullOrBlank()) "尚未检测到引擎" else "已发现 DSH · SDK 模式", color = Color(0xFF64748B))
                Text("开发版先使用 DSH 的 sdk-minimal 配置，提供持久 Shell 和会话。模型可通过 Shell 读写代码、运行工具。", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { safe { TermuxConnection.installEngine(context) } }, enabled = state.connected) { Text("安装 / 检查 Android 引擎") }
                OutlinedButton(onClick = { safe { TermuxConnection.diagnose(context) } }) { Text("诊断本地服务连接") }
                OutlinedButton(onClick = { safe { TermuxConnection.openTerminal(context, state.selected?.path ?: TermuxConnection.HOME) } }) { Text("打开原生 Termux 终端") }
            }
        }
        Surface(shape = RoundedCornerShape(18.dp), color = Color.White) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ApiPresetsPane(state, model)
            }
        }
        Text("DSH Pocket · 独立开发项目，与 DeepSeek 官方应用无隶属关系。", style = MaterialTheme.typography.labelSmall, color = Color(0xFF94A3B8))
    }
}

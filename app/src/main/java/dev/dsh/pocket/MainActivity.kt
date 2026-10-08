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
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.Close
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import java.util.Locale

private val Blue = Color(0xFF2563EB)
private val Surface = Color(0xFFF7F8FC)

class MainActivity : ComponentActivity() {
    private val model: PocketModel by viewModels()
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        TermuxConnection.allowed.value = granted
        if (!granted) model.error(tr("请在系统设置 → DSH Pocket → 权限中允许在 Termux 运行命令"))
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Align Java's default locale with this app's effective one: the system
        // language, or the per-app language chosen in Android settings. Lang.tr()
        // reads Locale.getDefault(), so this is what makes the switch follow.
        Locale.setDefault(resources.configuration.locales[0])
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
    val safe: (() -> Unit) -> Unit = { block -> try { block() } catch (e: Exception) { model.error(e.message ?: tr("操作失败")) } }
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
        val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
        val content: @Composable () -> Unit = {
            Scaffold(containerColor = Surface, snackbarHost = { SnackbarHost(snackbar) }, topBar = {
                TopAppBar(title = {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(state.selected?.name ?: "DSH Pocket", fontWeight = FontWeight.SemiBold, maxLines = 1,
                                overflow = TextOverflow.Ellipsis)
                            Text("  " + if (state.connected) tr("● Termux") else tr("○ 等待连接"),
                                style = MaterialTheme.typography.labelSmall, maxLines = 1,
                                color = if (state.connected) Color(0xFF16835F) else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        // The figures are three buttons already, each opening its own
                        // detail. Wrapping them in another chooser only added a tap.
                        ChatStats(state.chat)
                    }
                }, navigationIcon = { if (!wide) IconButton(onClick = { scope.launch { drawer.open() } }) { Icon(Icons.Outlined.Menu, tr("工作区与会话")) } },
                    actions = {
                        if (state.selected != null) IconButton(onClick = { model.newChat(); tab = 0 }) { Icon(Icons.Outlined.AddComment, tr("新建会话")) }
                        IconButton(onClick = { model.connect(); tab = if (state.connected) tab else 3 }) { Icon(Icons.Outlined.Refresh, tr("连接或刷新")) }
                    }, colors = TopAppBarDefaults.topAppBarColors(containerColor = Surface))
            }, bottomBar = {
                // With the keyboard up the bar sits underneath it; keeping it would
                // reserve a blank band exactly where it used to be.
                if (!imeVisible) NavigationBar(containerColor = Color.White, tonalElevation = 0.dp) {
                    val tabs = listOf(tr("对话") to Icons.Outlined.ChatBubbleOutline, tr("文件") to Icons.Outlined.FolderOpen, tr("终端") to Icons.Outlined.Terminal, tr("环境") to Icons.Outlined.Tune)
                    tabs.forEachIndexed { index, (label, icon) ->
                        NavigationBarItem(selected = tab == index, onClick = {
                            tab = index
                            if (index == 1) state.selected?.let { model.browse(it.path) }
                            if (index == 2 && state.selected != null && state.terminalId == null) model.terminal()
                        }, icon = { Icon(icon, label) }, label = { Text(label) })
                    }
                }
            }) { padding ->
                // The keyboard is inset here rather than by Scaffold, and the bar above
                // steps aside while it is open; the two together leave no blank band.
                Box(Modifier.padding(padding).fillMaxSize().imePadding()) {
                    when (tab) {
                        0 -> ChatPane(state, model, onSetup = { tab = 3 }, onWorkspace = addWorkspace)
                        1 -> FilesPane(state, model, addWorkspace) { directory, command -> tab = 2; model.openTerminalAt(directory, command) }
                        2 -> if (state.terminalId != null) key(state.terminalId) {
                            TerminalPane(state.terminalId!!, model.api, model::error, { model.closeTerminal() }, Modifier.fillMaxSize())
                        } else EmptyPanel(Icons.Outlined.Terminal, tr("你的完整终端"), tr("进入项目目录，运行熟悉的命令。"), if (state.selected == null) tr("选择工作区") else tr("打开终端")) {
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
    state.viewer?.let { open ->
        // Full screen: the old dialog capped every file at 520 dp, could not zoom
        // a photo and had nowhere to put an editor toolbar.
        Surface(Modifier.fillMaxSize().statusBarsPadding()) {
            FileViewerScreen(open, model, onClose = model::closeFile, onTerminal = { directory, command ->
                model.closeFile()
                tab = 2
                model.openTerminalAt(directory, command)
            })
        }
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
                Text(tr("你的移动开发空间"), color = Color(0xFF64748B), style = MaterialTheme.typography.labelSmall)
            }
        }
        Spacer(Modifier.height(22.dp))
        Button(onClick = onNew, enabled = state.connected && state.selected != null, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
            Icon(Icons.Outlined.Add, null); Spacer(Modifier.width(8.dp)); Text(tr("新建对话"))
        }
        Row(Modifier.fillMaxWidth().padding(top = 24.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text(tr("工作区"), style = MaterialTheme.typography.labelMedium, color = Color(0xFF64748B))
            IconButton(onClick = onAdd, enabled = state.connected) { Icon(Icons.Outlined.CreateNewFolder, tr("添加工作区"), modifier = Modifier.size(20.dp)) }
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
                            Icon(Icons.Outlined.MoreVert, tr("工作区操作"), modifier = Modifier.size(17.dp), tint = Color(0xFF94A3B8))
                        }
                    }
                }
            }
            item { Text(tr("最近对话"), color = Color(0xFF64748B), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 24.dp, bottom = 8.dp)) }
            items(state.chats, key = { it.string("id") }) { chat ->
                val starred = chat.optBoolean("starred", false)
                Row(Modifier.fillMaxWidth().clickable { onChat(chat.getString("id")) }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (starred) Icons.Outlined.Star else Icons.Outlined.ChatBubbleOutline, null,
                        modifier = Modifier.size(16.dp), tint = if (starred) Color(0xFFE0A800) else Color(0xFF64748B))
                    Column(Modifier.padding(start = 10.dp).weight(1f)) {
                        Text(chat.string("title"), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                        val status = chat.string("status")
                        if (status == "running" || status == "error") Text(
                            if (status == "running") tr("运行中") else tr("出错"), style = MaterialTheme.typography.labelSmall,
                            color = if (status == "running") Blue else MaterialTheme.colorScheme.error)
                    }
                    IconButton(onClick = { chatMenu = chat }, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Outlined.MoreVert, tr("对话操作"), modifier = Modifier.size(16.dp), tint = Color(0xFF94A3B8))
                    }
                }
            }
        }
        HorizontalDivider(color = Color(0xFFE0E6F0))
        TextButton(onClick = onSettings, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Outlined.Tune, null); Spacer(Modifier.width(8.dp)); Text(tr("连接与模型设置")) }
        Text(BuildConfig.VERSION_NAME + " · " + tr("开发预览"), style = MaterialTheme.typography.labelSmall, color = Color(0xFF94A3B8), modifier = Modifier.padding(12.dp))
    }
    chatMenu?.let { chat ->
        DropdownMenu(expanded = true, onDismissRequest = { chatMenu = null }) {
            DropdownMenuItem(text = { Text(if (chat.optBoolean("starred", false)) tr("取消星标") else tr("星标置顶")) },
                leadingIcon = { Icon(if (chat.optBoolean("starred", false)) Icons.Outlined.Star else Icons.Outlined.StarBorder, null) },
                onClick = { chatMenu = null; onStarChat(chat) })
            DropdownMenuItem(text = { Text(tr("删除对话")) }, leadingIcon = { Icon(Icons.Outlined.Delete, null) },
                onClick = { chatMenu = null; chatDelete = chat })
        }
    }
    workspaceMenu?.let { workspace ->
        DropdownMenu(expanded = true, onDismissRequest = { workspaceMenu = null }) {
            DropdownMenuItem(text = { Text(if (workspace.starred) tr("取消星标") else tr("星标置顶")) },
                leadingIcon = { Icon(if (workspace.starred) Icons.Outlined.Star else Icons.Outlined.StarBorder, null) },
                onClick = { workspaceMenu = null; onStarWorkspace(workspace) })
            DropdownMenuItem(text = { Text(tr("从列表移除")) },
                leadingIcon = { Icon(Icons.Outlined.FolderOff, null) },
                onClick = { workspaceMenu = null; workspaceDelete = workspace })
        }
    }
    chatDelete?.let { chat -> AlertDialog(onDismissRequest = { chatDelete = null },
        title = { Text(tr("删除这个对话？")) },
        text = { Text(tr("这条对话的消息和工具记录会一并删除，项目文件不受影响。")) },
        confirmButton = { TextButton(onClick = { chatDelete = null; onDeleteChat(chat) }) { Text(tr("删除"), color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { chatDelete = null }) { Text(tr("取消")) } }) }
    workspaceDelete?.let { workspace -> AlertDialog(onDismissRequest = { workspaceDelete = null },
        title = { Text(tr("从列表移除工作区？")) },
        text = { Text(workspace.path + "\n\n" + tr("只会移除这条记录，磁盘上的项目文件不会被删除。")) },
        confirmButton = { TextButton(onClick = { workspaceDelete = null; onDeleteWorkspace(workspace) }) { Text(tr("移除"), color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { workspaceDelete = null }) { Text(tr("取消")) } }) }
}

@Composable
private fun ChatPane(state: PocketState, model: PocketModel, onSetup: () -> Unit, onWorkspace: () -> Unit) {
    if (!state.connected) { EmptyPanel(Icons.Outlined.Hub, tr("把开发环境带在身边"), tr("连接本机 Termux，在真实项目中对话、修改文件和运行命令。"), tr("配置本地环境"), onSetup); return }
    if (state.selected == null) { EmptyPanel(Icons.Outlined.FolderOpen, tr("从一个文件夹开始"), tr("选择已有项目，或创建一个新的目录。文件保留在原位置。"), tr("选择工作区"), onWorkspace); return }
    if (state.chat == null) { EmptyPanel(Icons.Outlined.AutoAwesome, tr("今天想做些什么？"), state.selected.path, tr("在此项目新建对话")) { model.newChat() }; return }
    val chat = state.chat
    val messages = chat.objects("messages")
    val events = chat.objects("events")
    val status = chat.string("status")
    val running = status == "running"
    var input by rememberSaveable(chat.string("id")) { mutableStateOf("") }
    val timeline = remember(chat) { buildTimeline(chat) }
    val lastUser = timeline.lastOrNull { it is Timeline.Bubble && it.role == "user" }?.seq ?: 0
    // Only the turn that is actually running may spin, and a card belongs to this
    // run only if it was produced after the newest request. Taking the last card
    // instead made the previous turn claim to be working from the moment a request
    // was sent until the new turn produced its first card.
    val activeWork = if (running) timeline.lastOrNull { it is Timeline.Work && it.seq > lastUser }?.seq else null
    val chatId = chat.string("id")
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) model.attach(context, uri)
    }
    val listState = rememberLazyListState()
    // Returning to a transcript (a tab switch, a rotation) resumes where it was
    // left; opening a chat for the first time lands on the newest message. This
    // has to be explicit: the list state is recreated with the composition, and
    // it cannot rely on canScrollForward, which is only meaningful after layout.
    LaunchedEffect(chatId) {
        val saved = model.transcriptPosition(chatId)
        if (saved != null) listState.scrollToItem(saved.first, saved.second)
        else if (timeline.isNotEmpty()) listState.scrollToItem(timeline.lastIndex)
    }
    DisposableEffect(chatId) {
        onDispose {
            model.rememberTranscriptPosition(chatId, listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)
        }
    }
    // Sending always brings the newest message into view, even from further up.
    var seenUser by remember(chatId) { mutableStateOf(lastUser) }
    LaunchedEffect(lastUser) {
        if (lastUser != 0 && lastUser != seenUser) {
            seenUser = lastUser
            if (timeline.isNotEmpty()) listState.animateScrollToItem(timeline.lastIndex)
        }
    }
    // Otherwise follow only while the reader is already at the bottom, and never
    // on the first composition, which would undo the restore above.
    var followArmed by remember(chatId) { mutableStateOf(false) }
    LaunchedEffect(timeline.size) {
        if (!followArmed) { followArmed = true; return@LaunchedEffect }
        if (timeline.isNotEmpty() && !listState.canScrollForward) listState.animateScrollToItem(timeline.lastIndex)
    }
    // The keyboard takes height away from the transcript, which would leave the newest
    // message below the fold. Opening it means the reader is about to write, so bring
    // the end of the conversation with it.
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    LaunchedEffect(imeVisible) {
        if (imeVisible && timeline.isNotEmpty()) listState.animateScrollToItem(timeline.lastIndex)
    }
    Column(Modifier.fillMaxSize()) {
        LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(horizontal = 24.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
            if (messages.isEmpty()) item {
                Column(Modifier.fillMaxWidth().padding(top = 56.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Outlined.Code, null, tint = Blue, modifier = Modifier.size(42.dp))
                    Text(tr("从想法，到运行"), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 18.dp))
                    Text(tr("描述任务，DSH 会在当前项目里工作。"), color = Color(0xFF64748B), modifier = Modifier.padding(top = 10.dp))
                }
            }
            items(timeline, key = { it.seq }) { item -> TimelineRow(item, item.seq == activeWork) }
            if (running) item { Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp); Text(tr("DSH 正在工作…"), modifier = Modifier.padding(start = 10.dp), color = Color(0xFF64748B)) } }
            if (chat.string("error").isNotBlank()) item { Text(chat.string("error"), color = MaterialTheme.colorScheme.error) }
        }
        // Every chat is continuable, including one restored from a previous run:
        // the engine keeps its sessions under DSH_HOME, so asking again resumes it.
        Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp).widthIn(max = 1000.dp).fillMaxWidth()) {
            if (state.pendingAttachments.isNotEmpty()) Row(Modifier.padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.pendingAttachments.forEach { file ->
                    InputChip(selected = false, onClick = { model.removeAttachment(file.name) },
                        label = { Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        trailingIcon = { Icon(Icons.Outlined.Close, tr("移除附件"), Modifier.size(16.dp)) })
                }
            }
            OutlinedTextField(value = input, onValueChange = { input = it }, placeholder = { Text(tr("描述你想完成的任务…")) }, modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 6,
                shape = RoundedCornerShape(18.dp),
                leadingIcon = { IconButton(onClick = { picker.launch(arrayOf("*/*")) }) { Icon(Icons.Outlined.AttachFile, tr("添加附件"), tint = Color(0xFF64748B)) } },
                trailingIcon = {
                    if (running) IconButton(onClick = model::stop) { Icon(Icons.Outlined.StopCircle, tr("停止任务"), tint = MaterialTheme.colorScheme.error) }
                    else IconButton(enabled = input.isNotBlank(), onClick = { model.send(input); input = "" }) { Icon(Icons.AutoMirrored.Outlined.Send, tr("发送"), tint = if (input.isNotBlank()) Blue else Color.Gray) }
                })
            ChatModelControls(state, model, running)
        }
    }
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
private fun FilesPane(state: PocketState, model: PocketModel, onWorkspace: () -> Unit,
                      onTerminal: (String, String?) -> Unit) {
    if (state.selected == null) { EmptyPanel(Icons.Outlined.FolderOpen, tr("项目文件"), tr("浏览真实文件，查看代码。"), tr("选择工作区"), onWorkspace); return }
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { model.browse(state.browserParent) }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, tr("上一级")) }
            Text(state.browserPath, modifier = Modifier.weight(1f), maxLines = 2, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            IconButton(onClick = { model.browse(state.browserPath) }) { Icon(Icons.Outlined.Refresh, tr("刷新文件")) }
        }
        if (state.browserLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
        LazyColumn(Modifier.fillMaxSize()) {
            itemsIndexed(state.entries, key = { index, entry -> "${entry.path}_$index" }) { _, entry ->
                // Runnable files get their own run button, so the file list can
                // start something without opening the viewer first.
                val command = if (entry.directory) null else runCommandFor(entry.path)
                FileRow(entry,
                    onClick = { if (entry.directory) model.browse(entry.path) else model.openFile(entry) },
                    onRun = command?.let { run -> { onTerminal(entry.path.substringBeforeLast('/'), run) } })
            }
        }
    }
}

@Composable
private fun FileRow(entry: FileEntry, onClick: () -> Unit, onRun: (() -> Unit)? = null) {
    ListItem(headlineContent = { Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(if (entry.directory) tr("文件夹") else humanSize(entry.size), style = MaterialTheme.typography.labelSmall) },
        leadingContent = { Icon(if (entry.directory) Icons.Outlined.Folder else fileIcon(entry.name), null,
            tint = if (entry.directory) Blue else Color(0xFF64748B)) },
        trailingContent = onRun?.let { action -> {
            IconButton(onClick = action) { Icon(Icons.Outlined.PlayArrow, tr("在终端运行"), tint = Blue) }
        } },
        modifier = Modifier.clickable(onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent))
}

@Composable
private fun WorkspaceDialog(state: PocketState, model: PocketModel, dismiss: () -> Unit, grantStorage: () -> Unit) {
    val context = LocalContext.current
    var path by remember(state.browserPath) { mutableStateOf(state.browserPath) }
    var create by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var checking by remember { mutableStateOf(false) }
    var checkResult by remember(path) { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { model.loadShortcuts() }
    AlertDialog(onDismissRequest = dismiss, title = { Text(tr("选择工作区目录")) }, text = {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            OutlinedTextField(path, { path = it }, label = { Text(tr("目录路径（支持 ~/）")) }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                placeholder = { Text("/storage/XXXX-XXXX/projects") },
                trailingIcon = { IconButton(onClick = { model.browse(path.trim(), true) }) { Icon(Icons.Outlined.ArrowForward, tr("打开路径")) } })
            LazyRow {
                items(state.shortcuts, key = { it.path }) { item ->
                    TextButton(onClick = {
                        path = item.path
                        if (item.available) model.browse(item.path, true)
                        else checkResult = item.reason.ifBlank { tr("Termux 暂时无法访问，请授权或重新连接存储后刷新。") }
                    }) { Text(if (item.available) item.label else item.label + tr("（不可访问）")) }
                }
            }
            Row {
                TextButton(onClick = { model.loadShortcuts() }) { Text(tr("刷新存储")) }
                TextButton(enabled = path.isNotBlank() && !checking, onClick = {
                    val target = path.trim()
                    checking = true
                    scope.launch {
                        try {
                            val result = withContext(Dispatchers.IO) { model.api.call("workspace-check", "POST", JSONObject().put("path", target)) }
                            if (path.trim() == target) checkResult = tr("读写验证通过：") + result.getString("path")
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) { if (path.trim() == target) checkResult = e.message ?: tr("验证失败") }
                        finally { checking = false }
                    }
                }) { Text(if (checking) tr("验证中…") else tr("验证读写")) }
            }
            if (checkResult.isNotBlank()) Text(checkResult, style = MaterialTheme.typography.bodySmall)
            Text(tr("可选择 SD 卡 / USB 硬盘目录。权限由 Termux 决定；若盘根目录不可写，可尝试 ~/storage/external-1。"), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { runCatching { TermuxConnection.storageSettings(context) }.onFailure { model.error(it.message ?: tr("无法打开权限设置")) } }) {
                Text(tr("外接盘无法写入？打开 Termux 权限设置"))
            }
            Row {
                TextButton(onClick = { model.browse(state.browserParent, true) },
                    enabled = state.browserParent.isNotBlank() && state.browserParent != state.browserPath) { Text(tr("上一级")) }
                TextButton(onClick = grantStorage) { Text(tr("授权 Termux 存储")) }
            }
            if (state.browserLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (!state.browserLoading && state.entries.isEmpty()) Text(tr("此目录为空或尚未成功打开。"), style = MaterialTheme.typography.bodySmall)
            if (state.browserTruncated) Text(tr("条目过多，仅显示前一部分。"), style = MaterialTheme.typography.bodySmall)
            LazyColumn(Modifier.height(180.dp)) {
                itemsIndexed(state.entries.filter { it.directory }, key = { index, entry -> "${entry.path}_$index" }) { _, entry ->
                    FileRow(entry, onClick = { model.browse(entry.path, true) })
                }
            }
            TextButton(onClick = { create = true }, enabled = path.isNotBlank()) {
                Icon(Icons.Outlined.CreateNewFolder, null); Spacer(Modifier.width(8.dp)); Text(tr("在此新建文件夹"))
            }
        }
    }, confirmButton = { Button(onClick = {
        saving = true
        scope.launch {
            try { model.addWorkspace(path.trim().ifBlank { state.browserPath }, dismiss).join() }
            finally { saving = false }
        }
    }, enabled = !saving && !checking && (path.isNotBlank() || state.browserPath.isNotBlank())) { Text(if (saving) tr("正在验证…") else tr("使用此目录")) } },
        dismissButton = { TextButton(onClick = dismiss) { Text(tr("取消")) } })
    if (create) AlertDialog(onDismissRequest = { create = false }, title = { Text(tr("新建文件夹")) }, text = {
        Column { Text(tr("父目录：$path"), style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(name, { name = it }, singleLine = true, label = { Text(tr("文件夹名称")) }) }
    }, confirmButton = { TextButton(onClick = { model.createDirectory(name, path.trim()) { create = false } }, enabled = name.isNotBlank()) { Text(tr("创建")) } },
        dismissButton = { TextButton(onClick = { create = false }) { Text(tr("取消")) } })
}

@Composable
private fun EnvironmentPane(state: PocketState, model: PocketModel, grant: () -> Unit, safe: (() -> Unit) -> Unit) {
    val context = LocalContext.current
    val result by TermuxConnection.lastResult.collectAsStateWithLifecycle()
    // Compare against the real constant. A hardcoded "0.2.0" here made the button
    // read 更新本地服务 forever, even on a current service.
    val needsUpdate = state.health?.string("version") != BRIDGE_VERSION
    var onboarding by remember { mutableStateOf(false) }
    var permission by remember { mutableStateOf(false) }
    // Termux owns its prompt, so its switch cannot be typed for the user; this command
    // is the whole of that instruction. It ends with a reload because the property is
    // only read when Termux loads its settings.
    val permissionCommand = TermuxConnection.setupCommand
    val permitted by TermuxConnection.allowed.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { TermuxConnection.refreshPermission(context) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Text(tr("本地环境"), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Text(tr("DSH 和命令运行在手机上的完整 Termux 中。"), color = Color(0xFF64748B))
        Surface(shape = RoundedCornerShape(18.dp), color = Color.White) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (state.connected) tr("● Termux 已连接") else tr("○ 完成首次连接"), fontWeight = FontWeight.SemiBold, color = if (state.connected) Color(0xFF16835F) else Blue)
                if (!state.connected) {
                    // Two permissions gate this, and they are different things: Android
                    // must allow this app to run commands in Termux, and Termux must have
                    // its own switch on. Showing only one of them is how a first run
                    // ended up with nothing left to press.
                    Text(tr("首次连接会安装 Node/Python、写入配置并启动本地服务。"), style = MaterialTheme.typography.bodyMedium)
                    if (permitted) {
                        Text(tr("✓ 已允许本应用在 Termux 中运行命令"), style = MaterialTheme.typography.bodySmall, color = Color(0xFF16835F))
                    } else {
                        Button(onClick = grant) { Text(tr("① 授予 Termux 权限")) }
                        Text(tr("系统会弹出授权框；它和 Termux 内部的开关是两件事，两个都要。"), style = MaterialTheme.typography.bodySmall)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        // This one acts. Termux refusing the command is the failure a
                        // first-time user hits, so it opens the instructions with the reason.
                        Button(enabled = permitted, onClick = {
                            runCatching { TermuxConnection.firstRun(context, model.token) }
                                .onSuccess { safe { TermuxConnection.openApp(context) } }
                                .onFailure { onboarding = true; permission = true }
                        }) { Text(tr("② 一键完成首次连接")) }
                        OutlinedButton(onClick = { onboarding = true; permission = true }) { Text(tr("Termux 里的开关")) }
                    }
                }
                Button(onClick = {
                    // Starting the local service means Termux has to be up; on a cold
                    // device a background command would run unseen.
                    if (!state.connected) safe { TermuxConnection.openApp(context) }
                    model.connect()
                }, enabled = !state.connecting) {
                    if (state.connecting) { CircularProgressIndicator(Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)) }
                    Text(if (state.connecting) tr("正在连接…") else if (state.connected && needsUpdate) tr("更新本地服务") else if (state.connected) tr("刷新状态") else tr("启动本地服务"))
                }
                if (state.connected && needsUpdate) Text(tr("本地服务版本落后，将自动更新。更新会结束终端连接，保留工作区和对话记录。"), style = MaterialTheme.typography.bodySmall)
                var details by remember { mutableStateOf(false) }
                TextButton(onClick = { details = !details }) { Text(tr("运行详情")) }
                if (details && result.isNotBlank()) Text(result, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.heightIn(max = 160.dp).verticalScroll(rememberScrollState()))
            }
        }
        if (onboarding) AlertDialog(onDismissRequest = { onboarding = false; permission = false },
            title = { Text(tr("首次连接")) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(tr("Termux 默认不允许其他应用运行命令，需要打开一次这个开关："))
                Text(permissionCommand, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                Text(tr("把上面这行在 Termux 里执行一次，然后回到这里按「已设置，开始连接」——安装 Node/Python、写入配置、启动服务都会自动完成。"))
            } },
            confirmButton = { TextButton(onClick = {
                (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText(tr("Termux 配置"), permissionCommand))
                safe { TermuxConnection.openApp(context) }
            }) { Text(tr("复制命令并打开 Termux")) } },
            dismissButton = { TextButton(onClick = {
                onboarding = false; permission = false
                // A silent failure here is what made this look like nothing happened.
                runCatching { TermuxConnection.firstRun(context, model.token) }
                    .onSuccess { safe { TermuxConnection.openApp(context) } }
                    .onFailure { model.error(it.message ?: tr("Termux 没有接受命令")) }
            }) { Text(tr("已设置，开始连接")) } })
        Surface(shape = RoundedCornerShape(18.dp), color = Color.White) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(tr("DSH 引擎"), fontWeight = FontWeight.SemiBold)
                Text(if (state.health?.string("dsh").isNullOrBlank()) tr("尚未检测到引擎") else tr("已发现 DSH · SDK 模式"), color = Color(0xFF64748B))
                var maintenance by remember { mutableStateOf(false) }
                if (state.health?.string("dsh").isNullOrBlank()) {
                    Button(onClick = { safe { TermuxConnection.installEngine(context) } }, enabled = state.connected) { Text(tr("安装引擎")) }
                } else {
                    TextButton(onClick = { maintenance = !maintenance }) { Text(tr("维护选项")) }
                    if (maintenance) {
                        OutlinedButton(onClick = { safe { TermuxConnection.installEngine(context) } }, enabled = state.connected) { Text(tr("更新引擎")) }
                        TextButton(onClick = { safe { TermuxConnection.openTerminal(context, state.selected?.path ?: TermuxConnection.HOME) } }) { Text(tr("打开原生 Termux 终端")) }
                    }
                }
            }
        }
        Surface(shape = RoundedCornerShape(18.dp), color = Color.White) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                PhoneControlPane()
                HorizontalDivider()
                ApiPresetsPane(state, model)
            }
        }
        Text(tr("DSH Pocket · 独立开发项目，与 DeepSeek 官方应用无隶属关系。"), style = MaterialTheme.typography.labelSmall, color = Color(0xFF94A3B8))
    }
}

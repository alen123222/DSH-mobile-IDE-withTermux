package dev.dsh.pocket

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.ZoomIn
import androidx.compose.material.icons.outlined.ZoomOut
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.zip.ZipFile

/** One file open in the viewer. Text rides in memory; everything else is cached on disk. */
data class OpenFile(
    val path: String, val name: String, val size: Long, val kind: String, val mime: String,
    val language: String = "plain", val encoding: String = "", val mtime: Long = 0,
    val text: String = "", val lines: Int = 0, val cache: File? = null,
    val editable: Boolean = false, val note: String = "",
) {
    val isText get() = kind == "text"
    /** The bridge reports GBK; Node has no GBK encoder, so saving converts to UTF-8. */
    val isGbk get() = encoding == "gbk"

    companion object {
        fun from(json: org.json.JSONObject, cache: File?): OpenFile {
            val kind = json.string("kind", "binary")
            val writable = json.optBoolean("writable", true)
            return OpenFile(
                path = json.getString("path"),
                name = json.string("name", json.getString("path").substringAfterLast('/')),
                size = json.optLong("size"), kind = kind,
                mime = json.string("mime", "application/octet-stream"),
                language = json.string("language", "plain"), encoding = json.string("encoding"),
                mtime = json.optLong("mtime"), text = json.string("text"), lines = json.optInt("lines"),
                cache = cache, editable = kind == "text" && writable, note = json.string("note"),
            )
        }
    }
}

fun fileIcon(name: String): ImageVector {
    val ext = name.substringAfterLast('.', "").lowercase()
    return when {
        ext in setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "heic", "heif", "avif", "ico") -> Icons.Outlined.Image
        ext == "pdf" -> Icons.Outlined.PictureAsPdf
        ext in setOf("zip", "jar", "apk", "aar", "whl", "epub", "tar", "gz", "xz", "bz2", "7z", "rar") -> Icons.Outlined.FolderZip
        ext in setOf("c", "h", "cpp", "cc", "cxx", "hpp", "py", "java", "kt", "kts", "js", "mjs", "cjs",
            "ts", "tsx", "jsx", "rs", "go", "rb", "php", "swift", "cs", "dart", "lua", "sh", "bash",
            "sql", "json", "xml", "yml", "yaml", "toml", "css", "html", "gradle") -> Icons.Outlined.Code
        else -> Icons.Outlined.Description
    }
}

fun humanSize(bytes: Long): String = when {
    bytes < 0 -> ""
    bytes < 1024 -> bytes.toString() + " B"
    bytes < 1024 * 1024 -> (bytes / 1024).toString() + " KB"
    bytes < 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
    else -> String.format(Locale.US, "%.2f GB", bytes / 1024.0 / 1024.0 / 1024.0)
}

/**
 * Hand a copy to whichever app owns this file type. The workspace lives in
 * Termux's private home, so other apps can never open it directly; FileProvider
 * exposes only the copies this app puts in its own cache.
 */
fun openWithAnotherApp(context: Context, name: String, mime: String, source: File) =
    shareWithAnotherApp(context, name, mime) { target -> source.copyTo(target, overwrite = true) }

fun openWithAnotherApp(context: Context, name: String, mime: String, bytes: ByteArray) =
    shareWithAnotherApp(context, name, mime) { target -> target.writeBytes(bytes) }

private fun shareWithAnotherApp(context: Context, name: String, mime: String, write: (File) -> Unit) {
    val directory = File(context.cacheDir, "shared").apply { mkdirs() }
    val target = File(directory, name.ifBlank { "file" })
    write(target)
    val uri = FileProvider.getUriForFile(context, context.packageName + ".files", target)
    val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime.ifBlank { "*/*" })
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(intent, tr("打开方式")))
}

private fun decodeImage(file: File): ImageBitmap? = runCatching {
    if (Build.VERSION.SDK_INT >= 28) {
        val source = ImageDecoder.createSource(file)
        val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            // Software allocation keeps the bitmap readable and out of the
            // hardware pool; 2400 px is more than a tablet screen needs.
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val longest = maxOf(info.size.width, info.size.height)
            if (longest > 2400) decoder.setTargetSampleSize((longest + 2399) / 2400)
        }
        bitmap.asImageBitmap()
    } else {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        var sample = 1
        while (longest / sample > 2400) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        BitmapFactory.decodeFile(file.path, options)?.asImageBitmap()
    }
}.getOrNull()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileViewerScreen(open: OpenFile, model: PocketModel, onClose: () -> Unit,
                     onTerminal: (String, String?) -> Unit) {
    val context = LocalContext.current
    val syntax = remember(open.language, open.name) { syntaxFor(open.language, open.name) }
    val command = remember(open.path) { runCommandFor(open.path) }
    var value by remember(open.path) { mutableStateOf(TextFieldValue(open.text)) }
    var editing by remember(open.path) { mutableStateOf(open.editable && !open.isGbk) }
    var saving by remember(open.path) { mutableStateOf(false) }
    var conflict by remember(open.path) { mutableStateOf(false) }
    var confirmClose by remember(open.path) { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var showInfo by remember { mutableStateOf(false) }
    var searching by remember(open.path) { mutableStateOf(false) }
    var query by remember(open.path) { mutableStateOf("") }
    var matchIndex by remember(open.path) { mutableStateOf(0) }
    val dirty = open.isText && value.text != open.text
    val matches = remember(value.text, query) { if (query.isBlank()) emptyList() else findMatches(value.text, query) }
    val current = if (matches.isEmpty()) -1 else matchIndex.coerceIn(0, matches.size - 1)
    fun jump(delta: Int) {
        if (matches.isEmpty()) return
        val size = matches.size
        matchIndex = ((current + delta) % size + size) % size
        val at = matches[matchIndex].first
        value = value.copy(selection = androidx.compose.ui.text.TextRange(at, at + query.length))
    }

    fun save(force: Boolean) {
        saving = true
        model.saveFile(open.path, value.text, open.mtime, force) { ok, message ->
            saving = false
            if (ok) conflict = false
            else if (message.contains(tr("已被外部修改"))) conflict = true
            else model.error(message)
        }
    }
    fun leave() { if (dirty) confirmClose = true else onClose() }

    BackHandler { leave() }

    Scaffold(containerColor = Color.White, topBar = {
        TopAppBar(title = {
            Column {
                Text(open.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle(open, value.text, syntax), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }, navigationIcon = { IconButton(onClick = { leave() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, tr("返回")) } },
            actions = {
                if (open.isText) IconButton(onClick = { save(false) }, enabled = dirty && editing && !saving) {
                    Icon(Icons.Outlined.Save, tr("保存"))
                }
                // Run: jump to the terminal in this file's folder and start it.
                if (open.isText && command != null) IconButton(onClick = {
                    onTerminal(open.path.substringBeforeLast('/'), command)
                }) { Icon(Icons.Outlined.PlayArrow, tr("在终端运行")) }
                IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, tr("更多")) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(tr("用其他应用打开")) }, leadingIcon = { Icon(Icons.AutoMirrored.Outlined.OpenInNew, null) },
                        onClick = {
                            menu = false
                            runCatching {
                                if (open.isText) openWithAnotherApp(context, open.name, open.mime, value.text.toByteArray())
                                else open.cache?.let { openWithAnotherApp(context, open.name, open.mime, it) }
                                    ?: openWithAnotherApp(context, open.name, open.mime, ByteArray(0))
                            }.onFailure { model.error(it.message ?: tr("无法打开")) }
                        })
                    DropdownMenuItem(text = { Text(tr("在终端打开")) }, leadingIcon = { Icon(Icons.Outlined.Terminal, null) },
                        onClick = { menu = false; onTerminal(open.path.substringBeforeLast('/'), null) })
                    if (open.isText) DropdownMenuItem(text = { Text(tr("复制全文")) }, leadingIcon = { Icon(Icons.Outlined.ContentCopy, null) },
                        onClick = { menu = false; copy(context, tr("文件内容"), value.text) })
                    if (open.isText) DropdownMenuItem(text = { Text(tr("查找")) }, leadingIcon = { Icon(Icons.Outlined.Search, null) },
                        onClick = { menu = false; searching = true })
                    DropdownMenuItem(text = { Text(tr("文件信息")) }, leadingIcon = { Icon(Icons.Outlined.Info, null) },
                        onClick = { menu = false; showInfo = true })
                }
            }, colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.White))
    }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().imePadding()) {
            if (open.isText && searching) SearchBar(query, { query = it }, if (matches.isEmpty()) 0 else current + 1,
                matches.size, { jump(-1) }, { jump(1) }, { searching = false; query = "" })
            Box(Modifier.weight(1f)) {
                when {
                    open.isText -> CodeBody(open, value, { value = it }, editing, syntax, matches, current)
                    open.kind == "image" -> ImageBody(open)
                    open.kind == "pdf" -> PdfBody(open)
                    open.kind == "archive" -> ZipBody(open, model)
                    else -> HexBody(open)
                }
            }
        }
    }

    if (showInfo) AlertDialog(onDismissRequest = { showInfo = false }, title = { Text(open.name) },
        text = {
            Column {
                InfoRow(tr("路径"), open.path)
                InfoRow(tr("大小"), humanSize(open.size) + (if (open.lines > 0) " · " + open.lines + tr(" 行") else ""))
                InfoRow(tr("类型"), open.kind + (if (open.mime.isNotBlank()) " · " + open.mime else ""))
                if (open.encoding.isNotBlank()) InfoRow(tr("编码"), open.encoding)
                if (open.language.isNotBlank() && open.language != "plain") InfoRow(tr("语言"), open.language)
                InfoRow(tr("修改时间"), if (open.mtime > 0)
                    java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(open.mtime))
                    else "—")
                if (open.note.isNotBlank()) InfoRow(tr("说明"), open.note)
            }
        },
        confirmButton = { TextButton(onClick = { showInfo = false }) { Text(tr("关闭")) } })
    if (open.isText && open.isGbk && !editing && value.text.isNotEmpty()) {
        // GBK has no encoder in Node, so an edit has to become UTF-8. Say so
        // instead of silently writing bytes the file never held.
        AlertDialog(onDismissRequest = { }, title = { Text(tr("GBK 编码")) },
            text = { Text(tr("此文件是 GBK 编码，已按 GBK 正确显示。Node 无法写回 GBK：编辑后保存会将文件转为 UTF-8（中文内容不变，其他程序仍可读）。")) },
            confirmButton = { TextButton(onClick = { editing = true }) { Text(tr("转为 UTF-8 编辑")) } },
            dismissButton = { TextButton(onClick = onClose) { Text(tr("只读查看")) } })
    }
    if (conflict) AlertDialog(onDismissRequest = { conflict = false }, title = { Text(tr("文件已被外部修改")) },
        text = { Text(tr("这个文件在别处（很可能是终端里的 DSH）被改过。覆盖会丢弃那些改动。")) },
        confirmButton = { TextButton(onClick = { save(true) }) { Text(tr("仍然覆盖")) } },
        dismissButton = { TextButton(onClick = { conflict = false }) { Text(tr("取消")) } })
    if (confirmClose) AlertDialog(onDismissRequest = { confirmClose = false }, title = { Text(tr("尚未保存")) },
        text = { Text(tr("离开会丢弃这次编辑。")) },
        confirmButton = { TextButton(onClick = { confirmClose = false; onClose() }) { Text(tr("放弃改动")) } },
        dismissButton = { TextButton(onClick = { confirmClose = false }) { Text(tr("继续编辑")) } })
}

/**
 * The shell command that runs this file, or null when we have no business
 * guessing. Interpreted languages run directly; compiled ones are built into a
 * sibling artefact first, which fails loudly in the terminal when no toolchain
 * is installed instead of silently doing nothing.
 */
fun runCommandFor(path: String): String? {
    val name = path.substringAfterLast('/')
    val base = name.substringBeforeLast('.', name)
    val quoted = shellQuote(path)
    val output = shellQuote(base + ".out")
    return when (name.substringAfterLast('.', "").lowercase()) {
        "py", "pyw" -> "python3 " + quoted
        "sh", "bash" -> "bash " + quoted
        "js", "mjs", "cjs" -> "node " + quoted
        "rb" -> "ruby " + quoted
        "php" -> "php " + quoted
        "lua" -> "lua " + quoted
        "pl" -> "perl " + quoted
        "ts" -> "npx --yes tsx " + quoted
        "java" -> "java " + quoted
        "kt", "kts" -> "kotlinc " + quoted + " -include-runtime -d " + shellQuote(base + ".jar") + " && java -jar " + shellQuote(base + ".jar")
        "c" -> "cc " + quoted + " -o " + output + " && " + output
        "cpp", "cc", "cxx" -> "c++ " + quoted + " -o " + output + " && " + output
        "go" -> "go run " + quoted
        "rs" -> "rustc " + quoted + " -o " + output + " && " + output
        "swift" -> "swift " + quoted
        "dart" -> "dart run " + quoted
        else -> null
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Column(Modifier.padding(bottom = 10.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = Color(0xFF94A3B8))
        Text(value, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
    }
}

private fun subtitle(open: OpenFile, text: String, syntax: SyntaxLang): String {
    val parts = mutableListOf<String>()
    parts.add(humanSize(open.size))
    if (open.kind.isNotBlank()) parts.add(open.kind)
    if (syntax != SyntaxLang.PLAIN) parts.add(syntax.name.lowercase())
    if (open.encoding.isNotBlank()) parts.add(open.encoding)
    if (open.isText) parts.add((text.count { it == '\n' } + 1).toString() + tr(" 行"))
    return parts.joinToString(" · ")
}

private fun copy(context: Context, label: String, value: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    clipboard.setPrimaryClip(android.content.ClipData.newPlainText(label, value))
}

@Composable
private fun CodeBody(open: OpenFile, value: TextFieldValue, onChange: (TextFieldValue) -> Unit,
                     editing: Boolean, syntax: SyntaxLang, matches: List<IntRange> = emptyList(), current: Int = -1) {
    val highlighted = remember(value.text, syntax, matches, current) { highlight(value.text, syntax, matches, current) }
    val transformation = remember(highlighted) { object : VisualTransformation {
        override fun filter(text: AnnotatedString): TransformedText =
            TransformedText(highlighted, OffsetMapping.Identity)
    } }
    Surface(color = Color(0xFFFBFCFE), modifier = Modifier.fillMaxSize()) {
        BasicTextField(value = value, onValueChange = onChange, readOnly = !editing,
            textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = SyntaxColors.plain,
                lineHeight = 19.sp),
            visualTransformation = transformation,
            modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 10.dp))
    }
}

@Composable
private fun ImageBody(open: OpenFile) {
    val file = open.cache
    val bitmap by produceState<ImageBitmap?>(null, open.path) {
        value = if (file == null) null else withContext(Dispatchers.IO) { decodeImage(file) }
    }
    var scale by remember(open.path) { mutableFloatStateOf(1f) }
    var offset by remember(open.path) { mutableStateOf(Offset.Zero) }
    Box(Modifier.fillMaxSize().background(Color(0xFF0B1220)).clipToBounds()
        .pointerInput(open.path) {
            detectTransformGestures { _, pan, zoom, _ ->
                scale = (scale * zoom).coerceIn(0.5f, 10f)
                offset += pan
            }
        }, contentAlignment = Alignment.Center) {
        val current = bitmap
        if (current == null) CircularProgressIndicator()
        else androidx.compose.foundation.Image(current, contentDescription = open.name, contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().graphicsLayer(
                scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y))
    }
}

@Composable
private fun PdfBody(open: OpenFile) {
    val file = open.cache
    val renderer = remember(open.path) {
        if (file == null) null else runCatching {
            PdfRenderer(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY))
        }.getOrNull()
    }
    DisposableEffect(renderer) { onDispose { runCatching { renderer?.close() } } }
    if (renderer == null) { NotViewable(open, tr("无法解析这个 PDF（可能已加密）")); return }
    val pages = runCatching { renderer.pageCount }.getOrDefault(0)
    if (pages <= 0) { NotViewable(open, tr("这个 PDF 没有可显示的页面")); return }
    val pager = rememberPagerState(pageCount = { pages })
    val mutex = remember { Mutex() }
    var scale by remember(open.path) { mutableFloatStateOf(1f) }
    var offset by remember(open.path) { mutableStateOf(Offset.Zero) }
    Column(Modifier.fillMaxSize().background(Color(0xFFEEF1F6))) {
        // Pinch to zoom. Once magnified the pager stops taking horizontal drags,
        // otherwise panning a page would flip to the next one instead.
        HorizontalPager(state = pager, userScrollEnabled = scale <= 1.01f, modifier = Modifier.weight(1f)) { index ->
            var bitmap by remember(index) { mutableStateOf<ImageBitmap?>(null) }
            androidx.compose.runtime.LaunchedEffect(index) {
                bitmap = withContext(Dispatchers.IO) {
                    mutex.withLock {
                        runCatching {
                            val page = renderer.openPage(index)
                            val image = Bitmap.createBitmap(page.width * 2, page.height * 2, Bitmap.Config.ARGB_8888)
                            image.eraseColor(android.graphics.Color.WHITE)
                            page.render(image, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            page.close()
                            image.asImageBitmap()
                        }.getOrNull()
                    }
                }
            }
            Box(Modifier.fillMaxSize().clipToBounds()
                .pointerInput(open.path, index) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(0.5f, 8f)
                        offset = if (scale <= 1.01f) Offset.Zero else offset + pan
                    }
                }, contentAlignment = Alignment.Center) {
                val page = bitmap
                if (page == null) CircularProgressIndicator()
                else androidx.compose.foundation.Image(page, contentDescription = tr("第 ") + (index + 1) + tr(" 页"),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().graphicsLayer(
                        scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y))
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text((pager.currentPage + 1).toString() + " / " + pages, style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { scale = 1f; offset = Offset.Zero }) { Text((scale * 100).toInt().toString() + "%") }
            IconButton(onClick = { scale = (scale / 1.25f).coerceIn(0.5f, 8f); if (scale <= 1.01f) offset = Offset.Zero }) {
                Icon(Icons.Outlined.ZoomOut, tr("缩小"))
            }
            IconButton(onClick = { scale = (scale * 1.25f).coerceIn(0.5f, 8f) }) { Icon(Icons.Outlined.ZoomIn, tr("放大")) }
        }
    }
}

@Composable
private fun SearchBar(query: String, onChange: (String) -> Unit, index: Int, total: Int,
                      onPrev: () -> Unit, onNext: () -> Unit, onClose: () -> Unit) {
    Surface(color = Color(0xFFF1F5F9)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(query, onChange, singleLine = true, modifier = Modifier.weight(1f),
                placeholder = { Text(tr("查找文本")) },
                textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp))
            Text(" " + index + " / " + total + " ", style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 6.dp))
            IconButton(onClick = onPrev, enabled = total > 0) { Icon(Icons.Outlined.KeyboardArrowUp, tr("上一个")) }
            IconButton(onClick = onNext, enabled = total > 0) { Icon(Icons.Outlined.KeyboardArrowDown, tr("下一个")) }
            IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, tr("关闭")) }
        }
    }
}

/** Hex dump of the first 64 KB. Enough to identify a file, cheap on memory. */
@Composable
private fun HexBody(open: OpenFile) {
    val rows by produceState<List<String>?>(null, open.path) {
        value = withContext(Dispatchers.IO) {
            val file = open.cache ?: return@withContext null
            runCatching {
                val limit = 64 * 1024
                val buffer = ByteArray(limit)
                val read = file.inputStream().use { input ->
                    var total = 0
                    while (total < limit) {
                        val count = input.read(buffer, total, limit - total)
                        if (count < 0) break
                        total += count
                    }
                    total
                }
                val lines = ArrayList<String>(read / 16 + 1)
                var offset = 0
                while (offset < read) {
                    val hex = StringBuilder(48)
                    val ascii = StringBuilder(16)
                    for (i in 0 until 16) {
                        val at = offset + i
                        if (at < read) {
                            val byte = buffer[at].toInt() and 0xff
                            hex.append(String.format(java.util.Locale.US, "%02x ", byte))
                            ascii.append(if (byte in 32..126) byte.toChar() else '.')
                        } else {
                            hex.append("   ")
                        }
                    }
                    lines.add(String.format(java.util.Locale.US, "%08x  %s %s", offset, hex.toString().trimEnd(), ascii))
                    offset += 16
                }
                lines
            }.getOrNull()
        }
    }
    val list = rows
    if (list == null) { NotViewable(open); return }
    Column(Modifier.fillMaxSize()) {
        Text(tr("十六进制") + " · " + tr("显示前 ") + humanSize(list.size * 16L) +
            (if (open.size > 64 * 1024) tr("（文件更大，仅显示开头）") else ""),
            style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(16.dp))
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            items(list) { row ->
                Text(row, fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                    maxLines = 1, modifier = Modifier.padding(horizontal = 16.dp, vertical = 1.dp))
            }
        }
    }
}

@Composable
private fun ZipBody(open: OpenFile, model: PocketModel) {
    val file = open.cache
    val entries by produceState<List<Triple<String, Long, Long>>?>(null, open.path) {
        value = if (file == null) null else withContext(Dispatchers.IO) {
            runCatching {
                ZipFile(file).use { zip ->
                    zip.entries().asSequence().map { Triple(it.name, it.size, it.compressedSize) }
                        .sortedBy { it.first }.toList()
                }
            }.getOrNull()
        }
    }
    val list = entries
    if (list == null) { NotViewable(open, tr("无法读取这个压缩包")); return }
    var working by remember(open.path) { mutableStateOf(false) }
    var failure by remember(open.path) { mutableStateOf("") }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(tr("共 ") + list.size + tr(" 项 · ") + humanSize(open.size) + tr("（压缩后）"),
                style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
            if (working) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            else TextButton(onClick = {
                working = true
                model.extractArchive(open.path) { ok, message -> working = false; if (!ok) failure = message }
            }) {
                Icon(Icons.Outlined.Unarchive, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp)); Text(tr("解压到此处"))
            }
        }
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            items(list) { (name, size, _) ->
                ListItem(headlineContent = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium) },
                    supportingContent = { Text(humanSize(size), style = MaterialTheme.typography.labelSmall) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent))
            }
        }
    }
    if (failure.isNotBlank()) AlertDialog(onDismissRequest = { failure = "" }, title = { Text(tr("解压失败")) },
        text = { Text(failure) }, confirmButton = { TextButton(onClick = { failure = "" }) { Text(tr("关闭")) } })
}

@Composable
private fun NotViewable(open: OpenFile, reason: String = "") {
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Outlined.Description, null, tint = Color(0xFF94A3B8), modifier = Modifier.size(48.dp))
        Text(open.name, modifier = Modifier.padding(top = 16.dp), fontWeight = FontWeight.SemiBold, maxLines = 2,
            overflow = TextOverflow.Ellipsis)
        Text(reason.ifBlank { open.note.ifBlank { tr("这个格式不能在内置查看器里显示。") } },
            color = Color(0xFF64748B), style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 10.dp))
        Text(tr("点右上角「更多」→「用其他应用打开」"), color = Color(0xFF64748B),
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
        Spacer(Modifier.height(12.dp))
        Text(humanSize(open.size) + " · " + open.mime, style = MaterialTheme.typography.labelSmall,
            color = Color(0xFF94A3B8))
    }
}

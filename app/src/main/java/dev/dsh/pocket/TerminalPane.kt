package dev.dsh.pocket

import android.annotation.SuppressLint
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayInputStream

class TerminalCallbacks(
    val messages: Channel<JSONObject>, val onReady: () -> Unit,
) {
    @JavascriptInterface fun input(data: String) {
        messages.trySend(JSONObject().put("type", "input").put("data", Base64.encodeToString(data.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)))
    }
    @JavascriptInterface fun resize(rows: Int, cols: Int) {
        if (rows !in 2..500 || cols !in 2..500) return
        messages.trySend(JSONObject().put("type", "resize").put("rows", rows).put("cols", cols))
    }
    @JavascriptInterface fun ready() = onReady()
}

// Keep the concrete callback type here so Android Lint can resolve the annotated
// methods without following Compose remember's generic return type.
private fun WebView.attachTerminalCallbacks(callbacks: TerminalCallbacks) {
    addJavascriptInterface(callbacks, "PocketTerminal")
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun TerminalPane(id: String, api: BridgeApi, onError: (String) -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val messages = remember(id) { Channel<JSONObject>(Channel.UNLIMITED) }
    var webView by remember(id) { mutableStateOf<WebView?>(null) }
    var ready by remember(id) { mutableStateOf(false) }
    var exited by remember(id) { mutableStateOf(false) }
    val callbacks: TerminalCallbacks = remember(id) { TerminalCallbacks(messages) { scope.launch { ready = true } } }
    LaunchedEffect(id) {
        for (message in messages) {
            try { withContext(Dispatchers.IO) { api.call("terminals/$id", "POST", message) } }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { onError(e.message ?: tr("终端输入失败")) }
        }
    }
    LaunchedEffect(id, ready) {
        if (!ready) return@LaunchedEffect
        var cursor = 0L
        while (true) {
            try {
                val result = withContext(Dispatchers.IO) { api.call("terminals/$id", query = mapOf("after" to cursor.toString())) }
                if (result.optBoolean("gap")) webView?.evaluateJavascript("window.terminalNotice('" + tr("部分旧输出已滚出缓存；可按 Ctrl+L 重绘") + "')", null)
                for (chunk in result.objects("chunks")) {
                    webView?.evaluateJavascript("window.receiveBase64(${JSONObject.quote(chunk.getString("data"))})", null)
                }
                cursor = result.getLong("cursor")
                if (!result.optBoolean("running")) {
                    exited = true
                    webView?.evaluateJavascript("window.terminalNotice('" + tr("终端已退出") + "')", null)
                    break
                }
                delay(200)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { onError(e.message ?: tr("终端连接已断开")); break }
        }
    }
    DisposableEffect(id) {
        onDispose { messages.close(); webView?.removeJavascriptInterface("PocketTerminal"); webView?.destroy() }
    }
    Column(modifier.background(Color(0xFF111827))) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("BASH  ·  TERMUX", style = MaterialTheme.typography.labelMedium, color = Color(0xFF94A3B8), modifier = Modifier.padding(top = 14.dp))
            TextButton(onClick = onClose) { Text(if (exited) tr("重新打开") else tr("结束终端"), color = Color(0xFFCBD5E1)) }
        }
        AndroidView(modifier = Modifier.weight(1f).fillMaxWidth(), factory = { context ->
            WebView(context).apply {
                WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
                isFocusable = true
                isFocusableInTouchMode = true
                setBackgroundColor(android.graphics.Color.rgb(17, 24, 39))
                settings.javaScriptEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.domStorageEnabled = false
                settings.blockNetworkLoads = true
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?) = true
                    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse {
                        val uri = request?.url
                        val allowed = setOf("index.html", "xterm.js", "xterm.css", "addon-fit.js")
                        val file = uri?.lastPathSegment
                        if (uri?.scheme == "https" && uri.host == "pocket.local" && file in allowed) {
                            val mime = when { file!!.endsWith(".js") -> "application/javascript"; file.endsWith(".css") -> "text/css"; else -> "text/html" }
                            return WebResourceResponse(mime, "UTF-8", context.assets.open("terminal/$file"))
                        }
                        return WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))
                    }
                }
                attachTerminalCallbacks(callbacks)
                webView = this
                loadUrl("https://pocket.local/index.html")
            }
        })
        Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(4.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            listOf("ESC" to "\u001b", "TAB" to "\t", "CTRL+C" to "\u0003", "↑" to "\u001b[A", "↓" to "\u001b[B", "←" to "\u001b[D", "→" to "\u001b[C").forEach { (label, key) ->
                TextButton(onClick = { webView?.evaluateJavascript("window.terminalKey(${JSONObject.quote(key)})", null) }, contentPadding = PaddingValues(4.dp)) {
                    Text(label, color = Color(0xFFCBD5E1), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

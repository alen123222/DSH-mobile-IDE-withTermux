package dev.dsh.pocket

import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.util.concurrent.TimeUnit

const val BRIDGE_VERSION = "0.3.3"

/**
 * Never route through a proxy. The bridge only listens on this device's own
 * loopback, so a proxy can only break the connection. ProxySelector.of needs
 * API 24 desugaring that this minSdk does not need, so implement it directly.
 */
internal val NO_PROXY: ProxySelector = object : ProxySelector() {
    override fun select(uri: URI): List<Proxy> = listOf(Proxy.NO_PROXY)
    override fun connectFailed(uri: URI?, sa: SocketAddress?, io: IOException?) = Unit
}

class BridgeApi(internal val token: String) {
    // A VPN or a per-app proxy can capture an app's traffic while leaving Termux
    // alone, which sends the app's 127.0.0.1 somewhere other than the device's
    // own loopback interface. Termux then reports a perfectly healthy service
    // that the app can never reach. Probe the plausible local addresses once and
    // remember whichever answers, rather than trusting loopback blindly.
    @Volatile internal var host: String = "127.0.0.1"
    internal val candidates = listOf("127.0.0.1", "localhost", "10.0.2.2")

    /** Address currently in use; surfaced in the UI so a workaround is visible. */
    fun address(): String = host

    /** Remember a host that answered, so later calls skip the failing ones. */
    internal fun preferHost(candidate: String) {
        if (candidate != host) host = candidate
    }

    // Listing shared storage over Android's FUSE mount can take tens of seconds
    // on a phone full of photos. 12 s made the file browser grey out and time out
    // on perfectly ordinary folders, so browse/directory work gets its own budget.
    // The bridge only ever listens on the device's own loopback, so a proxy can
    // only ever break the connection. OkHttp otherwise honours the system-wide
    // proxy (a manual Wi-Fi/APN proxy, a "no VPN" claim notwithstanding), and
    // sends 127.0.0.1:8765 to that proxy. The proxy answers with an HTML error
    // page, which surfaces as a JSONException on an <html> body while Termux,
    // using raw sockets, still reaches the service fine.
    private val client = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS).callTimeout(60, TimeUnit.SECONDS)
        .proxy(Proxy.NO_PROXY).proxySelector(NO_PROXY).build()
    // A separate client: the SSE channel must never inherit the 12 s read timeout.
    internal val streamClient = OkHttpClient.Builder().connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS).retryOnConnectionFailure(true)
        .proxy(Proxy.NO_PROXY).proxySelector(NO_PROXY).build()

    internal fun decode(response: Response): JSONObject = decodeText(response.body?.string().orEmpty(), response.code)

    /**
     * Find a local address this process can actually reach.
     *
     * A VPN or per-app proxy can capture the app's traffic while leaving Termux
     * alone, so the app's own 127.0.0.1 never reaches the device loopback and a
     * service Termux calls healthy looks dead here. Try each candidate in order
     * and keep the first that answers; a thrown error means the host is unusable.
     */
    fun probeHosts(): String {
        var last: Throwable? = null
        for (candidate in candidates) {
            try { callOn(candidate, "health"); return candidate }
            catch (error: Throwable) { last = error }
        }
        throw last ?: IllegalStateException("no candidate host")
    }

    /** Try one call against a specific host without disturbing the preferred one. */
    fun callOn(target: String, route: String): JSONObject {
        val url = "http://$target:8765/v1/$route".toHttpUrl()
        val request = Request.Builder().url(url).header("Authorization", "Bearer $token").build()
        client.newCall(request).execute().use { response -> return decode(response) }
    }

    fun call(route: String, method: String = "GET", body: JSONObject? = null,
             query: Map<String, String> = emptyMap()): JSONObject {
        val url = "http://$host:8765/v1/$route".toHttpUrl().newBuilder()
        query.forEach { (key, value) -> url.addQueryParameter(key, value) }
        val request = Request.Builder().url(url.build()).header("Authorization", "Bearer $token")
        when (method) {
            "POST" -> request.post((body ?: JSONObject()).toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            "DELETE" -> request.delete()
        }
        client.newCall(request.build()).execute().use { response -> return decode(response) }
    }
}

data class StreamFrame(val chat: JSONObject?, val deleted: Boolean)

/**
 * Turn a raw response body into JSON, explaining the failure instead of letting
 * org.json throw something unreadable.
 *
 * An HTML body means the request never reached this bridge: something else
 * answered on the port, almost always a proxy. OkHttp honours the system proxy
 * while Termux uses raw sockets, so Termux reports a healthy service while the
 * app gets an error page.
 */
fun decodeText(text: String, code: Int): JSONObject {
    if (text.trimStart().startsWith("<")) {
        throw IllegalStateException(
            "收到 HTML 而非 JSON（HTTP $code），请求未到达本地服务。" +
            "通常是系统代理 / Wi-Fi 手动代理接管了 127.0.0.1:8765；" +
            "请到 设置 → WLAN → 当前网络 → 高级 → 代理，改为「无」。"
        )
    }
    val result = runCatching { JSONObject(text) }.getOrElse {
        throw IllegalStateException("响应不是有效 JSON（HTTP $code）: ${text.take(120)}")
    }
    if (code !in 200..299) throw IllegalStateException(result.optString("error", "连接失败 ($code)"))
    return result
}

/** Turn a transport failure into something a user can act on. */
fun describe(error: Throwable): String {
    val type = error.javaClass.simpleName
    val detail = error.message.orEmpty()
    return when {
        detail.contains("Failed to connect", true) || detail.contains("ECONNREFUSED", true) ->
            "端口 8765 无监听，Termux 服务未运行"
        detail.contains("timeout", true) || detail.contains("SocketTimeout", true) ->
            "连接超时，Termux 可能已被系统杀死"
        detail.contains("CLEARTEXT", true) -> "明文 HTTP 被系统策略拦截"
        detail.contains("HTML", true) -> "请求被代理接管（收到 HTML 而非本地服务的 JSON）"
        detail.contains("401", true) || detail.contains("密钥", true) -> "配对密钥不匹配"
        detail.isBlank() -> type
        else -> "$type: $detail"
    }
}

/** One SSE channel. Returns the Call so the caller can cancel it. */
fun BridgeApi.stream(id: String, onFrame: (StreamFrame) -> Unit, onClosed: () -> Unit): Call {
    val url = "http://$host:8765/v1/chats/$id/stream".toHttpUrl()
    val request = Request.Builder().url(url).header("Authorization", "Bearer $token").build()
    val call = streamClient.newCall(request)
    call.enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) { onClosed() }
        override fun onResponse(call: Call, response: Response) {
            // Every exit path must reach onClosed, otherwise a read error leaves
            // the caller waiting forever and the UI silently stops updating.
            try {
                if (!response.isSuccessful) { onClosed(); return }
                val type = response.header("Content-Type").orEmpty()
                if (!type.contains("text/event-stream")) { onClosed(); return }
                val source = response.body?.source() ?: run { onClosed(); return }
                var event = "message"
                while (!source.exhausted()) {
                    val line = source.readUtf8LineStrict()
                    when {
                        line.startsWith("event:") -> event = line.substringAfter(':').trim()
                        line.startsWith("data:") -> {
                            val data = line.substringAfter(':').trim()
                            if (data.isEmpty()) continue
                            if (event == "deleted") onFrame(StreamFrame(null, true))
                            else runCatching { JSONObject(data) }.getOrNull()?.let { onFrame(StreamFrame(it, false)) }
                        }
                        line.isBlank() -> event = "message"
                    }
                }
            } catch (e: IOException) {
                // Normal when the radio drops or the service is restarted.
            } finally {
                onClosed()
            }
        }
    })
    return call
}

fun JSONObject.string(key: String, default: String = "") = if (isNull(key)) default else optString(key, default)
fun JSONObject.objects(key: String): List<JSONObject> = optJSONArray(key)?.let { array ->
    (0 until array.length()).mapNotNull { array.optJSONObject(it) }
} ?: emptyList()

data class Workspace(val id: String, val name: String, val path: String, val available: Boolean = true, val starred: Boolean = false) {
    companion object {
        fun from(json: JSONObject) = Workspace(json.getString("id"), json.getString("name"), json.getString("path"),
            json.optBoolean("available", true), json.optBoolean("starred", false))
    }
}
data class FileEntry(val name: String, val path: String, val directory: Boolean, val size: Long) {
    companion object { fun from(json: JSONObject) = FileEntry(json.getString("name"), json.getString("path"), json.getBoolean("directory"), json.optLong("size")) }
}
data class EngineSettings(val model: String = "deepseek-v4-flash", val provider: String = "deepseek-official", val apiKey: String = "", val baseUrl: String = "",
    val protocol: String = "deepseek-messages", val autoVersion: Boolean = true,
    val contextWindow: Int = 131072, val maxTokens: Int = 8192) {
    // Kotlin default arguments do not generate Java overloads, so the Java
    // instrumentation test could no longer construct this after contextWindow
    // and maxTokens were added. Keep an explicit six-argument constructor.
    constructor(model: String, provider: String, apiKey: String, baseUrl: String, protocol: String, autoVersion: Boolean) :
        this(model, provider, apiKey, baseUrl, protocol, autoVersion, 131072, 8192)

    val route: String get() = if (protocol == "deepseek-messages") "deepseek-official" else "pocket-openai"
    fun json(): JSONObject = JSONObject().put("model", model).put("provider", route).put("apiKey", apiKey).put("baseUrl", baseUrl)
        .put("protocol", protocol).put("autoVersion", autoVersion)
        .put("contextWindow", contextWindow).put("maxTokens", maxTokens)
    companion object {
        fun from(json: JSONObject): EngineSettings {
            val provider = json.string("provider", "deepseek-official")
            val url = json.string("baseUrl")
            val inferred = if (provider != "deepseek-official" || url.trimEnd('/').endsWith("/chat/completions") || url.trimEnd('/').endsWith("/responses")) "openai-chat" else "deepseek-messages"
            return EngineSettings(json.string("model", "deepseek-v4-flash"), provider, json.string("apiKey"), url,
                json.string("protocol", inferred), json.optBoolean("autoVersion", true),
                json.optInt("contextWindow", 131072), json.optInt("maxTokens", 8192))
        }
    }
}

data class ApiPreset(val id: String, val name: String, val settings: EngineSettings)

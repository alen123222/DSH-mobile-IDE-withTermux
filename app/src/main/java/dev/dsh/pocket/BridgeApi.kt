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
import java.util.concurrent.TimeUnit

const val BRIDGE_VERSION = "0.3.1"

class BridgeApi(internal val token: String) {
    // Listing shared storage over Android's FUSE mount can take tens of seconds
    // on a phone full of photos. 12 s made the file browser grey out and time out
    // on perfectly ordinary folders, so browse/directory work gets its own budget.
    private val client = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS).callTimeout(60, TimeUnit.SECONDS).build()
    // A separate client: the SSE channel must never inherit the 12 s read timeout.
    internal val streamClient = OkHttpClient.Builder().connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS).retryOnConnectionFailure(true).build()

    fun call(route: String, method: String = "GET", body: JSONObject? = null,
             query: Map<String, String> = emptyMap()): JSONObject {
        val url = "http://127.0.0.1:8765/v1/$route".toHttpUrl().newBuilder()
        query.forEach { (key, value) -> url.addQueryParameter(key, value) }
        val request = Request.Builder().url(url.build()).header("Authorization", "Bearer $token")
        when (method) {
            "POST" -> request.post((body ?: JSONObject()).toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            "DELETE" -> request.delete()
        }
        client.newCall(request.build()).execute().use { response ->
            val result = JSONObject(response.body?.string() ?: "{}")
            if (!response.isSuccessful) throw IllegalStateException(result.optString("error", "连接失败 (${response.code})"))
            return result
        }
    }
}

data class StreamFrame(val chat: JSONObject?, val deleted: Boolean)

/** One SSE channel. Returns the Call so the caller can cancel it. */
fun BridgeApi.stream(id: String, onFrame: (StreamFrame) -> Unit, onClosed: () -> Unit): Call {
    val url = "http://127.0.0.1:8765/v1/chats/$id/stream".toHttpUrl()
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

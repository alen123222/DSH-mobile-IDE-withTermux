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

const val BRIDGE_VERSION = "0.3.0"

class BridgeApi(internal val token: String) {
    private val client = OkHttpClient.Builder().connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS).callTimeout(15, TimeUnit.SECONDS).build()
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

/** Long-lived Server-Sent Events channel for one session. Calls onFrame on a background dispatcher. */
fun BridgeApi.stream(id: String, onFrame: (StreamFrame) -> Unit, onClosed: () -> Unit): Call {
    val url = "http://127.0.0.1:8765/v1/chats/$id/stream".toHttpUrl()
    val request = Request.Builder().url(url).header("Authorization", "Bearer $token").build()
    val call = streamClient.newCall(request)
    call.enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) { onClosed() }
        override fun onResponse(call: Call, response: Response) {
            response.use {
                val source = it.body?.source() ?: run { onClosed(); return }
                var event = "message"
                while (!source.exhausted()) {
                    val line = source.readUtf8LineStrict()
                    when {
                        line.startsWith("event:") -> event = line.substringAfter(':').trim()
                        line.startsWith("data:") -> {
                            val data = line.substringAfter(':').trim()
                            if (event == "deleted") onFrame(StreamFrame(null, true))
                            else runCatching { JSONObject(data) }.getOrNull()?.let { onFrame(StreamFrame(it, false)) }
                        }
                        line.isBlank() -> event = "message"
                    }
                }
            }
            onClosed()
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

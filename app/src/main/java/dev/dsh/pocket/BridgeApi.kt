package dev.dsh.pocket

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class BridgeApi(private val token: String) {
    private val client = OkHttpClient.Builder().connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS).callTimeout(15, TimeUnit.SECONDS).build()

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

fun JSONObject.string(key: String, default: String = "") = if (isNull(key)) default else optString(key, default)
fun JSONObject.objects(key: String): List<JSONObject> = optJSONArray(key)?.let { array ->
    (0 until array.length()).mapNotNull { array.optJSONObject(it) }
} ?: emptyList()

data class Workspace(val id: String, val name: String, val path: String, val available: Boolean = true) {
    companion object { fun from(json: JSONObject) = Workspace(json.getString("id"), json.getString("name"), json.getString("path"), json.optBoolean("available", true)) }
}
data class FileEntry(val name: String, val path: String, val directory: Boolean, val size: Long) {
    companion object { fun from(json: JSONObject) = FileEntry(json.getString("name"), json.getString("path"), json.getBoolean("directory"), json.optLong("size")) }
}
data class EngineSettings(val model: String = "deepseek-v4-flash", val provider: String = "deepseek-official", val apiKey: String = "", val baseUrl: String = "",
    val protocol: String = "deepseek-messages", val autoVersion: Boolean = true) {
    val route: String get() = if (protocol == "deepseek-messages") "deepseek-official" else "pocket-openai"
    fun json(): JSONObject = JSONObject().put("model", model).put("provider", route).put("apiKey", apiKey).put("baseUrl", baseUrl)
        .put("protocol", protocol).put("autoVersion", autoVersion)
    companion object {
        fun from(json: JSONObject): EngineSettings {
            val provider = json.string("provider", "deepseek-official")
            val url = json.string("baseUrl")
            val inferred = if (provider != "deepseek-official" || url.trimEnd('/').endsWith("/chat/completions") || url.trimEnd('/').endsWith("/responses")) "openai-chat" else "deepseek-messages"
            return EngineSettings(json.string("model", "deepseek-v4-flash"), provider, json.string("apiKey"), url,
                json.string("protocol", inferred), json.optBoolean("autoVersion", true))
        }
    }
}

data class ApiPreset(val id: String, val name: String, val settings: EngineSettings)

package dev.dsh.pocket

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object ProviderEndpoint {
    fun base(settings: EngineSettings): String {
        require(settings.protocol in listOf("openai-chat", "openai-responses", "deepseek-messages")) { tr("不支持的 API 协议") }
        val fallback = if (settings.protocol == "deepseek-messages") "https://api.deepseek.com/anthropic" else "https://api.openai.com/v1"
        val url = (settings.baseUrl.trim().ifEmpty { fallback }).toHttpUrl()
        require(url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null) { tr("API 地址不能包含账号、查询参数或片段") }
        var path = url.encodedPath.trimEnd('/')
        val suffixes = if (settings.protocol == "deepseek-messages") listOf("/v1/messages", "/messages") else listOf("/chat/completions", "/responses")
        val suffix = suffixes.firstOrNull { path.endsWith(it) }
        if (suffix != null) path = path.removeSuffix(suffix)
        if (suffix == null && path.isEmpty() && settings.protocol != "deepseek-messages" && settings.autoVersion) path = "/v1"
        return url.newBuilder().encodedPath(path.ifEmpty { "/" }).build().toString().trimEnd('/')
    }
    fun request(settings: EngineSettings): String = base(settings) + when (settings.protocol) {
        "deepseek-messages" -> if (base(settings).endsWith("/v1")) "/messages" else "/v1/messages"
        "openai-responses" -> "/responses"
        else -> "/chat/completions"
    }
    fun models(settings: EngineSettings): String {
        val base = base(settings)
        // The official Messages gateway shares the regular DeepSeek model catalog.
        if (settings.protocol == "deepseek-messages" && base == "https://api.deepseek.com/anthropic") return "https://api.deepseek.com/models"
        return base + if (settings.protocol == "deepseek-messages" && !base.endsWith("/v1")) "/v1/models" else "/models"
    }
}

class ProviderClient {
    private val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(35, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build()
    private fun call(settings: EngineSettings, url: String, body: JSONObject? = null): JSONObject {
        require(settings.apiKey.isNotBlank()) { tr("请先输入 API Key") }
        val request = Request.Builder().url(url).header("Accept", "application/json")
        if (settings.protocol == "deepseek-messages") request.header("x-api-key", settings.apiKey).header("anthropic-version", "2023-06-01")
        else request.header("Authorization", "Bearer ${settings.apiKey}")
        if (body != null) request.post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
        client.newCall(request.build()).execute().use { response ->
            val source = response.body?.source() ?: error(tr("API 返回空响应"))
            val buffer = okio.Buffer()
            while (buffer.size <= 2 * 1024 * 1024) {
                if (source.read(buffer, 8192) == -1L) break
            }
            check(buffer.size <= 2 * 1024 * 1024) { tr("API 响应过大") }
            val raw = buffer.readUtf8()
            val json = runCatching { JSONObject(raw) }.getOrNull()
            if (!response.isSuccessful) {
                val detail = json?.optJSONObject("error")?.string("message")?.ifBlank { null }
                    ?: json?.string("message")?.ifBlank { null } ?: tr("请检查地址、协议和密钥")
                error("HTTP ${response.code}：${detail.replace(settings.apiKey, "[已隐藏]").take(400)}")
            }
            return json ?: error(tr("API 返回的不是 JSON，请检查端点路径"))
        }
    }
    fun models(settings: EngineSettings): List<String> {
        val json = call(settings, ProviderEndpoint.models(settings))
        val array = json.optJSONArray("data") ?: json.optJSONArray("models") ?: error(tr("端点未提供模型列表；可手动填写模型 ID 后测试"))
        val ids = (0 until minOf(array.length(), 1000)).mapNotNull { index ->
            (array.optJSONObject(index)?.string("id") ?: array.optString(index)).takeIf { it.isNotBlank() }
        }.distinct().sorted()
        check(ids.isNotEmpty()) { tr("模型列表为空，可手动填写模型 ID 后测试") }
        return ids
    }
    fun test(settings: EngineSettings): String {
        require(settings.model.isNotBlank()) { tr("请填写或选择模型 ID") }
        val message = JSONObject().put("role", "user").put("content", "Reply with OK.")
        val body = JSONObject().put("model", settings.model).put("stream", false)
        when (settings.protocol) {
            "openai-responses" -> body.put("input", "Reply with OK.").put("max_output_tokens", 32)
            "deepseek-messages" -> body.put("messages", JSONArray().put(message)).put("max_tokens", 32)
            else -> body.put("messages", JSONArray().put(message)).put(
                if (settings.model.startsWith("gpt-5") || Regex("^o[1-9].*").matches(settings.model)) "max_completion_tokens" else "max_tokens", 128)
        }
        val json = call(settings, ProviderEndpoint.request(settings), body)
        if (json.has("error") && !json.isNull("error")) error(tr("API 返回错误：") + json.opt("error")?.toString().orEmpty().replace(settings.apiKey, tr("[已隐藏]")).take(400))
        val valid = when (settings.protocol) {
            "openai-responses" -> json.has("output") && json.string("status") != "failed"
            "deepseek-messages" -> json.has("content")
            else -> json.optJSONArray("choices")?.length()?.let { it > 0 } == true
        }
        check(valid) { tr("HTTP 请求成功，但响应不符合所选协议") }
        return tr("模型 ") + settings.model + tr(" 请求成功。工具调用能力会在 DSH 对话中进一步验证。")
    }
}

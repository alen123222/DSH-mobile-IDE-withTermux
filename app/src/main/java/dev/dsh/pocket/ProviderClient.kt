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

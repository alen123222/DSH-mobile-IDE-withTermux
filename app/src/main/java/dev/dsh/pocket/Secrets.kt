package dev.dsh.pocket

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import org.json.JSONArray
import java.util.UUID
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class Secrets(context: Context) {
    private val prefs = context.getSharedPreferences("pocket-private", Context.MODE_PRIVATE)
    private val alias = "dsh-pocket-secrets-v1"
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    private fun read(name: String): String? {
        val stored = prefs.getString(name, null) ?: return null
        val bytes = Base64.decode(stored, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        return String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
    }
    private fun write(name: String, value: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val bytes = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        prefs.edit().putString(name, Base64.encodeToString(bytes, Base64.NO_WRAP)).apply()
    }
    fun token(): String = read("bridge-token") ?: ByteArray(32).also { SecureRandom().nextBytes(it) }
        .joinToString("") { "%02x".format(it) }.also { write("bridge-token", it) }
    fun settings(): EngineSettings {
        val (active, items) = presets()
        return items.firstOrNull { it.id == active }?.settings ?: items.first().settings
    }
    fun presets(): Pair<String, List<ApiPreset>> {
        read("api-presets")?.let { value ->
            val json = JSONObject(value)
            val items = json.objects("items").map { ApiPreset(it.getString("id"), it.getString("name"), EngineSettings.from(it.getJSONObject("settings"))) }
            if (items.isNotEmpty()) return json.string("activeId", items.first().id) to items
        }
        val item = ApiPreset(UUID.randomUUID().toString(), tr("原有连接"), EngineSettings.from(JSONObject(read("engine") ?: "{}")))
        savePresets(item.id, listOf(item))
        return item.id to listOf(item)
    }
    fun savePresets(activeId: String, items: List<ApiPreset>) {
        require(items.isNotEmpty())
        write("api-presets", JSONObject().put("activeId", activeId).put("items", JSONArray(items.map {
            JSONObject().put("id", it.id).put("name", it.name).put("settings", it.settings.json())
        })).toString())
    }
}

package dev.dsh.pocket

import android.app.Instrumentation
import android.content.Intent
import org.json.JSONObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType

object PhoneSmoke {
    @JvmStatic fun run(test: Instrumentation): String {
        val context = test.targetContext
        val previous = PhoneControl.allowed
        val client = OkHttpClient()
        fun request(token: String, body: JSONObject): JSONObject = client.newCall(Request.Builder()
            .url("http://127.0.0.1:${BuildConfig.BRIDGE_PORT + 1}/phone")
            .header("Authorization", "Bearer $token").post(body.toString().toRequestBody("application/json".toMediaType())).build()).execute().use { JSONObject(it.body!!.string()) }
        try {
            test.startActivitySync(Intent(context, PhoneFixtureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            repeat(40) { if (!PhoneControl.connected.value) Thread.sleep(250) }
            check(PhoneControl.connected.value) { "Accessibility service not connected" }
            PhoneControl.allow(context, context.packageName, true)
            PhoneControl.toggle(true)
            val token = PhoneControl.grant()!!.getString("token")
            fun call(action: String, extra: JSONObject = JSONObject()) = request(token, extra.put("action", action))
            fun observe(): JSONObject { Thread.sleep(500); return call("observe").also { check(it.getBoolean("ok")) { it.toString() } } }
            check(!request("invalid", JSONObject().put("action", "observe")).getBoolean("ok"))
            var screen = observe()
            check(screen.getInt("count") > 0) { "The fixture exposes no controls" }
            check(!screen.toString().contains("private-marker")) { "Password leaked" }
            // Tap the field, then type into whatever holds focus, exactly as a person would.
            check(call("tap", JSONObject().put("desc", "phone-test-input")).getBoolean("ok"))
            check(call("type", JSONObject().put("text", "手机控制中文测试")).getBoolean("ok"))
            screen = observe()
            check(screen.toString().contains("手机控制中文测试")) { "Typed text is not on screen" }
            // Tap by text, resolved when it runs, so no observation can go stale.
            check(call("tap", JSONObject().put("text", "phone-test-button")).getBoolean("ok"))
            screen = observe()
            check(screen.toString().contains("phone-test-clicked")) { "The tap did not land" }
            check(call("scroll", JSONObject().put("direction", "down")).getBoolean("ok"))
            check(call("tap", JSONObject().put("text", "no-such-control")).getBoolean("ok").not()) { "Unknown target accepted" }
            val shot = call("screenshot"); check(shot.getBoolean("ok")) { shot.toString() }; check(shot.getString("imageBase64").length > 1000)
            PhoneControl.allow(context, context.packageName, false)
            check(!call("observe").getBoolean("ok")) { "App allowlist bypassed" }
            PhoneControl.revoke()
            check(!call("state").getBoolean("ok")) { "Revoked token accepted" }
            return "Phone control PASS: auth, allowlist, password redaction, text tap, Chinese input, scroll, screenshot, revocation"
        } finally {
            PhoneControl.toggle(false)
            for (pkg in PhoneControl.allowed - previous) PhoneControl.allow(context, pkg, false)
            for (pkg in previous - PhoneControl.allowed) PhoneControl.allow(context, pkg, true)
        }
    }
}

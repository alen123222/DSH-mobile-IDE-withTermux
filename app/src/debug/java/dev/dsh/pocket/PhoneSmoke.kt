package dev.dsh.pocket

import android.app.Instrumentation
import android.content.Intent
import org.json.JSONObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType

object PhoneSmoke {
    @JvmStatic fun run(test: Instrumentation, checkWechat: Boolean = false): String {
        return run(test.targetContext, { test.startActivitySync(it) }, { test.runOnMainSync(it) }, checkWechat)
    }

    fun run(context: android.content.Context, launch: (Intent) -> android.app.Activity,
            onMain: (Runnable) -> Unit, checkWechat: Boolean): String {
        val previous = PhoneControl.allowed
        val client = OkHttpClient()
        fun request(token: String, body: JSONObject): JSONObject = client.newCall(Request.Builder()
            .url("http://127.0.0.1:${BuildConfig.BRIDGE_PORT + 1}/phone")
            .header("Authorization", "Bearer $token").post(body.toString().toRequestBody("application/json".toMediaType())).build()).execute().use { JSONObject(it.body!!.string()) }
        try {
            val fixture = launch(Intent(context, PhoneFixtureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
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
            // Keep the IME open: it used to be mistaken for a second application.
            Thread.sleep(700)
            val keyboardShot = call("screenshot")
            check(keyboardShot.getBoolean("ok")) { "Screenshot with keyboard: $keyboardShot" }
            val keyboard = PocketAccessibilityService.instance?.windows?.firstOrNull {
                it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD
            }
            check(keyboard != null) { "Keyboard must be visible for the tap guard test" }
            val keyboardBounds = android.graphics.Rect().also { keyboard.getBoundsInScreen(it) }
            val keyTap = call("tap", JSONObject().put("x", keyboardBounds.centerX()).put("y", keyboardBounds.centerY()))
            check(!keyTap.optBoolean("ok") && keyTap.optString("error").contains("phone_type")) { "Keyboard tap was not rejected: $keyTap" }
            observe()
            check(!call("tap", JSONObject().put("fx", 0.5).put("fy", 950)).getBoolean("ok")) { "Out-of-range fraction accepted" }
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                // Simulate an editor that works with the keyboard but exposes no nodes.
                onMain(Runnable { fixture.window.decorView.importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS })
                Thread.sleep(700)
                val nativeInput = call("type", JSONObject().put("text", "原生中文输入测试"))
                check(nativeInput.optBoolean("ok") && nativeInput.optString("method") == "inputConnection") { nativeInput.toString() }
                Thread.sleep(500)
                onMain(Runnable {
                    val input = fixture.window.decorView.findViewWithTag<android.widget.EditText>("phone-test-input")
                    check(input.text.toString() == "原生中文输入测试") { "Native input did not replace the editor contents" }
                    fixture.window.decorView.importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
                })
                observe()
            }
            // Tap by text, resolved when it runs, so no observation can go stale.
            check(call("tap", JSONObject().put("text", "phone-test-button")).getBoolean("ok"))
            screen = observe()
            check(screen.toString().contains("phone-test-clicked", ignoreCase = true)) { "The tap did not land" }
            check(call("scroll", JSONObject().put("direction", "down")).getBoolean("ok"))
            check(call("tap", JSONObject().put("text", "no-such-control")).getBoolean("ok").not()) { "Unknown target accepted" }
            val shot = call("screenshot"); check(shot.getBoolean("ok")) { shot.toString() }; check(shot.getString("imageBase64").length > 1000)
            PhoneControl.allow(context, context.packageName, false)
            check(!call("observe").getBoolean("ok")) { "App allowlist bypassed" }
            check(!call("screenshot").getBoolean("ok")) { "Screenshot allowlist bypassed" }
            if (checkWechat) {
                check("com.tencent.mm" in previous) { "WeChat must already be user-allowed" }
                check(call("launch", JSONObject().put("package", "com.tencent.mm")).getBoolean("ok"))
                Thread.sleep(1500)
                val wechat = observe()
                check(wechat.getString("package") == "com.tencent.mm") { "WeChat is not foreground" }
                val wechatShot = call("screenshot")
                check(wechatShot.getBoolean("ok")) { "WeChat screenshot: $wechatShot" }
                check(wechatShot.getString("imageBase64").length > 1000)
                if (android.os.Build.VERSION.SDK_INT >= 33) {
                    val method = PocketAccessibilityService.instance?.inputMethod
                    val connection = method?.currentInputConnection
                    check(method?.currentInputEditorInfo?.packageName == "com.tencent.mm" && connection != null) {
                        "Tap a WeChat input field before running the input test"
                    }
                    val original = connection.getSurroundingText(4096, 4096, 0)
                    // Legacy editors report offset=-1 even when the requested
                    // before/after ranges contain the whole short field.
                    check(original != null && original.offset in -1..0 && original.text.length < 4096) {
                        "Cannot safely preserve the existing draft (offset=${original?.offset}, length=${original?.text?.length})"
                    }
                    try {
                        val typed = call("type", JSONObject().put("text", "DSH中文输入验证"))
                        check(typed.optBoolean("ok")) { typed.toString() }
                        Thread.sleep(700)
                        val after = connection.getSurroundingText(4096, 4096, 0)
                        check(after?.text?.toString() == "DSH中文输入验证") { "WeChat did not contain the test text" }
                    } finally {
                        check(method.currentInputEditorInfo?.packageName == "com.tencent.mm") { "Editor changed before draft restoration" }
                        connection.performContextMenuAction(android.R.id.selectAll)
                        connection.commitText(original.text, 1, null)
                        Thread.sleep(500)
                        connection.setSelection(original.selectionStart, original.selectionEnd)
                        check(connection.getSurroundingText(4096, 4096, 0)?.text?.toString() == original.text.toString()) { "WeChat draft restoration failed" }
                    }
                }
            }
            PhoneControl.revoke()
            check(!call("state").getBoolean("ok")) { "Revoked token accepted" }
            return "Phone control PASS: auth, allowlist, password redaction, text tap, Chinese input, scroll, screenshot with keyboard, screenshot allowlist, revocation" +
                if (checkWechat) "; WeChat screenshot and Chinese input/readback PASS; draft restored; no messages sent" else ""
        } finally {
            PhoneControl.toggle(false)
            for (pkg in PhoneControl.allowed - previous) PhoneControl.allow(context, pkg, false)
            for (pkg in previous - PhoneControl.allowed) PhoneControl.allow(context, pkg, true)
        }
    }
}

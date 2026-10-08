package dev.dsh.pocket

import android.app.Activity
import org.json.JSONObject

/** Actual configured model + DSH + phone tools. No messaging application is used. */
object PhoneAgentSmoke {
    fun run(activity: Activity): String {
        val context = activity.applicationContext
        val previous = PhoneControl.allowed.toSet()
        val api = BridgeApi(Secrets(context).token())
        var chatId: String? = null
        fun ui(action: () -> Unit) {
            val done = java.util.concurrent.CountDownLatch(1)
            activity.runOnUiThread { try { action() } finally { done.countDown() } }
            check(done.await(5, java.util.concurrent.TimeUnit.SECONDS))
        }
        try {
            check(api.call("chats").objects("items").none { it.string("status") == "running" }) { "Another DSH task is running" }
            val workspace = api.call("workspaces").objects("items").first()
            for (pkg in previous - context.packageName) PhoneControl.allow(context, pkg, false)
            PhoneControl.allow(context, context.packageName, true)
            ui {
                val field = activity.window.decorView.findViewWithTag<android.widget.EditText>("phone-test-input")
                field.setText("")
                field.requestFocus()
                activity.getSystemService(android.view.inputmethod.InputMethodManager::class.java).showSoftInput(field, 0)
                activity.window.decorView.importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            }
            Thread.sleep(1000)
            PhoneControl.toggle(true)
            val phone = PhoneControl.grant()
            chatId = api.call("chats", "POST", JSONObject().put("workspaceId", workspace.getString("id"))).getString("id")
            val body = Secrets(context).settings().json().put("phone", phone).put("allowExecution", true)
                .put("prompt", "在当前页面的输入框填入“喵喵喵”，确认内容后结束。不要点击发送，不要切换应用，不要修改文件。")
            api.call("chats/$chatId/prompt", "POST", body)
            var chat = api.call("chats/$chatId")
            val deadline = System.currentTimeMillis() + 180_000
            while (chat.string("status") == "running" && System.currentTimeMillis() < deadline) {
                check(chat.objects("events").count { it.string("type") == "tool/call" } <= 12) { "Agent exceeded 12 tool calls" }
                Thread.sleep(1000)
                chat = api.call("chats/$chatId")
            }
            val calls = chat.objects("events").filter { it.string("type") == "tool/call" }.map { it.getJSONObject("data") }
            check(chat.string("status") != "running") { "Agent timed out" }
            check(calls.any { it.string("name") == "phone_type" && it.string("arguments").contains("喵喵喵") }) { "Agent did not use whole-text phone_type" }
            check(calls.none { it.string("name") == "bash" }) { "Agent attempted shell input" }
            var actual = ""
            ui { actual = activity.window.decorView.findViewWithTag<android.widget.EditText>("phone-test-input").text.toString() }
            check(actual == "喵喵喵") { "Actual field content differs from requested text" }
            return "PASS: real configured model + DSH filled the hidden-node editor with 喵喵喵. Tools: " + calls.joinToString { it.string("name") } + "; chat=$chatId"
        } finally {
            if (chatId != null) try { if (api.call("chats/$chatId").string("status") == "running") api.call("chats/$chatId/stop", "POST") } catch (_: Exception) { }
            PhoneControl.toggle(false)
            for (pkg in PhoneControl.allowed - previous) PhoneControl.allow(context, pkg, false)
            for (pkg in previous - PhoneControl.allowed) PhoneControl.allow(context, pkg, true)
            ui { activity.window.decorView.importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_AUTO }
        }
    }
}

package dev.dsh.pocket

import android.app.Activity
import android.os.Bundle
import android.widget.*

/** Deterministic on-device fixture, never shown in the production UI. */
class PhoneFixtureActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val agentTest = intent.getBooleanExtra("agenttest", false)
        if (agentTest) {
            layout.setPadding(40, 180, 40, 0)
            layout.addView(TextView(this).apply { text = "中文输入测试"; textSize = 24f })
        }
        layout.addView(EditText(this).apply { tag = "phone-test-input"; contentDescription = "phone-test-input"; setText("initial") })
        if (!agentTest) {
        layout.addView(EditText(this).apply { inputType = 129; contentDescription = "phone-test-password"; setText("private-marker") })
        layout.addView(Button(this).apply { text = "phone-test-button"; setOnClickListener { text = "phone-test-clicked" } })
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        repeat(70) { content.addView(TextView(this).apply { text = "phone-test-row-$it"; textSize = 24f; setPadding(12, 20, 12, 20) }) }
        layout.addView(ScrollView(this).apply { addView(content) })
        }
        setContentView(layout)
        if (intent.getBooleanExtra("agenttest", false)) Thread {
            val output = try { PhoneAgentSmoke.run(this) }
                catch (e: Throwable) { "FAIL: " + android.util.Log.getStackTraceString(e) }
            java.io.File(filesDir, "phone-agent-test.txt").writeText(output)
        }.start()
        // Proves the Shizuku action backend: dump, resolve a label, inject a tap — all
        // as the shell user, with no accessibility service in the picture.
        if (intent.getBooleanExtra("shzphone", false)) Thread {
            try {
                ShizukuShot.observe(this)
                Thread.sleep(2000)
                val observed = ShizukuPhone.observe(this)
                val nodes = observed.optJSONArray("nodes")
                val labels = (0 until (nodes?.length() ?: 0))
                    .mapNotNull { nodes?.getJSONObject(it)?.optString("text")?.takeIf { text -> text.isNotBlank() } }
                    .take(6)
                val tapped = ShizukuPhone.tap(this, org.json.JSONObject().put("text", "PHONE-TEST-BUTTON"))
                val after = ShizukuPhone.observe(this)
                java.io.File(filesDir, "shz-phone-test.txt").writeText(
                    "ok=" + observed.optBoolean("ok") + " count=" + observed.optInt("count") +
                        " pkg=" + observed.optString("package") + " labels=" + labels.joinToString("|") +
                        " tapOk=" + tapped.optBoolean("ok") + " tapError=" + tapped.optString("error") +
                        " after=" + after.optInt("count"))
            } catch (e: Throwable) {
                java.io.File(filesDir, "shz-phone-test.txt").writeText("FAIL: " + android.util.Log.getStackTraceString(e))
            }
        }.start()
        // Proves the Shizuku path end to end: the PNG magic number cannot appear unless
        // a real screenshot came back through the shell user.
        if (intent.getBooleanExtra("shizuku", false)) Thread {
            try {
                ShizukuShot.observe(this)
                Thread.sleep(2000)
                val bytes = ShizukuShot.capture(this)
                val magic = bytes?.take(8)?.joinToString(",") { (it.toInt() and 0xFF).toString() } ?: "-"
                java.io.File(filesDir, "shizuku-test.txt").writeText(
                    "running=" + ShizukuShot.running.value + " granted=" + ShizukuShot.granted.value +
                        " bytes=" + (bytes?.size ?: -1) + " magic=" + magic)
            } catch (e: Throwable) {
                java.io.File(filesDir, "shizuku-test.txt").writeText("FAIL: " + android.util.Log.getStackTraceString(e))
            }
        }.start()
        // Shell-only debug fixture runs in the existing process so instrumentation
        // does not kill the bound accessibility service on device ROMs.
        if (intent.getBooleanExtra("selftest", false)) Thread {
            val output = try {
                PhoneSmoke.run(this, { this }, { action ->
                    val done = java.util.concurrent.CountDownLatch(1)
                    var failure: Throwable? = null
                    runOnUiThread { try { action.run() } catch (e: Throwable) { failure = e } finally { done.countDown() } }
                    check(done.await(5, java.util.concurrent.TimeUnit.SECONDS)) { "UI test timed out" }
                    failure?.let { throw it }
                }, intent.getBooleanExtra("wechat", false))
            } catch (e: Throwable) { "FAIL: " + android.util.Log.getStackTraceString(e) }
            java.io.File(filesDir, "phone-selftest.txt").writeText(output)
        }.start()
    }
}

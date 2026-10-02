package dev.dsh.pocket

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject

object TermuxConnection {
    const val PERMISSION = "com.termux.permission.RUN_COMMAND"
    const val HOME = "/data/data/com.termux/files/home"
    const val PREFIX = "/data/data/com.termux/files/usr"
    val lastResult = MutableStateFlow("")
    val setupCommand = "mkdir -p ~/.termux && printf '\\nallow-external-apps=true\\n' >> ~/.termux/termux.properties && termux-reload-settings"

    fun installed(context: Context): Boolean = try { context.packageManager.getPackageInfo("com.termux", 0); true } catch (_: PackageManager.NameNotFoundException) { false }
    fun permitted(context: Context) = context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED

    private fun run(context: Context, script: String, background: Boolean, title: String) {
        check(installed(context)) { "请先安装并打开 Termux" }
        check(permitted(context)) { "请先授予“在 Termux 环境中运行命令”权限" }
        val receiver = Intent(context, TermuxResultReceiver::class.java)
        val callback = PendingIntent.getBroadcast(context, (System.nanoTime() and 0x7fffffff).toInt(), receiver,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_MUTABLE)
        val intent = Intent("com.termux.RUN_COMMAND").apply {
            component = ComponentName("com.termux", "com.termux.app.RunCommandService")
            putExtra("com.termux.RUN_COMMAND_PATH", "$PREFIX/bin/bash")
            // Termux 0.118 forwards stdin only to background tasks. A foreground
            // PTY must receive its script as an argument or bash -s waits forever.
            putExtra("com.termux.RUN_COMMAND_ARGUMENTS", if (background) arrayOf("-s") else arrayOf("-c", script))
            if (background) putExtra("com.termux.RUN_COMMAND_STDIN", script)
            putExtra("com.termux.RUN_COMMAND_WORKDIR", HOME)
            putExtra("com.termux.RUN_COMMAND_BACKGROUND", background)
            putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "0")
            putExtra("com.termux.RUN_COMMAND_COMMAND_LABEL", title)
            putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", callback)
        }
        lastResult.value = "已发送：$title。前台任务的进度会显示在 Termux 中。"
        check(context.startService(intent) != null) { "Termux 没有响应" }
    }

    private fun assetsScript(context: Context, folders: List<String>) = buildString {
            append("set -eu\numask 077\nROOT=\"\$HOME/.local/share/dsh-pocket\"\n")
            for (folder in folders) {
                append("mkdir -p \"\$ROOT/$folder\"\n")
                for (file in context.assets.list(folder).orEmpty()) {
                    val bytes = context.assets.open("$folder/$file").use { it.readBytes() }
                    val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                    append("printf '%s' '$base64' | base64 -d > \"\$ROOT/$folder/$file\"\n")
                }
            }
    }

    fun bootstrap(context: Context, token: String) {
        val script = buildString {
            append(assetsScript(context, listOf("bridge", "termux")))
            val config = JSONObject().put("token", token).put("port", 8765).toString()
            val base64 = Base64.encodeToString(config.toByteArray(), Base64.NO_WRAP)
            append("printf '%s' '$base64' | base64 -d > \"\$ROOT/connection.json\"\n")
            append("if ! command -v node >/dev/null || ! command -v python >/dev/null; then\n echo '请在 Termux 执行: pkg install nodejs-lts python'; exit 1; fi\n")
            append("export POCKET_HOME=\"\$ROOT\"\nexec node \"\$ROOT/bridge/server.mjs\"\n")
        }
        run(context, script, true, "DSH Pocket 本地服务")
    }
    fun upgrade(context: Context, token: String) {
        val script = buildString {
            append("set -eu\nexport POCKET_UPDATE_TOKEN='$token'\nnode --input-type=module <<'POCKET_UPDATE'\n")
            append("""
                const headers = { Authorization: 'Bearer ' + process.env.POCKET_UPDATE_TOKEN };
                const health = await fetch('http://127.0.0.1:8765/v1/health', {headers});
                if (!health.ok) throw new Error('无法验证现有本地服务');
                const data = await health.json();
                const chats = await fetch('http://127.0.0.1:8765/v1/chats', {headers});
                if (!chats.ok || (await chats.json()).items.some(x => x.status === 'running')) throw new Error('请先停止正在运行的 DSH 任务再更新服务');
                if (!Number.isSafeInteger(data.pid) || data.pid < 2) throw new Error('无效服务进程');
                process.kill(data.pid, 'SIGTERM');
                let stopped = false;
                for (let i = 0; i < 60; i++) {
                  await new Promise(resolve => setTimeout(resolve, 100));
                  try { await fetch('http://127.0.0.1:8765/v1/health', {headers}); }
                  catch { stopped = true; break; }
                }
                if (!stopped) throw new Error('现有服务未退出');
            """.trimIndent())
            append("\nPOCKET_UPDATE\nunset POCKET_UPDATE_TOKEN\n")
            append(assetsScript(context, listOf("bridge", "termux")))
            append("export POCKET_HOME=\"\$ROOT\"\nexec node \"\$ROOT/bridge/server.mjs\"\n")
        }
        run(context, script, true, "更新 DSH Pocket 本地服务")
    }
    fun installEngine(context: Context) = run(context, buildString {
        append("printf '\\nDSH Pocket: starting Android engine installation\\n'\n")
        // Refresh the installer even when the existing local service is still
        // running from an earlier APK. No server restart is required.
        append(assetsScript(context, listOf("termux")))
        append("exec bash \"\$ROOT/termux/install-dsh.sh\"\n")
    }, false, "安装 DSH Android 引擎")
    fun installBase(context: Context) = run(context, "pkg install -y nodejs-lts python\n", false, "安装本地运行环境")
    fun openTerminal(context: Context, cwd: String) {
        val quote = "'" + cwd.replace("'", "'\\''") + "'"
        run(context, "cd -- $quote || exit\nexec \"\$PREFIX/bin/bash\" -l\n", false, "项目终端")
    }
    fun openApp(context: Context) {
        val intent = context.packageManager.getLaunchIntentForPackage("com.termux") ?: error("尚未安装 Termux")
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

class TermuxResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val result = intent.getBundleExtra("result") ?: return
        val error = result.getString("errmsg").orEmpty()
        val output = result.getString("stdout").orEmpty() + result.getString("stderr").orEmpty()
        val exitCode = if (result.containsKey("exitCode")) result.getInt("exitCode").toString() else "未知"
        TermuxConnection.lastResult.value = "Termux 任务结束 · 退出码 $exitCode\n" +
            (if (error.isNotBlank()) error else output).takeLast(12000)
    }
}

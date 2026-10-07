package dev.dsh.pocket

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Base64
import android.os.Build
import android.net.Uri
import android.provider.Settings
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
        check(installed(context)) { tr("请先安装并打开 Termux") }
        check(permitted(context)) { tr("请先授予“在 Termux 环境中运行命令”权限") }
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
        lastResult.value = tr("已发送：") + title + tr("。前台任务的进度会显示在 Termux 中。")
        check(context.startService(intent) != null) { tr("Termux 没有响应") }
    }

    private fun assetsScript(context: Context, folders: List<String>) = buildString {
            append("set -eu\numask 077\nROOT=\"\$HOME/.local/share/${BuildConfig.STATE_DIRECTORY}\"\n")
            for (folder in folders) {
                append("mkdir -p \"\$ROOT/$folder\"\n")
                for (file in context.assets.list(folder).orEmpty()) {
                    val bytes = context.assets.open("$folder/$file").use { it.readBytes() }
                    val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                    append("printf '%s' '$base64' | base64 -d > \"\$ROOT/$folder/$file\"\n")
                }
            }
    }

    // A fragment handed to RUN_COMMAND must end in a newline or it will be glued
    // to whatever is appended next. Kotlin's trimIndent() also strips trailing
    // blank lines, so wrap every such fragment in this instead of trusting it.
    private fun String.asScriptBlock() = if (endsWith("\n")) this else this + "\n"

    // Launching the bridge used to be fire-and-forget: every reconnect ran a bare
    // `node server.mjs`, so a second launch while the first was alive died with
    // EADDRINUSE and the app could never reconnect again. The server now adopts a
    // healthy existing instance, and this script additionally detects a live
    // service before starting a duplicate.
    //
    // Every fragment appended to a RUN_COMMAND script must end in a newline.
    // Kotlin's trimIndent() also drops trailing blank lines, so returning a
    // bare raw string here glued `fi` to whatever came next and bash rejected
    // the whole script with "unexpected end of file from `if`".
    private fun guardScript(token: String) = """
        export POCKET_TOKEN='$token'
        if node -e "
          const t = process.env.POCKET_TOKEN;
          fetch('http://127.0.0.1:${BuildConfig.BRIDGE_PORT}/v1/health', { headers: { Authorization: 'Bearer ' + t }, signal: AbortSignal.timeout(1500) })
            .then(r => { if (r.ok) { console.log('DSH Pocket: local service already running'); process.exit(0); } process.exit(1); })
            .catch(() => process.exit(1));
        " 2>/dev/null; then exit 0; fi
        if node -e "fetch('http://127.0.0.1:${BuildConfig.BRIDGE_PORT}/v1/health', { signal: AbortSignal.timeout(1200) }).then(() => process.exit(0)).catch(() => process.exit(1))" 2>/dev/null; then
          echo 'DSH Pocket: port ${BuildConfig.BRIDGE_PORT} is held by a service this app cannot authenticate with.'
          echo 'Run in Termux: pkill -f server.mjs'
          exit 1
        fi
    """.trimIndent().asScriptBlock()

    fun bootstrap(context: Context, token: String) {
        val script = buildString {
            append(assetsScript(context, listOf("bridge", "termux")).asScriptBlock())
            val config = JSONObject().put("token", token).put("port", BuildConfig.BRIDGE_PORT).toString()
            val base64 = Base64.encodeToString(config.toByteArray(), Base64.NO_WRAP)
            append("printf '%s' '$base64' | base64 -d > \"\$ROOT/connection.json\"\n")
            append("if ! command -v node >/dev/null || ! command -v python >/dev/null; then\n echo '" + tr("请在 Termux 执行: pkg install nodejs-lts python") + "'; exit 1; fi\n")
            append(guardScript(token))
            append("export POCKET_HOME=\"\$ROOT\"\nexec node \"\$ROOT/bridge/server.mjs\"\n")
        }
        run(context, script, true, tr("DSH Pocket 本地服务"))
    }
    fun upgrade(context: Context, token: String) {
        val script = buildString {
            append("set -eu\nexport POCKET_UPDATE_TOKEN='$token'\nnode --input-type=module <<'POCKET_UPDATE'\n")
            append("""
                const headers = { Authorization: 'Bearer ' + process.env.POCKET_UPDATE_TOKEN };
                const health = await fetch('http://127.0.0.1:${BuildConfig.BRIDGE_PORT}/v1/health', {headers});
                if (!health.ok) throw new Error('无法验证现有本地服务');
                const data = await health.json();
                const chats = await fetch('http://127.0.0.1:${BuildConfig.BRIDGE_PORT}/v1/chats', {headers});
                if (!chats.ok || (await chats.json()).items.some(x => x.status === 'running')) throw new Error('请先停止正在运行的 DSH 任务再更新服务');
                if (!Number.isSafeInteger(data.pid) || data.pid < 2) throw new Error('无效服务进程');
                process.kill(data.pid, 'SIGTERM');
                let stopped = false;
                for (let i = 0; i < 60; i++) {
                  await new Promise(resolve => setTimeout(resolve, 100));
                  try { await fetch('http://127.0.0.1:${BuildConfig.BRIDGE_PORT}/v1/health', {headers}); }
                  catch { stopped = true; break; }
                }
                if (!stopped) throw new Error('现有服务未退出');
            """.trimIndent())
            append("\nPOCKET_UPDATE\nunset POCKET_UPDATE_TOKEN\n")
            append(assetsScript(context, listOf("bridge", "termux")).asScriptBlock())
            append("export POCKET_HOME=\"\$ROOT\"\nexec node \"\$ROOT/bridge/server.mjs\"\n")
        }
        run(context, script, true, tr("更新 DSH Pocket 本地服务"))
    }
    fun installEngine(context: Context) = run(context, buildString {
        append("printf '\\nDSH Pocket: starting Android engine installation\\n'\n")
        // Refresh the installer even when the existing local service is still
        // running from an earlier APK. No server restart is required.
        append(assetsScript(context, listOf("termux")))
        append("export POCKET_HOME=\"\$ROOT\"\n")
        append("exec bash \"\$ROOT/termux/install-dsh.sh\"\n")
    }, false, tr("安装 DSH Android 引擎"))
    fun installBase(context: Context) = run(context, "pkg install -y nodejs-lts python\n", false, tr("安装本地运行环境"))
    // Shared storage is only visible to Termux after this one-time grant; without
    // it /sdcard and /storage/emulated/0 do not exist, which is why the workspace
    // picker could not reach ordinary folders on the phone.
    fun setupStorage(context: Context) = run(context,
        "termux-setup-storage\n", false, tr("授权访问手机共享存储"))

    fun storageSettings(context: Context) {
        val declared = context.packageManager.getPackageInfo("com.termux", PackageManager.GET_PERMISSIONS)
            .requestedPermissions.orEmpty().contains("android.permission.MANAGE_EXTERNAL_STORAGE")
        val action = if (Build.VERSION.SDK_INT >= 30 && declared) Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION
            else Settings.ACTION_APPLICATION_DETAILS_SETTINGS
        context.startActivity(Intent(action, Uri.parse("package:com.termux")))
    }

    fun openTerminal(context: Context, cwd: String) {
        val quote = "'" + cwd.replace("'", "'\\''") + "'"
        run(context, "cd -- $quote || exit\nexec \"\$PREFIX/bin/bash\" -l\n", false, tr("项目终端"))
    }
    fun openApp(context: Context) {
        val intent = context.packageManager.getLaunchIntentForPackage("com.termux") ?: error(tr("尚未安装 Termux"))
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

class TermuxResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val result = intent.getBundleExtra("result") ?: return
        val error = result.getString("errmsg").orEmpty()
        val output = result.getString("stdout").orEmpty() + result.getString("stderr").orEmpty()
        val exitCode = if (result.containsKey("exitCode")) result.getInt("exitCode").toString() else tr("未知")
        TermuxConnection.lastResult.value = tr("Termux 任务结束 · 退出码 ") + exitCode + "\n" +
            (if (error.isNotBlank()) error else output).takeLast(12000)
    }
}

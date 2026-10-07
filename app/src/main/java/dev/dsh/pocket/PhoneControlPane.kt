package dev.dsh.pocket

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@Composable
fun PhoneControlPane() {
    val context = LocalContext.current
    val connected by PhoneControl.connected.collectAsState()
    // The binding can change while this pane is on screen (or before it opens),
    // so check the socket whenever it appears rather than trusting a callback.
    LaunchedEffect(Unit) { PhoneControl.refresh() }
    var expanded by remember { mutableStateOf(false) }
    var allowed by remember { mutableStateOf(PhoneControl.allowed) }
    val apps = remember {
        context.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .distinctBy { it.activityInfo.packageName }.map { it.activityInfo.packageName to it.loadLabel(context.packageManager).toString() }.sortedBy { it.second }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("手机控制 · 免 root", style = MaterialTheme.typography.titleMedium)
        val listed = PhoneControl.enabledInSettings(context)
        Text(when {
            connected -> tr("无障碍服务已连接")
            // Saying "not enabled" here sends people to a settings page that already
            // says it is on, which is exactly the confusing state this names.
            listed -> tr("系统里已开启，但服务没有在运行。请到无障碍设置里把它关闭再打开一次。")
            else -> tr("开启无障碍服务后，可以读取和操作允许的应用。")
        })
        if (!connected) OutlinedButton(onClick = { PhoneControl.openSettings(context) }) { Text(if (listed) tr("重新开启无障碍服务") else tr("开启无障碍服务")) }
        TextButton(onClick = { expanded = !expanded }) { Text("允许的应用 · ${allowed.size} ▾") }
        if (expanded) Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState())) {
            apps.forEach { (pkg, label) ->
                Row(Modifier.fillMaxWidth()) {
                    Checkbox(pkg in allowed, onCheckedChange = { PhoneControl.allow(context, pkg, it); allowed = PhoneControl.allowed })
                    Text(label, Modifier.padding(top = 14.dp))
                }
            }
        }
        Text("聊天中开启“允许手机控制”后生效，停止任务会立即撤销操作权限。截图仅用于支持图片的模型。", style = MaterialTheme.typography.bodySmall)
    }
}

/** Sits with the model and reasoning controls rather than taking a row of its own. */
@Composable
fun PhoneChatControl(running: Boolean) {
    val enabled by PhoneControl.enabled.collectAsState()
    val connected by PhoneControl.connected.collectAsState()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(tr("手机控制"), style = MaterialTheme.typography.bodySmall, color = Color(0xFF64748B))
        Switch(checked = enabled, onCheckedChange = { PhoneControl.toggle(it) },
            enabled = enabled || (connected && !running), modifier = Modifier.padding(start = 6.dp))
    }
}

package dev.dsh.pocket

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID

/** The capability is memory-only: process death, Stop, or switching it off revokes it. */
object PhoneControl {
    val inputGuard = PhoneInputGuard()
    val connected = MutableStateFlow(false)
    val enabled = MutableStateFlow(false)
    @Volatile private var token = ""
    @Volatile private var expires = 0L
    @Volatile var allowed: Set<String> = emptySet()
        private set
    fun load(context: Context) {
        allowed = context.getSharedPreferences("phone-control", Context.MODE_PRIVATE).getStringSet("apps", emptySet())!!.toSet()
    }
    fun allow(context: Context, pkg: String, value: Boolean) {
        allowed = if (value) allowed + pkg else allowed - pkg
        context.getSharedPreferences("phone-control", Context.MODE_PRIVATE).edit().putStringSet("apps", allowed).apply()
    }
    fun toggle(value: Boolean) { enabled.value = value; revoke() }
    /** Settings can list the service as on while it is not actually bound. */
    fun enabledInSettings(context: Context): Boolean {
        val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? android.view.accessibility.AccessibilityManager ?: return false
        return manager.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { it.resolveInfo.serviceInfo.packageName == context.packageName &&
                it.resolveInfo.serviceInfo.name.endsWith("PocketAccessibilityService") }
    }
    fun revoke() { token = ""; expires = 0 }
    fun grant(): JSONObject? {
        if (!enabled.value) return null
        check(connected.value) { "请先开启 DSH Pocket 的无障碍服务" }
        check(allowed.isNotEmpty()) { "请先选择允许控制的应用" }
        token = UUID.randomUUID().toString() + UUID.randomUUID().toString()
        expires = SystemClock.elapsedRealtime() + 30 * 60 * 1000
        spent = 0; lastAction = ""; repeats = 0
        inputGuard.reset()
        return JSONObject().put("token", token).put("port", BuildConfig.BRIDGE_PORT + 1)
    }
    fun authorized(candidate: String) = enabled.value && candidate.isNotEmpty() && candidate == token && SystemClock.elapsedRealtime() < expires
    fun openSettings(context: Context) = context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))

    /**
     * A request gets a fixed number of actions, and the same action repeated three
     * times in a row ends it. Without this a model that cannot see any progress
     * retries until the user stops it, which is how a five-minute turn happens.
     */
    private val budget = 45
    @Volatile private var spent = 0
    @Volatile private var lastAction = ""
    @Volatile private var repeats = 0
    fun spend(signature: String): String? {
        if (++spent > budget) return "Phone control has used its $budget actions for this request; stop and report what you have so far."
        if (signature == lastAction && ++repeats >= 2) {
            return "The same action was tried three times in a row; it is not working. Stop and tell the user what is blocking it."
        }
        if (signature != lastAction) { lastAction = signature; repeats = 0 }
        return null
    }

    /** The run is over, successfully or not: bring DSH back so the result is visible. */
    fun returnToApp(context: Context) {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        try { context.startActivity(intent) } catch (_: Exception) { /* The system may refuse. */ }
    }

    private const val CHANNEL = "phone-control"
    private const val NOTICE = 41
    @SuppressLint("MissingPermission")
    fun notifyWorking(context: Context, text: String) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, tr("手机控制"), NotificationManager.IMPORTANCE_LOW))
        }
        if (!androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val notice = androidx.core.app.NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(context.applicationInfo.icon)
            .setContentTitle(tr("DSH 正在操作手机"))
            .setContentText(text.ifBlank { tr("任务结束后会自动回到 DSH") })
            .setOngoing(true).setSilent(true).setOnlyAlertOnce(true).build()
        try { androidx.core.app.NotificationManagerCompat.from(context).notify(NOTICE, notice) } catch (_: Exception) { }
    }
    fun clearWorking(context: Context) {
        try { androidx.core.app.NotificationManagerCompat.from(context).cancel(NOTICE) } catch (_: Exception) { }
    }
    /**
     * What matters to the user is whether the service is actually answering, and
     * the bound-service callback is not a dependable way to know: Android can
     * rebind the same process, and a force-stop leaves the service enabled in
     * Settings but silent. Ask the socket instead.
     */
    fun reachable(): Boolean = try {
        java.net.Socket().use { it.connect(java.net.InetSocketAddress("127.0.0.1", BuildConfig.BRIDGE_PORT + 1), 700) }
        true
    } catch (_: Exception) { false }
    // A socket connect on the main thread throws NetworkOnMainThreadException, and
    // swallowing that made the check report "not connected" for a service that was
    // answering every request.
    suspend fun refresh() = withContext(Dispatchers.IO) { connected.value = reachable() }
}

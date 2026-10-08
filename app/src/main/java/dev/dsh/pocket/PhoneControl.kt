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
    /** The Shizuku-only endpoint is serving, which happens when accessibility is off. */
    val standalone = MutableStateFlow(false)
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
     * A request gets a generous but finite number of actions. The repeat rule only
     * covers actions that change something: observing twice in a row is how a model
     * waits for a screen to settle, and counting that as a runaway cut real runs short.
     * A model that cannot make progress still gets stopped before the user has to.
     */
    private val budget = 120
    private val readOnly = setOf("observe", "screenshot", "state")
    @Volatile private var spent = 0
    @Volatile private var lastAction = ""
    @Volatile private var repeats = 0
    fun spend(action: String, args: JSONObject): String? {
        if (++spent > budget) return "Phone control has used its $budget actions for this request; stop and report what you have so far."
        val signature = action + " " + args.toString()
        if (action !in readOnly && signature == lastAction && ++repeats >= 4) {
            return "The same action was tried four times in a row with nothing changing; stop and tell the user what is blocking it."
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

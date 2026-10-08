package dev.dsh.pocket

import android.content.ComponentName
import android.content.pm.ApplicationInfo
import android.util.Log
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import kotlinx.coroutines.flow.MutableStateFlow
import rikka.shizuku.Shizuku
import java.io.File

/**
 * Screenshots through Shizuku, when the user runs it.
 *
 * AccessibilityService.takeScreenshot refuses while more than one window is on screen —
 * including our own work notice — and some apps cannot be captured at all. The shell
 * user has neither limit, so this path is preferred and accessibility stays the
 * fallback.
 *
 * The binder arrives from rikka.shizuku.ShizukuProvider, declared in the manifest; the
 * listeners below turn "Shizuku started or died" into state the UI can show without a
 * restart. Everything here is optional: a missing binder, a denied permission, a failed
 * bind or a failed command all end in null and the caller falls back.
 */
object ShizukuShot {
    private const val REQUEST_CODE = 4210
    @Volatile private var watching = false
    @Volatile private var service: IShotService? = null
    @Volatile private var appContext: Context? = null

    /** Shizuku is running and its binder is reachable. */
    val running = MutableStateFlow(false)

    /** This app holds Shizuku's permission. */
    val granted = MutableStateFlow(false)

    fun available(): Boolean = try {
        Shizuku.pingBinder() && Shizuku.getVersion() >= 11
    } catch (_: Throwable) { false }

    fun refresh() {
        running.value = available()
        granted.value = running.value && try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Throwable) { false }
        if (granted.value) bind() else service = null
    }

    /** Registers the listeners once and reports the state they describe. */
    fun observe(context: Context) {
        appContext = context.applicationContext
        if (!watching) {
            watching = true
            try {
                // Sticky: fires immediately when the binder is already there.
                Shizuku.addBinderReceivedListenerSticky { running.value = true; refresh() }
                Shizuku.addBinderDeadListener { running.value = false; granted.value = false; service = null }
                Shizuku.addRequestPermissionResultListener { _, _ -> refresh() }
            } catch (_: Throwable) { watching = false }
        }
        refresh()
    }

    fun request(context: Context) {
        appContext = context.applicationContext
        if (!available()) return
        try {
            if (!granted.value) Shizuku.requestPermission(REQUEST_CODE)
        } catch (_: Throwable) { }
    }

    private fun bind() {
        if (service != null) return
        val context = appContext ?: return
        try {
            // Shizuku refuses a user service whose debug flag disagrees with the app's
            // own: a debug build must ask for a debuggable service.
            val debuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
            val args = Shizuku.UserServiceArgs(ComponentName(context, ShotService::class.java))
                .daemon(false).processNameSuffix("shot").debuggable(debuggable).version(IShotService.VERSION)
            Shizuku.bindUserService(args, object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                    service = IShotService.Stub.asInterface(binder)
                }
                override fun onServiceDisconnected(name: ComponentName?) { service = null }
            })
        } catch (e: Throwable) {
            // Silent here once cost an afternoon: a refused bind looked like a dead device.
            Log.w("DSH Pocket", "Shizuku user service bind failed", e)
            service = null
        }
    }

    /**
     * Captures into this app's own external files directory: the shell user can write
     * there, and reading the file back avoids pushing megabytes through binder.
     */
    fun capture(context: Context): ByteArray? = try {
        val target = File(context.getExternalFilesDir(null), "pocket-shot.png")
        target.parentFile?.mkdirs()
        val api = service
        if (api == null || !api.screencap(target.absolutePath) || !target.isFile) null
        else target.readBytes().takeIf { it.isNotEmpty() }
    } catch (_: Throwable) { null } finally {
        try { File(context.getExternalFilesDir(null), "pocket-shot.png").delete() } catch (_: Throwable) { }
    }
}

package dev.dsh.pocket

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import org.json.JSONObject

/**
 * Hosts the phone endpoint when the accessibility service is not running, driving the
 * device through Shizuku instead.
 *
 * A foreground service is what keeps this app alive while the model works in other
 * apps: the system keeps an accessibility service alive, and without one this process
 * would be a candidate for reclamation in the middle of a task. The notification is
 * the price, and on Android 13+ without the notification permission it is simply not
 * shown while the service still runs.
 */
class PhoneStandaloneService : Service() {
    private val server by lazy {
        PhoneServer(
            dispatch = ::dispatch,
            // Without accessibility there is no overlay to show, and the notification
            // already says the app is working.
            onNotice = { },
            onFinished = { PhoneControl.returnToApp(this) },
            onState = { PhoneControl.standalone.value = it },
        )
    }

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(NotificationChannel(CHANNEL, tr("手机控制"), NotificationManager.IMPORTANCE_LOW))
        }
        startForeground(NOTICE_ID, notification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ShizukuShot.observe(this)
        if (!server.start()) {
            // The port belongs to the accessibility service, which is the better host.
            PhoneControl.standalone.value = false
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        server.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun dispatch(action: String, args: JSONObject, finish: (JSONObject) -> Unit) {
        try {
            if (!ShizukuShot.granted.value) error("Shizuku is not authorised; the app cannot reach the device without accessibility")
            when (action) {
                "observe" -> finish(ShizukuPhone.observe(this))
                "tap" -> finish(ShizukuPhone.tap(this, args))
                "type" -> finish(ShizukuPhone.type(this, args))
                "scroll" -> finish(ShizukuPhone.scroll(this, args))
                "key" -> finish(ShizukuPhone.key(args))
                "launch" -> finish(ShizukuPhone.launch(args))
                "screenshot" -> finish(screenshot())
                else -> error("Unknown action: " + action)
            }
        } catch (e: Exception) { finish(JSONObject().put("ok", false).put("error", e.message)) }
    }

    private fun screenshot(): JSONObject {
        val bytes = ShizukuShot.capture(this)
            ?: return JSONObject().put("ok", false).put("error", "The shell user could not capture the screen")
        val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: return JSONObject().put("ok", false).put("error", "The screenshot could not be decoded")
        return PhoneFrame.encode(bitmap, "")
    }

    private fun notification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(this)
        return builder
            .setSmallIcon(applicationInfo.icon)
            .setContentTitle(tr("DSH 手机控制"))
            .setContentText(tr("任务结束后会自动回到 DSH"))
            .setOngoing(true).setOnlyAlertOnce(true).build()
    }

    companion object {
        private const val CHANNEL = "phone-standalone"
        private const val NOTICE_ID = 42

        fun start(context: android.content.Context) {
            context.startForegroundService(Intent(context, PhoneStandaloneService::class.java))
        }

        fun stop(context: android.content.Context) {
            context.stopService(Intent(context, PhoneStandaloneService::class.java))
        }
    }
}

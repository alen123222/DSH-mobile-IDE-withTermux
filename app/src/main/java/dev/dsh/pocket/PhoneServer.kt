package dev.dsh.pocket

import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The loopback endpoint the bridge talks to.
 *
 * It owns everything that does not depend on how an action is carried out: the socket,
 * request parsing, authentication, the per-request budget, the work notice and the
 * deadline. Performing an action is delegated, so the same endpoint can be hosted by
 * the accessibility service or, when accessibility is unavailable, by a service that
 * drives the device through Shizuku.
 */
class PhoneServer(
    private val dispatch: (String, JSONObject, (JSONObject) -> Unit) -> Unit,
    private val onNotice: (String?) -> Unit,
    private val onFinished: () -> Unit,
    private val onState: (Boolean) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var listener: ServerSocket? = null

    val running: Boolean get() = listener?.isClosed == false

    /** Binds the port and serves until [stop]. Returns false when the port is taken. */
    fun start(): Boolean {
        synchronized(this) {
            if (running) return true
            val bound = try {
                ServerSocket(BuildConfig.BRIDGE_PORT + 1, 4, InetAddress.getByName("127.0.0.1"))
            } catch (e: Exception) {
                Log.w(TAG, "Phone endpoint could not bind", e)
                onState(false)
                return false
            }
            listener = bound
            onState(true)
            Thread({
                try {
                    while (!bound.isClosed) try { bound.accept().use(::serve) } catch (_: Exception) { }
                } finally {
                    synchronized(this) {
                        if (listener === bound) { listener = null; onState(false) }
                    }
                }
            }, "pocket-phone").start()
            return true
        }
    }

    fun stop() {
        synchronized(this) {
            try { listener?.close() } catch (_: Exception) { }
            listener = null
            onState(false)
        }
    }

    private fun serve(socket: Socket) {
        socket.soTimeout = 3000
        var status = 200
        val result = try {
            val input = socket.getInputStream()
            check(line(input) == "POST /phone HTTP/1.1") { "Unsupported request" }
            val headers = mutableMapOf<String, String>()
            var total = 0
            while (true) {
                val value = line(input); total += value.length
                check(total <= 16384) { "Headers too large" }
                if (value.isEmpty()) break
                val split = value.indexOf(':'); check(split > 0)
                headers[value.substring(0, split).lowercase()] = value.substring(split + 1).trim()
            }
            val credential = headers["authorization"].orEmpty().removePrefix("Bearer ")
            if (!PhoneControl.authorized(credential)) { status = 403; error("Phone control is disabled or expired") }
            check(!headers.containsKey("transfer-encoding"))
            val length = headers["content-length"]?.toIntOrNull() ?: 0
            check(length in 2..32768) { "Invalid body size" }
            val bytes = ByteArray(length)
            var offset = 0
            while (offset < length) { val count = input.read(bytes, offset, length - offset); check(count > 0); offset += count }
            execute(JSONObject(String(bytes, Charsets.UTF_8)), credential)
        } catch (e: Exception) { JSONObject().put("ok", false).put("error", e.message ?: "Phone operation failed") }
        val body = result.toString().toByteArray(Charsets.UTF_8)
        socket.getOutputStream().apply {
            write(("HTTP/1.1 " + status + " OK\r\nContent-Type: application/json\r\nContent-Length: " + body.size + "\r\nConnection: close\r\n\r\n").toByteArray())
            write(body); flush()
        }
    }

    private fun line(input: java.io.InputStream): String {
        val builder = StringBuilder()
        while (true) {
            val next = input.read()
            check(next >= 0) { "Unexpected end of request" }
            if (next == '\n'.code) break
            if (next != '\r'.code) builder.append(next.toChar())
        }
        return builder.toString()
    }

    /** One action at a time, with a hard deadline. */
    private fun execute(args: JSONObject, credential: String): JSONObject {
        val done = CountDownLatch(1)
        var result = JSONObject().put("ok", false).put("error", "Operation timed out")
        var attemptedInput = false
        val finish: (JSONObject) -> Unit = {
            if (attemptedInput) {
                PhoneControl.inputGuard.inputResult(it.optBoolean("ok"))
                if (!it.optBoolean("ok")) it.put("recovery", PhoneControl.inputGuard.blocked("type"))
            }
            if (it.optBoolean("ok") && (it.has("imageBase64") || it.optInt("count") > 0)) {
                PhoneControl.inputGuard.observed()
            }
            result = it; done.countDown()
        }
        main.post {
            try {
                check(PhoneControl.authorized(credential)) { "Control revoked" }
                val action = args.getString("action")
                PhoneControl.inputGuard.blocked(action)?.let { message ->
                    finish(JSONObject().put("ok", false).put("error", message)); return@post
                }
                if (action != "finished") onNotice(tr("DSH 正在工作") + " · " + action)
                (if (action == "finished") null else PhoneControl.spend(action, args)).let { stop ->
                    if (stop != null) { finish(JSONObject().put("ok", false).put("error", stop)); return@post }
                }
                when (action) {
                    // The bridge says the turn is over: the only reliable end signal,
                    // because a reclaimed app cannot be trusted to notice the transition.
                    "finished" -> { onFinished(); finish(JSONObject().put("ok", true)) }
                    "state" -> finish(JSONObject().put("ok", true).put("apps", JSONArray(PhoneControl.allowed.toList()))
                        .put("shizuku", ShizukuShot.granted.value)
                        .put("preciseScreenshot", ShizukuShot.granted.value))
                    else -> { attemptedInput = action == "type"; dispatch(action, args, finish) }
                }
            } catch (e: Exception) { finish(JSONObject().put("ok", false).put("error", e.message)) }
        }
        if (!done.await(8, TimeUnit.SECONDS)) { main.removeCallbacksAndMessages(null); PhoneControl.revoke() }
        return result
    }

    private companion object {
        const val TAG = "DSH Pocket"
    }
}

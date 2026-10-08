package dev.dsh.pocket

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Phone control over the accessibility tree.
 *
 * The contract mirrors the simplest working Harness-to-Android bridges: [observe]
 * returns a flat list of nodes with absolute bounds, and actions address a target
 * by text/description or by coordinates that are resolved when the action runs.
 * Nothing is addressed by an index into an earlier observation, so a screen that
 * keeps repainting cannot invalidate an action, and the model never has to observe
 * again merely to retry the same tap. Every action is bounded: one at a time, on
 * the main thread, with an overall deadline after which control is revoked.
 */
class PocketAccessibilityService : AccessibilityService() {
    companion object {
        /** Non-null exactly while the service is bound; the app uses it to retract the notice. */
        @Volatile var instance: PocketAccessibilityService? = null
    }
    private val main = Handler(Looper.getMainLooper())
    private val revision = AtomicLong(0)
    @Volatile private var server: ServerSocket? = null

    override fun onServiceConnected() {
        PhoneControl.load(this)
        if (Build.VERSION.SDK_INT >= 33) {
            serviceInfo = serviceInfo.apply {
                flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_INPUT_METHOD_EDITOR
            }
        }
        // Android can call this again for a service that is already bound. Binding
        // the port a second time fails, and the failure used to clear the flag while
        // the first socket kept serving, so the UI reported "not connected" for a
        // service that was plainly answering. Only the thread that owns the socket
        // may publish its state, and only while it is still the current one.
        synchronized(this) {
            val current = server
            if (current != null && !current.isClosed) { PhoneControl.connected.value = true; return }
            val listener = try {
                ServerSocket(BuildConfig.BRIDGE_PORT + 1, 4, InetAddress.getByName("127.0.0.1"))
            } catch (_: Exception) {
                PhoneControl.connected.value = false
                return
            }
            server = listener
            instance = this
            PhoneControl.connected.value = true
            Thread({
                try { while (!listener.isClosed) try { listener.accept().use(::serve) } catch (_: Exception) { } }
                finally {
                    synchronized(this) {
                        if (server === listener) { server = null; PhoneControl.connected.value = false }
                    }
                }
            }, "pocket-phone").start()
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { revision.incrementAndGet() }
    override fun onInterrupt() { PhoneControl.revoke() }
    override fun onDestroy() {
        PhoneControl.revoke(); PhoneControl.connected.value = false
        if (instance === this) instance = null
        hidePill()
        server?.close(); super.onDestroy()
    }

    // ---- always-visible work notice ------------------------------------------

    private var pill: android.view.View? = null
    // A stop or a killed engine can end a run without any turn/end, so the notice
    // also expires on its own rather than waiting to be taken down.
    private val pillExpiry = Runnable { hidePill() }

    /** Lets the app take the notice down the moment the user stops the run. */
    fun hideNotice() { hidePill() }

    /**
     * An accessibility overlay rides on top of whatever app is in front, needs no
     * permission of its own, and is not touchable, so the user can see that DSH is
     * working without opening the shade and without the notice interfering.
     */
    private fun showPill(text: String) {
        main.post {
            main.removeCallbacks(pillExpiry)
            main.postDelayed(pillExpiry, 300_000)
            try {
                val manager = getSystemService(android.view.WindowManager::class.java) ?: return@post
                val existing = pill as? android.widget.TextView
                if (existing != null) { existing.text = text; return@post }
                val view = android.widget.TextView(this).apply {
                    this.text = text
                    setTextColor(android.graphics.Color.WHITE)
                    textSize = 13f
                    setPadding(32, 14, 32, 14)
                    setBackgroundColor(0xE61F2937.toInt())
                }
                val params = android.view.WindowManager.LayoutParams(
                    android.view.WindowManager.LayoutParams.WRAP_CONTENT,
                    android.view.WindowManager.LayoutParams.WRAP_CONTENT,
                    android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    android.graphics.PixelFormat.TRANSLUCENT)
                params.gravity = android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL
                params.y = 28
                manager.addView(view, params)
                pill = view
            } catch (_: Exception) { /* A ROM that refuses overlays loses only the notice. */ }
        }
    }

    private fun hidePill() {
        main.post {
            main.removeCallbacks(pillExpiry)
            val view = pill ?: return@post
            pill = null
            try { getSystemService(android.view.WindowManager::class.java)?.removeView(view) } catch (_: Exception) { }
        }
    }

    // ---- transport -----------------------------------------------------------

    private fun line(input: java.io.InputStream): String {
        val out = ByteArrayOutputStream()
        while (out.size() < 8192) {
            val c = input.read(); check(c >= 0) { "Incomplete request" }
            if (c == 10) return out.toString("US-ASCII").trimEnd('\r')
            out.write(c)
        }
        error("Header too long")
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
            write("HTTP/1.1 $status OK\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
            write(body); flush()
        }
    }

    /** One action at a time, on the main thread, with a hard deadline. */
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
                if (action != "finished") showPill(tr("DSH 正在工作") + " · " + action)
                (if (action == "finished") null else PhoneControl.spend(action + " " + args.toString())).let { stop ->
                    if (stop != null) { finish(JSONObject().put("ok", false).put("error", stop)); return@post }
                }
                when (action) {
                    // The bridge says the turn is over: this is the only reliable end
                    // signal, because the app may have been reclaimed while it was in
                    // the background and cannot be trusted to notice the transition.
                    "finished" -> {
                        // Only pull the app forward if this run really drove the phone;
                        // a turn that never touched it should not steal focus back.
                        val wasDriving = pill != null
                        hidePill()
                        if (wasDriving) PhoneControl.returnToApp(this)
                        finish(JSONObject().put("ok", true))
                    }
                    "state" -> finish(JSONObject().put("ok", true).put("apps", JSONArray(PhoneControl.allowed.toList())))
                    "launch" -> finish(launch(args))
                    "screenshot" -> capture(credential, finish)
                    else -> {
                        val root = rootInActiveWindow ?: error("No active window; unlock the device")
                        val pkg = root.packageName?.toString().orEmpty()
                        check(pkg in PhoneControl.allowed) { "Foreground app is not allowed: $pkg" }
                        when (action) {
                            "observe" -> finish(observe(root, pkg))
                            "tap" -> tap(root, args, finish)
                            "type" -> { attemptedInput = true; finish(type(root, args)) }
                            "scroll" -> scroll(args, finish)
                            "key" -> {
                                val key = args.getString("key")
                                check(key == "back" || key == "home") { "Only back/home are supported" }
                                val ok = performGlobalAction(if (key == "back") GLOBAL_ACTION_BACK else GLOBAL_ACTION_HOME)
                                finish(JSONObject().put("ok", ok).apply { if (!ok) put("error", "The system refused the action") })
                            }
                            else -> error("Unknown action: $action")
                        }
                    }
                }
            } catch (e: Exception) { finish(JSONObject().put("ok", false).put("error", e.message)) }
        }
        if (!done.await(8, TimeUnit.SECONDS)) { main.removeCallbacksAndMessages(null); PhoneControl.revoke() }
        return result
    }

    // ---- actions -------------------------------------------------------------

    private fun launch(args: JSONObject): JSONObject {
        val pkg = args.getString("package")
        check(pkg in PhoneControl.allowed) { "App is not allowed: $pkg" }
        val intent = packageManager.getLaunchIntentForPackage(pkg) ?: error("App has no launcher")
        startActivity(intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        revision.incrementAndGet()
        return JSONObject().put("ok", true)
    }

    /**
     * Flat list of what a person could act on. Bounds are absolute screen pixels,
     * which is what a tap accepts, so no identifier has to survive a repaint.
     */
    private fun observe(root: AccessibilityNodeInfo, pkg: String): JSONObject {
        val items = JSONArray()
        var visited = 0
        var truncated = false
        fun walk(node: AccessibilityNodeInfo, depth: Int) {
            if (visited > 1500 || depth > 40 || items.length() >= 250) { truncated = true; return }
            visited++
            val labelled = node.text != null || node.contentDescription != null
            val actionable = node.isClickable || node.isEditable || node.isScrollable || node.isCheckable
            // isVisibleToUser is false for parts of some apps while they are on
            // screen, so anything with a label counts as visible; dropping the flag
            // outright would fill the list with off-screen debris.
            if ((labelled || actionable) && (node.isVisibleToUser || labelled)) {
                val bounds = Rect(); node.getBoundsInScreen(bounds)
                if (bounds.width() > 0 && bounds.height() > 0) {
                    items.put(JSONObject()
                        .put("text", if (node.isPassword) "[password]" else node.text?.toString()?.take(300).orEmpty())
                        .put("desc", if (node.isPassword) "" else node.contentDescription?.toString()?.take(200).orEmpty())
                        .put("cls", node.className?.toString()?.substringAfterLast('.').orEmpty())
                        .put("x", bounds.left).put("y", bounds.top).put("w", bounds.width()).put("h", bounds.height())
                        .put("clickable", node.isClickable).put("input", node.isEditable)
                        .put("checked", node.isChecked).put("selected", node.isSelected)
                        .put("scrollable", node.isScrollable).put("depth", depth))
                }
            }
            for (i in 0 until node.childCount) { val child = node.getChild(i) ?: continue; walk(child, depth + 1) }
        }
        walk(root, 0)
        val observed = JSONObject().put("ok", true).put("package", pkg)
            .put("count", items.length()).put("truncated", truncated).put("nodes", items)
            .put("input", inputStatus(pkg))
        // A bare empty list reads as "nothing is on screen". Apps that render
        // outside the accessibility tree (WeChat among them) really do expose
        // nothing, and the model needs to hear that rather than retry forever.
        if (items.length() == 0) observed.put("hint",
            "No accessible controls in $pkg. Either the screen is still drawing, or this app does not expose its interface to accessibility; retrying is only worth it once, after a moment.")
        return observed
    }

    private fun inputStatus(pkg: String): JSONObject {
        val available = Build.VERSION.SDK_INT >= 33 && inputMethod?.currentInputStarted == true &&
            inputMethod?.currentInputEditorInfo?.packageName == pkg && inputMethod?.currentInputConnection != null
        return JSONObject().put("nativeConnectionAvailable", available)
            .put("instruction", "Enter the whole text with phone_type, including Chinese. It supports native input even when accessibility nodes are empty. Never type by tapping keyboard letters or using shell/clipboard commands. If no field is active, tap the app's text field first.")
    }

    private fun rejectKeyboardTap(x: Float, y: Float) {
        val keyboard = windows.any { window ->
            if (window.type != AccessibilityWindowInfo.TYPE_INPUT_METHOD) false
            else Rect().also { window.getBoundsInScreen(it) }.contains(x.toInt(), y.toInt())
        }
        if (keyboard) {
            PhoneControl.inputGuard.inputResult(false)
            error("Soft keyboard taps are disabled. Observe again, then use phone_type with the complete text (Chinese supported). Do not retry keyboard taps.")
        }
    }

    /** The tap lands where the target is *now*, not where it was when observed. */
    private fun tap(root: AccessibilityNodeInfo, args: JSONObject, finish: (JSONObject) -> Unit) {
        val metrics = resources.displayMetrics
        require(args.has("fx") == args.has("fy")) { "Supply both fx and fy" }
        require(args.has("x") == args.has("y")) { "Supply both x and y" }
        require(!(args.has("fx") && args.has("x"))) { "Use either fx/fy or x/y, not both" }
        for ((key, extent) in listOf("fx" to metrics.widthPixels, "fy" to metrics.heightPixels,
            "x" to metrics.widthPixels, "y" to metrics.heightPixels)) {
            if (args.has(key)) phoneCoordinate(args.getDouble(key), extent, key.startsWith("f"))
        }
        val text = args.optString("text").takeIf { it.isNotBlank() }
        val desc = args.optString("desc").takeIf { it.isNotBlank() }
        val labelled = if (text != null || desc != null) find(root, text, desc) else null
        if (labelled != null) {
            val match = nearestClickable(labelled) ?: labelled
            val targetBounds = Rect().also { match.getBoundsInScreen(it) }
            rejectKeyboardTap(targetBounds.exactCenterX(), targetBounds.exactCenterY())
            if (match.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                revision.incrementAndGet()
                finish(JSONObject().put("ok", true).put("found", true).put("method", "text"))
                return
            }
        } else if (!args.has("x") && !args.has("fx")) {
            finish(JSONObject().put("ok", false).put("found", false)
                .put("error", "Nothing on screen matches that text or description; observe again"))
            return
        }
        val bounds = Rect()
        // A label with no clickable ancestor is still worth pressing at its centre.
        val fallback = labelled?.let { nearestClickable(it) ?: it }
        if (fallback != null) fallback.getBoundsInScreen(bounds)
        val x = when {
            args.has("fx") -> phoneCoordinate(args.getDouble("fx"), metrics.widthPixels, true)
            args.has("x") -> args.getDouble("x").toFloat()
            fallback != null -> bounds.exactCenterX()
            else -> { finish(JSONObject().put("ok", false).put("error", "Pass text, desc, x/y or fx/fy")); return }
        }
        val y = when {
            args.has("fy") -> phoneCoordinate(args.getDouble("fy"), metrics.heightPixels, true)
            args.has("y") -> args.getDouble("y").toFloat()
            fallback != null -> bounds.exactCenterY()
            else -> { finish(JSONObject().put("ok", false).put("error", "Pass text, desc, x/y or fx/fy")); return }
        }
        rejectKeyboardTap(x, y)
        gesture(Path().apply { moveTo(x, y) }, 60, finish)
    }

    /** Types into whatever currently holds input focus, which is how a person types. */
    private fun type(root: AccessibilityNodeInfo, args: JSONObject): JSONObject {
        val value = args.getString("text")
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        check(focused?.isPassword != true) { "Password fields are not supported" }
        val target = focused?.takeIf { it.isEditable } ?: firstEditable(root)
            ?: return typeThroughInputConnection(root.packageName?.toString().orEmpty(), value)
        check(!target.isPassword) { "Password fields are not supported" }
        check(target.isEditable) { "The focused control is not editable" }
        val ok = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,
            Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) })
        if (!ok) return typeThroughInputConnection(root.packageName?.toString().orEmpty(), value)
        revision.incrementAndGet()
        return JSONObject().put("ok", ok).apply {
            if (!ok) put("error", "The app refused the text")
            else put("verificationRequired", true).put("hint", "Observe the field or take a screenshot to verify the text before sending; ACTION_SET_TEXT acceptance alone does not confirm its contents.")
        }
    }

    /** Android 13+ exposes the active editor independently of the accessibility tree. */
    private fun typeThroughInputConnection(pkg: String, value: String): JSONObject {
        if (Build.VERSION.SDK_INT < 33) return JSONObject().put("ok", false)
            .put("error", "No accessible editable field. Native input fallback requires Android 13 or newer.")
        val method = inputMethod
        val editor = method?.currentInputEditorInfo
        val connection = method?.currentInputConnection
        if (method?.currentInputStarted != true || editor == null || connection == null || editor.packageName != pkg) {
            return JSONObject().put("ok", false).put("error", "No active input connection for the foreground app; tap its input field and observe again")
        }
        val kind = editor.inputType and android.text.InputType.TYPE_MASK_CLASS
        val variation = editor.inputType and android.text.InputType.TYPE_MASK_VARIATION
        check(!(kind == android.text.InputType.TYPE_CLASS_TEXT && variation in setOf(
            android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD,
            android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            android.text.InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)) &&
            !(kind == android.text.InputType.TYPE_CLASS_NUMBER && variation == android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD)) {
            "Password fields are not supported"
        }
        check(kind != android.text.InputType.TYPE_NULL) { "The editor does not accept text" }
        // Match ACTION_SET_TEXT replacement semantics, rather than appending on retries.
        connection.performContextMenuAction(android.R.id.selectAll)
        connection.commitText(value, 1, null)
        revision.incrementAndGet()
        return JSONObject().put("ok", true).put("method", "inputConnection").put("verificationRequired", true)
            .put("hint", "Input was dispatched, not verified. Observe or screenshot to confirm the actual field contents before sending. Do not blindly repeat input.")
    }

    private fun scroll(args: JSONObject, finish: (JSONObject) -> Unit) {
        val metrics = resources.displayMetrics
        val cx = metrics.widthPixels / 2f
        val cy = metrics.heightPixels / 2f
        val path = Path()
        when (args.optString("direction", "down")) {
            // "up" means "bring the content above into view", so the finger moves down.
            "up" -> { path.moveTo(cx, metrics.heightPixels * 0.35f); path.lineTo(cx, metrics.heightPixels * 0.65f) }
            "down" -> { path.moveTo(cx, metrics.heightPixels * 0.65f); path.lineTo(cx, metrics.heightPixels * 0.35f) }
            "left" -> { path.moveTo(metrics.widthPixels * 0.7f, cy); path.lineTo(metrics.widthPixels * 0.3f, cy) }
            "right" -> { path.moveTo(metrics.widthPixels * 0.3f, cy); path.lineTo(metrics.widthPixels * 0.7f, cy) }
            else -> { finish(JSONObject().put("ok", false).put("error", "direction must be up/down/left/right")); return }
        }
        gesture(path, 240, finish)
    }

    private fun gesture(path: Path, duration: Long, finish: (JSONObject) -> Unit) {
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration)).build()
        val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(description: GestureDescription?) {
                revision.incrementAndGet(); finish(JSONObject().put("ok", true))
            }
            override fun onCancelled(description: GestureDescription?) {
                finish(JSONObject().put("ok", false).put("error", "The gesture was cancelled"))
            }
        }, main)
        if (!dispatched) finish(JSONObject().put("ok", false).put("error", "The system rejected the gesture"))
    }

    private fun capture(credential: String, finish: (JSONObject) -> Unit) {
        if (Build.VERSION.SDK_INT < 30) { finish(JSONObject().put("ok", false).put("error", "Screenshots require Android 11")); return }
        // The work notice is a window of this app, and a capture that sees two apps is
        // refused. Take it down, let the window actually go, capture, then put it back.
        val notice = pill != null
        hidePill()
        main.postDelayed({
            captureFrame(credential, revision.get()) { result ->
                if (notice && result.optBoolean("ok")) showPill(tr("DSH 正在工作"))
                finish(result)
            }
        }, 150)
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.R)
    private fun captureFrame(credential: String, at: Long, finish: (JSONObject) -> Unit) {
        val windows = windows.toList()
        val pkg = rootInActiveWindow?.packageName?.toString().orEmpty()
        if (pkg !in PhoneControl.allowed) {
            finish(JSONObject().put("ok", false).put("error", "Foreground app is not allowed: $pkg"))
            return
        }
        // A full-display capture must not include a second app sitting in split screen.
        // Keyboards, taskbars and ROM system panels are not split-screen apps.
        // Classify by window type rather than assuming all system UI uses one package.
        val otherWindows = windows.filter { window ->
            window.type != AccessibilityWindowInfo.TYPE_SYSTEM &&
                window.type != AccessibilityWindowInfo.TYPE_INPUT_METHOD &&
                window.root?.packageName?.toString()?.let { it != pkg && it != packageName } == true
        }
        if (otherWindows.isNotEmpty()) {
            finish(JSONObject().put("ok", false).put("error", "Use a single app window for screenshots")
                .put("blockingWindows", JSONArray().apply {
                    otherWindows.forEach { put(JSONObject().put("package", it.root?.packageName?.toString())
                        .put("type", it.type)) }
                }))
            return
        }
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(value: ScreenshotResult) {
                try {
                    check(PhoneControl.authorized(credential) && at == revision.get()) { "The screen changed while capturing; retry" }
                    val source = Bitmap.wrapHardwareBuffer(value.hardwareBuffer, value.colorSpace) ?: error("Empty screenshot")
                    val bitmap = source.copy(Bitmap.Config.ARGB_8888, false); source.recycle()
                    val width = bitmap.width
                    val height = bitmap.height
                    val ratio = minOf(1f, 1440f / maxOf(bitmap.width, bitmap.height))
                    val small = Bitmap.createScaledBitmap(bitmap, (bitmap.width * ratio).toInt(), (bitmap.height * ratio).toInt(), true)
                    val output = ByteArrayOutputStream(); small.compress(Bitmap.CompressFormat.JPEG, 85, output)
                    if (small !== bitmap) small.recycle()
                    bitmap.recycle()
                    finish(JSONObject().put("ok", true).put("width", width).put("height", height).put("input", inputStatus(pkg))
                        .put("imageBase64", android.util.Base64.encodeToString(output.toByteArray(), android.util.Base64.NO_WRAP)))
                } catch (e: Exception) { finish(JSONObject().put("ok", false).put("error", e.message)) }
                finally { value.hardwareBuffer.close() }
            }
            override fun onFailure(errorCode: Int) {
                finish(JSONObject().put("ok", false).put("error", "Screenshot unavailable ($errorCode); protected windows cannot be captured"))
            }
        })
    }

    // ---- node helpers --------------------------------------------------------

    private fun find(root: AccessibilityNodeInfo, text: String?, desc: String?): AccessibilityNodeInfo? {
        var found: AccessibilityNodeInfo? = null
        fun walk(node: AccessibilityNodeInfo, depth: Int) {
            if (found != null || depth > 40) return
            val label = node.text?.toString().orEmpty()
            val description = node.contentDescription?.toString().orEmpty()
            if ((text != null && label.contains(text, ignoreCase = true)) ||
                (desc != null && description.contains(desc, ignoreCase = true))) { found = node; return }
            for (i in 0 until node.childCount) walk(node.getChild(i) ?: continue, depth + 1)
        }
        walk(root, 0)
        return found
    }

    /** A label is usually on a child of the thing you can actually press. */
    private fun nearestClickable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        var current = node
        var hops = 0
        while (current != null && hops++ < 6) {
            if (current.isClickable) return current
            current = current.parent
        }
        return null
    }

    private fun firstEditable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var found: AccessibilityNodeInfo? = null
        fun walk(current: AccessibilityNodeInfo, depth: Int) {
            if (found != null || depth > 40) return
            if (current.isEditable && current.isVisibleToUser && !current.isPassword) { found = current; return }
            for (i in 0 until current.childCount) walk(current.getChild(i) ?: continue, depth + 1)
        }
        walk(node, 0)
        return found
    }
}

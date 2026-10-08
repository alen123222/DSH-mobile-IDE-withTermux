package dev.dsh.pocket

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Drives the device through Shizuku, for when the accessibility service is not running.
 *
 * The shapes match the accessibility backend exactly — the same flat node list, the
 * same target resolution — so the tools, the plugin and the model see one contract
 * regardless of which backend answered. What differs is the reach: the shell user can
 * dump any window and inject input anywhere, but it cannot type non-ASCII text.
 */
object ShizukuPhone {
    private val NODE = Regex("<node ([^>]*?)/?>")
    private fun attr(attrs: String, name: String): String? =
        Regex(name + "=\"([^\"]*)\"").find(attrs)?.groupValues?.get(1)

    /** Dumps the active window. Returns null when the shell cannot produce one. */
    private fun dump(context: Context): String? {
        val file = ShizukuShot.scratch(context, "pocket-dump.xml")
        file.delete()
        val path = "'" + file.absolutePath.replace("'", "'\\''") + "'"
        val ok = ShizukuShot.run("uiautomator dump " + path + " >/dev/null 2>&1 && chmod 644 " + path)
        if (!ok || !file.isFile) return null
        return try { file.readText() } catch (_: Throwable) { null } finally { file.delete() }
    }

    /** Same fields the accessibility observe returns, in the same order. */
    private fun nodes(xml: String): Pair<String, JSONArray> {
        val items = JSONArray()
        var pkg = ""
        for (match in NODE.findAll(xml)) {
            val attrs = match.groupValues[1]
            val text = attr(attrs, "text").orEmpty()
            val desc = attr(attrs, "content-desc").orEmpty()
            val cls = attr(attrs, "class").orEmpty()
            if (pkg.isEmpty()) pkg = attr(attrs, "package").orEmpty()
            val clickable = attr(attrs, "clickable") == "true"
            val scrollable = attr(attrs, "scrollable") == "true"
            val longClickable = attr(attrs, "long-clickable") == "true"
            val password = attr(attrs, "password") == "true"
            val bounds = Regex("\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]").find(attr(attrs, "bounds").orEmpty())
            val input = cls.contains("EditText")
            if (bounds == null) continue
            val left = bounds.groupValues[1].toInt(); val top = bounds.groupValues[2].toInt()
            val right = bounds.groupValues[3].toInt(); val bottom = bounds.groupValues[4].toInt()
            if (right <= left || bottom <= top) continue
            if (text.isEmpty() && desc.isEmpty() && !clickable && !scrollable && !longClickable && !input) continue
            items.put(JSONObject()
                .put("text", if (password) "[password]" else text.take(300))
                .put("desc", if (password) "" else desc.take(200))
                .put("cls", cls.substringAfterLast('.'))
                .put("x", left).put("y", top).put("w", right - left).put("h", bottom - top)
                .put("clickable", clickable).put("input", input)
                .put("checked", attr(attrs, "checked") == "true")
                .put("selected", attr(attrs, "selected") == "true")
                .put("scrollable", scrollable).put("depth", 0))
        }
        return pkg to items
    }

    fun observe(context: Context): JSONObject {
        val xml = dump(context) ?: return JSONObject().put("ok", false)
            .put("error", "The shell user could not read the screen; is Shizuku still running?")
        val (pkg, items) = nodes(xml)
        val observed = JSONObject().put("ok", true).put("package", pkg)
            .put("count", items.length()).put("truncated", false).put("nodes", items)
            .put("backend", "shizuku")
        if (items.length() == 0) observed.put("hint",
            "No controls in " + pkg + ". Either the screen is still drawing, or this app does not expose its interface to accessibility; retrying is only worth it once, after a moment.")
        return observed
    }

    /** Resolves a target the way the accessibility backend does: at execution time. */
    private fun target(context: Context, args: JSONObject): Pair<Int, Int>? {
        if (args.has("fx") || args.has("fy")) {
            val metrics = context.resources.displayMetrics
            return (args.optDouble("fx", 0.5) * metrics.widthPixels).toInt() to
                (args.optDouble("fy", 0.5) * metrics.heightPixels).toInt()
        }
        if (args.has("x") && args.has("y")) return args.optInt("x") to args.optInt("y")
        val text = args.optString("text").ifBlank { null }
        val desc = args.optString("desc").ifBlank { null }
        val xml = dump(context) ?: return null
        val (_, items) = nodes(xml)
        for (i in 0 until items.length()) {
            val node = items.getJSONObject(i)
            val label = node.optString("text")
            val description = node.optString("desc")
            val hit = when {
                text != null -> label == text || description == text
                desc != null -> description == desc || label == desc
                else -> false
            }
            if (hit) return node.getInt("x") + node.getInt("w") / 2 to node.getInt("y") + node.getInt("h") / 2
        }
        // Fall back to a partial match: full labels are often padded with counts.
        for (i in 0 until items.length()) {
            val node = items.getJSONObject(i)
            val label = node.optString("text") + " " + node.optString("desc")
            val wanted = text ?: desc ?: ""
            if (wanted.isNotBlank() && label.contains(wanted) && (node.optBoolean("clickable") || node.optBoolean("input"))) {
                return node.getInt("x") + node.getInt("w") / 2 to node.getInt("y") + node.getInt("h") / 2
            }
        }
        return null
    }

    fun tap(context: Context, args: JSONObject): JSONObject {
        val point = target(context, args)
            ?: return JSONObject().put("ok", false).put("error", "No control matched; observe again and use its coordinates")
        val ok = ShizukuShot.run("input tap " + point.first + " " + point.second)
        return JSONObject().put("ok", ok).apply { if (!ok) put("error", "The shell user could not tap") }
            .put("x", point.first).put("y", point.second).put("backend", "shizuku")
    }

    fun type(context: Context, args: JSONObject): JSONObject {
        val text = args.optString("text")
        if (text.isEmpty()) return JSONObject().put("ok", false).put("error", "text is required")
        if (text.any { it.code > 127 }) return JSONObject().put("ok", false).put("error",
            "Typing non-ASCII text needs the accessibility service; the shell can only send ASCII. Enable accessibility to type this.")
        val escaped = text.replace(" ", "%s").replace("'", "'\\''")
        val ok = ShizukuShot.run("input text '" + escaped + "'")
        return JSONObject().put("ok", ok).apply { if (!ok) put("error", "The shell user could not type") }
            .put("backend", "shizuku")
    }

    fun scroll(context: Context, args: JSONObject): JSONObject {
        val metrics = context.resources.displayMetrics
        val cx = metrics.widthPixels / 2
        val from = (metrics.heightPixels * 0.65f).toInt()
        val to = (metrics.heightPixels * 0.35f).toInt()
        val command = when (args.optString("direction", "down")) {
            "down" -> "input swipe " + cx + " " + from + " " + cx + " " + to
            "up" -> "input swipe " + cx + " " + to + " " + cx + " " + from
            "left" -> "input swipe " + (metrics.widthPixels * 0.8f).toInt() + " " + (metrics.heightPixels / 2) +
                " " + (metrics.widthPixels * 0.2f).toInt() + " " + (metrics.heightPixels / 2)
            "right" -> "input swipe " + (metrics.widthPixels * 0.2f).toInt() + " " + (metrics.heightPixels / 2) +
                " " + (metrics.widthPixels * 0.8f).toInt() + " " + (metrics.heightPixels / 2)
            else -> return JSONObject().put("ok", false).put("error", "direction must be up, down, left or right")
        }
        val ok = ShizukuShot.run(command)
        return JSONObject().put("ok", ok).apply { if (!ok) put("error", "The shell user could not scroll") }
            .put("backend", "shizuku")
    }

    fun key(args: JSONObject): JSONObject {
        val code = when (args.optString("key")) {
            "back" -> 4
            "home" -> 3
            else -> return JSONObject().put("ok", false).put("error", "Only back/home are supported")
        }
        val ok = ShizukuShot.run("input keyevent " + code)
        return JSONObject().put("ok", ok).apply { if (!ok) put("error", "The shell user could not press the key") }
            .put("backend", "shizuku")
    }

    fun launch(args: JSONObject): JSONObject {
        val pkg = args.optString("package")
        if (pkg.isBlank()) return JSONObject().put("ok", false).put("error", "package is required")
        val ok = ShizukuShot.run("monkey -p " + pkg + " -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1")
        return JSONObject().put("ok", ok).apply { if (!ok) put("error", "The shell user could not launch " + pkg) }
            .put("backend", "shizuku")
    }
}

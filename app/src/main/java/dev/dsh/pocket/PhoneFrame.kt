package dev.dsh.pocket

import android.graphics.Bitmap
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/** Packages one captured frame; both backends return this exact shape. */
object PhoneFrame {
    fun encode(source: Bitmap, pkg: String, input: JSONObject? = null): JSONObject {
        val bitmap = source.copy(Bitmap.Config.ARGB_8888, false)
        val width = bitmap.width
        val height = bitmap.height
        val ratio = minOf(1f, 1440f / maxOf(bitmap.width, bitmap.height))
        val small = Bitmap.createScaledBitmap(bitmap, (bitmap.width * ratio).toInt(), (bitmap.height * ratio).toInt(), true)
        val output = ByteArrayOutputStream(); small.compress(Bitmap.CompressFormat.JPEG, 85, output)
        if (small !== bitmap) small.recycle()
        bitmap.recycle()
        return JSONObject().put("ok", true).put("width", width).put("height", height)
            .put("imageBase64", android.util.Base64.encodeToString(output.toByteArray(), android.util.Base64.NO_WRAP))
            .apply { input?.let { put("input", it) } }
    }
}

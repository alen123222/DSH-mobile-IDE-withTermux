package dev.dsh.pocket

/** Per-request input failures survive intervening taps and observations. */
class PhoneInputGuard {
    private var failures = 0
    private var needsObservation = false
    fun reset() { failures = 0; needsObservation = false }
    fun observed() { needsObservation = false }
    fun inputResult(ok: Boolean) {
        if (ok) { failures = 0; needsObservation = true }
        else { failures++; needsObservation = true }
    }
    fun blocked(action: String): String? {
        if (action in setOf("state", "observe", "screenshot", "finished")) return null
        if (failures >= 2) return "Phone input failed twice. Further actions are disabled for this request. Stop and report the input error; do not retry taps or typing."
        if (needsObservation) return "Obtain a successful phone_observe or phone_screenshot to check the input result before any further action."
        return null
    }
}

fun phoneCoordinate(value: Double, extent: Int, fractional: Boolean): Float {
    require(value.isFinite() && value >= 0 && if (fractional) value <= 1 else value < extent) {
        if (fractional) "Fractional coordinates fx/fy must be between 0 and 1" else "Pixel coordinates must be inside the screen"
    }
    return if (fractional) (value * (extent - 1)).toFloat() else value.toFloat()
}

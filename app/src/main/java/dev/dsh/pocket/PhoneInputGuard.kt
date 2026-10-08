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
        // Only typing is stopped: a field that keeps refusing text is the runaway, and
        // locking the whole phone after two attempts left models unable to recover.
        if (action == "type" && failures >= 3) {
            return "Typing failed $failures times. Stop typing and report the input error; open the field again, use the app's own search, or tell the user."
        }
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

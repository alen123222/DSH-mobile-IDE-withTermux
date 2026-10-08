package dev.dsh.pocket

/**
 * The Shizuku user service. This class is instantiated by Shizuku inside a process that
 * runs as the shell user, so the command below is not subject to the app's own limits.
 */
class ShotService : IShotService.Stub() {
    override fun screencap(path: String): Boolean = try {
        val process = ProcessBuilder("sh", "-c", "screencap -p '" + path.replace("'", "'\\''") + "' && chmod 644 '" + path.replace("'", "'\\''") + "'")
            .redirectErrorStream(true)
            .start()
        val finished = process.waitFor()
        finished == 0
    } catch (_: Throwable) { false }
}

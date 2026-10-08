package dev.dsh.pocket

/**
 * The Shizuku user service. Shizuku instantiates this inside a process running as the
 * shell user, so the commands below are not subject to the app's own limits.
 */
class ShotService : IShotService.Stub() {
    override fun screencap(path: String): Boolean = exec("screencap -p " + quote(path) + " && chmod 644 " + quote(path))

    override fun run(command: String): Boolean = exec(command)

    private fun exec(command: String): Boolean = try {
        ProcessBuilder("sh", "-c", command).redirectErrorStream(true).start().waitFor() == 0
    } catch (_: Throwable) { false }

    private fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}

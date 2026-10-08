package dev.dsh.pocket;

/**
 * Runs inside the Shizuku user service, so the implementation has the shell user's
 * permissions. Results that can be large come back as files rather than values: a
 * screen-sized PNG or a UI dump runs past the binder transaction limit, but the shell
 * user can write into the app's own external files directory and the app reads it
 * from there.
 */
interface IShotService {
    const int VERSION = 2;
    boolean screencap(String path);
    /** Runs a shell command as the shell user; the caller redirects output to a file. */
    boolean run(String command);
}

package dev.dsh.pocket;

/**
 * Runs inside the Shizuku user service, so the implementation has the shell user's
 * permissions. It hands back a path rather than the pixels: a screen-sized PNG is far
 * past the binder transaction limit, but the shell user can write it into the app's
 * own external files directory and the app can read it from there.
 */
interface IShotService {
    const int VERSION = 1;
    boolean screencap(String path);
}

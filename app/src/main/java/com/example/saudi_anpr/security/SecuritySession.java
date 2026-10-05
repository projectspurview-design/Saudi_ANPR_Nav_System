package com.example.saudi_anpr.security;

/** In-memory session gate. It resets whenever the Android process is recreated or the app is backgrounded. */
public final class SecuritySession {
    private static volatile boolean unlocked = false;

    private SecuritySession() {}

    public static boolean isUnlocked() {
        return unlocked;
    }

    public static void unlock() {
        unlocked = true;
    }

    public static void lock() {
        unlocked = false;
    }
}

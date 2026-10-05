package com.example.saudi_anpr.security;

import android.content.Context;
import android.provider.Settings;

public final class DeviceId {
    private DeviceId() {}

    public static String get(Context context) {
        String id = Settings.Secure.getString(
                context.getContentResolver(),
                Settings.Secure.ANDROID_ID
        );
        return id == null || id.trim().isEmpty() ? "unknown-device" : id;
    }
}

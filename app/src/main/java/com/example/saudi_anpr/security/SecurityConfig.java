package com.example.saudi_anpr.security;

import com.example.saudi_anpr.BuildConfig;

/** Client-side security configuration. */
public final class SecurityConfig {
    private SecurityConfig() {}

    public static final String LICENSE_ID = BuildConfig.LICENSE_ID;
    public static final String QR_PREFIX = "ANPRLOGIN";
    public static final int PIN_LENGTH = 6;

    public static final String PREFS = "SaudiAnprSecurity";
    public static final String KEY_LAST_SERVER_EXPIRY = "last_server_expiry_ms";
    public static final String KEY_LAST_ACTIVATED_DEVICE = "last_activated_device";
    public static final String KEY_LICENSE_ID = "license_id";
}

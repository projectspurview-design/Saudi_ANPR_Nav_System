package com.example.saudi_anpr.security;

import android.content.Context;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.Source;
import com.google.firebase.Timestamp;

/**
 * Spark-plan license validation.
 *
 * IMPORTANT: this version is intentionally READ-ONLY on Firestore.
 * No Cloud Functions and no client-side activation writes are required.
 * The administrator provisions an expiresAt timestamp in Firestore.
 */
public final class LicenseValidator {
    public interface Callback {
        void onResult(boolean valid, String message, long expiresAtMs);
    }

    private LicenseValidator() {}

    public static void validate(
            Context context,
            String licenseId,
            String email,
            String pin,
            Callback callback
    ) {
        if (licenseId == null || licenseId.trim().isEmpty()
                || email == null || email.trim().isEmpty()
                || pin == null || pin.length() != SecurityConfig.PIN_LENGTH) {
            callback.onResult(false, "Enter the QR and six-digit PIN.", 0L);
            return;
        }

        FirebaseAuth auth = FirebaseAuth.getInstance();
        auth.signOut();

        auth.signInWithEmailAndPassword(email.trim(), pin)
                .addOnSuccessListener(authResult -> readLicense(
                        context,
                        licenseId.trim(),
                        email.trim(),
                        callback
                ))
                .addOnFailureListener(error -> callback.onResult(
                        false,
                        friendlyAuthError(error),
                        0L
                ));
    }

    public static void checkCurrentSession(Context context, Callback callback) {
        FirebaseAuth auth = FirebaseAuth.getInstance();
        if (auth.getCurrentUser() == null) {
            callback.onResult(false, "Please scan the QR and enter the PIN.", 0L);
            return;
        }

        String licenseId = context.getSharedPreferences(
                SecurityConfig.PREFS,
                Context.MODE_PRIVATE
        ).getString(SecurityConfig.KEY_LICENSE_ID, null);

        if (licenseId == null || licenseId.trim().isEmpty()) {
            callback.onResult(false, "License session not found.", 0L);
            return;
        }

        String email = auth.getCurrentUser().getEmail();
        if (email == null || email.trim().isEmpty()) {
            callback.onResult(false, "Firebase account email is unavailable.", 0L);
            return;
        }

        readLicense(context, licenseId, email.trim(), callback);
    }

    private static void readLicense(
            Context context,
            String licenseId,
            String email,
            Callback callback
    ) {
        FirebaseFirestore.getInstance()
                .collection("licenses")
                .document(licenseId)
                .get(Source.SERVER)
                .addOnSuccessListener(snapshot -> {
                    if (!snapshot.exists()) {
                        callback.onResult(false, "License not found: " + licenseId, 0L);
                        return;
                    }

                    String status = snapshot.getString("status");
                    String packageName = snapshot.getString("packageName");
                    String loginEmail = snapshot.getString("loginEmail");
                    Timestamp expiresAt = snapshot.getTimestamp("expiresAt");

                    if (!"active".equals(status)) {
                        callback.onResult(false, "License is not active.", 0L);
                        return;
                    }

                    if (!context.getPackageName().equals(packageName)) {
                        callback.onResult(false, "License is for a different application.", 0L);
                        return;
                    }

                    if (loginEmail == null || !loginEmail.equalsIgnoreCase(email)) {
                        callback.onResult(false, "QR email does not match the Firebase account.", 0L);
                        return;
                    }

                    if (expiresAt == null) {
                        callback.onResult(false,
                                "License has no expiry date. Add expiresAt in Firestore.", 0L);
                        return;
                    }

                    long expiry = expiresAt.toDate().getTime();
                    if (expiry <= System.currentTimeMillis()) {
                        callback.onResult(false, "License expired.", expiry);
                        return;
                    }

                    saveSession(context, licenseId, expiry);
                    callback.onResult(true, "Access granted.", expiry);
                })
                .addOnFailureListener(error -> callback.onResult(
                        false,
                        friendlyFirestoreError(error),
                        0L
                ));
    }

    private static void saveSession(Context context, String licenseId, long expiry) {
        context.getSharedPreferences(SecurityConfig.PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(SecurityConfig.KEY_LICENSE_ID, licenseId)
                .putLong(SecurityConfig.KEY_LAST_SERVER_EXPIRY, expiry)
                .apply();
    }

    private static String friendlyAuthError(Exception error) {
        String message = error == null ? "" : String.valueOf(error.getMessage());
        String m = message.toLowerCase();
        if (m.contains("invalid-credential") || m.contains("wrong-password")
                || m.contains("user-not-found") || m.contains("invalid login credentials")) {
            return "Invalid PIN or Firebase account.";
        }
        if (m.contains("operation-not-allowed")) {
            return "Firebase Email/Password sign-in is not enabled.";
        }
        if (m.contains("network")) {
            return "Cannot reach Firebase. Check Internet connection.";
        }
        if (com.example.saudi_anpr.BuildConfig.DEBUG) {
            return "Firebase sign-in failed: " + message;
        }
        return "Firebase sign-in failed.";
    }

    private static String friendlyFirestoreError(Exception error) {
        String message = error == null ? "" : String.valueOf(error.getMessage());
        String m = message.toLowerCase();
        if (m.contains("permission_denied") || m.contains("permission denied")) {
            return "Firestore denied access. Check Firebase Rules, user email, packageName and expiry.";
        }
        if (m.contains("network") || m.contains("unavailable")) {
            return "Cannot reach Firebase. Check Internet connection.";
        }
        if (com.example.saudi_anpr.BuildConfig.DEBUG) {
            return "Firestore failed: " + message;
        }
        return "License validation failed.";
    }
}

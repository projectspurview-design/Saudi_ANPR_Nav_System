package com.example.saudi_anpr.security;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.example.saudi_anpr.HomeActivity;
import com.example.saudi_anpr.R;
import com.example.saudi_anpr.scanner.QrScannerActivity;

/**
 * One-scan lock screen.
 *
 * The QR payload contains the license ID, Firebase Auth email and six-digit PIN:
 * ANPRLOGIN|LICENSE_ID|EMAIL|PIN
 *
 * After a successful QR scan the app immediately authenticates with Firebase
 * and opens HomeActivity. There is no second PIN-entry step.
 */
public final class LockActivity extends AppCompatActivity {
    private static final int REQUEST_SCAN = 901;

    private TextView statusText;
    private Button scanButton;
    private VoiceReceiver voiceReceiver;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN
        );
        getWindow().setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE
        );

        setContentView(R.layout.activity_lock);

        statusText = findViewById(R.id.statusText);
        scanButton = findViewById(R.id.scanQrButton);

        scanButton.setOnClickListener(v -> startQrScanner());
        scanButton.requestFocus();

        registerVoiceCommands();
    }

    private void registerVoiceCommands() {
        try {
            voiceReceiver = new VoiceReceiver(this);
        } catch (Throwable ignored) {
            statusText.setText("Voice unavailable. Use the touchpad.");
        }
    }

    public void startQrScannerFromVoice() {
        startQrScanner();
    }

    /** Kept for compatibility with older VoiceReceiver builds. */
    public void validateCurrentCredentialsFromVoice() {
        startQrScanner();
    }

    private void startQrScanner() {
        setBusy(true);
        statusText.setText("Opening QR scanner…");

        try {
            Intent intent = new Intent(this, QrScannerActivity.class);
            startActivityForResult(intent, REQUEST_SCAN);
        } catch (RuntimeException e) {
            setBusy(false);
            statusText.setText("Could not open the QR scanner.");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode != REQUEST_SCAN) return;

        setBusy(false);

        if (resultCode != Activity.RESULT_OK || data == null) {
            statusText.setText("QR scan cancelled.");
            return;
        }

        String raw = data.getStringExtra(QrScannerActivity.EXTRA_QR_TEXT);
        if (raw == null || raw.trim().isEmpty()) {
            statusText.setText("No QR data found.");
            return;
        }

        String[] parsed = parseOneScanQrPayload(raw.trim());
        if (parsed == null) {
            statusText.setText("Invalid QR. Use the one-scan reseller QR.");
            return;
        }

        String licenseId = parsed[1];
        String email = parsed[2];
        String pin = parsed[3];

        statusText.setText("QR accepted. Signing in…");
        setBusy(true);

        LicenseValidator.validate(this, licenseId, email, pin, (valid, message, expiresAtMs) ->
                runOnUiThread(() -> {
                    setBusy(false);

                    if (!valid) {
                        statusText.setText(
                                message == null || message.isEmpty()
                                        ? "Access denied."
                                        : message
                        );
                        return;
                    }

                    SecuritySession.unlock();
                    openApplication();
                })
        );
    }

    /**
     * Expected payload:
     * ANPRLOGIN|LICENSE_ID|FIREBASE_AUTH_EMAIL|SIX_DIGIT_PIN
     */
    private String[] parseOneScanQrPayload(String payload) {
        String[] parts = payload.split("\\|", -1);
        if (parts.length != 4) return null;
        if (!SecurityConfig.QR_PREFIX.equals(parts[0])) return null;

        String licenseId = parts[1].trim();
        String email = parts[2].trim();
        String pin = parts[3].trim();

        if (licenseId.isEmpty() || licenseId.length() > 120) return null;
        if (!email.contains("@") || email.length() > 200) return null;
        if (!pin.matches("\\d{" + SecurityConfig.PIN_LENGTH + "}")) return null;

        return new String[]{parts[0], licenseId, email, pin};
    }

    private void setBusy(boolean busy) {
        scanButton.setEnabled(!busy);
    }

    private void openApplication() {
        Intent intent = new Intent(this, HomeActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }

    @Override
    protected void onDestroy() {
        if (voiceReceiver != null) {
            voiceReceiver.close();
            voiceReceiver = null;
        }
        super.onDestroy();
    }
}

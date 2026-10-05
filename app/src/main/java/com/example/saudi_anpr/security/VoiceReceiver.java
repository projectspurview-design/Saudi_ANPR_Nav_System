package com.example.saudi_anpr.security;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.RemoteException;
import android.util.Log;

import androidx.core.content.ContextCompat;

import com.vuzix.sdk.speechrecognitionservice.VuzixSpeechClient;

/** Vuzix M400 voice navigation for the lock screen. */
public final class VoiceReceiver extends BroadcastReceiver {
    private static final String TAG = "VoiceReceiver";

    private final LockActivity activity;
    private VuzixSpeechClient client;
    private String scanCommand;
    private String unlockCommand;
    private boolean receiverRegistered;

    public VoiceReceiver(LockActivity activity) {
        this.activity = activity;

        try {
            client = new VuzixSpeechClient(activity);

            scanCommand = client.convertPhraseToReplyFormat("scan qr");
            unlockCommand = client.convertPhraseToReplyFormat("unlock");

            client.insertPhrase("scan qr", scanCommand);
            client.insertPhrase("unlock", unlockCommand);

            IntentFilter filter = new IntentFilter(VuzixSpeechClient.ACTION_VOICE_COMMAND);
            ContextCompat.registerReceiver(
                    activity,
                    this,
                    filter,
                    ContextCompat.RECEIVER_EXPORTED
            );
            receiverRegistered = true;

            Log.d(TAG, "Vuzix voice commands registered");
        } catch (RemoteException e) {
            client = null;
            Log.e(TAG, "Vuzix speech service error", e);
        } catch (NoClassDefFoundError e) {
            client = null;
            Log.e(TAG, "Vuzix Speech SDK unavailable", e);
        } catch (RuntimeException e) {
            client = null;
            Log.e(TAG, "Vuzix Speech SDK initialization failed", e);
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !VuzixSpeechClient.ACTION_VOICE_COMMAND.equals(intent.getAction())) {
            return;
        }

        String phrase = intent.getStringExtra(VuzixSpeechClient.PHRASE_STRING_EXTRA);
        if (phrase == null) return;

        if ((scanCommand != null && scanCommand.equals(phrase))
                || (unlockCommand != null && unlockCommand.equals(phrase))) {
            // One-scan flow: both voice phrases open the QR scanner.
            activity.runOnUiThread(activity::startQrScannerFromVoice);
        }
    }

    public void close() {
        if (client != null) {
            try {
                client.deletePhrase("scan qr");
                client.deletePhrase("unlock");
            } catch (NoClassDefFoundError | RuntimeException ignored) {
                // Device/SDK compatibility cleanup only.
            }
        }

        if (receiverRegistered) {
            try {
                activity.unregisterReceiver(this);
            } catch (IllegalArgumentException ignored) {
                // Already unregistered.
            } finally {
                receiverRegistered = false;
            }
        }

        client = null;
    }
}

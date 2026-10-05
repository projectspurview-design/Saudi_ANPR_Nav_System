package com.example.saudi_anpr;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.example.saudi_anpr.api.RetrofitClient;
import com.example.saudi_anpr.security.LicenseValidator;
import com.example.saudi_anpr.security.LockActivity;
import com.example.saudi_anpr.security.SecurityConfig;
import com.example.saudi_anpr.security.SecuritySession;
import com.example.saudi_anpr.api.ScheduleResponse;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class HomeActivity extends AppCompatActivity implements TextToSpeech.OnInitListener {

    private static final String TAG = "HOME_APP";
    private static final long SERVER_SYNC_INTERVAL = 10000; // keep schedule updates responsive while app is foregrounded
    private static final long LOCAL_TIME_CHECK_INTERVAL = 1000; // local only

    private static final String PREFS_NAME = "AnprReminderPrefs";
    private static final String KEY_LAST_NOTIFIED_SCHEDULE = "last_notified_schedule";
    private static final String KEY_LAST_FIVE_MIN_NOTIFIED = "last_five_min_notified";
    private static final String KEY_LAST_START_NOTIFIED = "last_start_notified";

    // Reminder UI Components
    private CardView reminderCard;
    private TextView reminderTitle;
    private TextView reminderLocation;
    private TextView reminderTime;
    private View reminderAlertContainer;
    private TextView reminderAlert;
    private TextView reminderCoords;
    private ImageView btnCloseReminder;

    private final Handler reminderHandler = new Handler(Looper.getMainLooper());
    private Runnable checkReminderRunnable;
    private Runnable serverSyncRunnable;
    private boolean isScheduleRequestInFlight = false;
    private ScheduleResponse.ScheduleData cachedScheduleData;

    // Text-to-Speech
    private TextToSpeech tts;
    private boolean isTtsInitialized = false;
    private final Handler licenseHandler = new Handler(Looper.getMainLooper());
    private final Runnable licenseCheckRunnable = () -> checkLicense(false);
    private boolean licenseCheckInFlight = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_home);

        // Initialize TTS
        tts = new TextToSpeech(this, this);

        Button btnFaceRecognition = findViewById(R.id.btnFaceRecognition);
        Button btnLicensePlate = findViewById(R.id.btnLicensePlate);

        if (btnFaceRecognition != null) {
            btnFaceRecognition.setOnClickListener(v ->
                    Toast.makeText(HomeActivity.this, "Face Recognition coming soon!", Toast.LENGTH_SHORT).show()
            );
        }

        if (btnLicensePlate != null) {
            btnLicensePlate.setOnClickListener(v -> {
                Intent intent = new Intent(HomeActivity.this, MainActivity.class);
                startActivity(intent);
            });
        }

        // Initialize Reminder UI
        reminderCard = findViewById(R.id.reminderCard);
        reminderTitle = findViewById(R.id.reminderTitle);
        reminderLocation = findViewById(R.id.reminderLocation);
        reminderTime = findViewById(R.id.reminderTime);
        reminderAlertContainer = findViewById(R.id.reminderAlertContainer);
        reminderAlert = findViewById(R.id.reminderAlert);
        reminderCoords = findViewById(R.id.reminderCoords);
        btnCloseReminder = findViewById(R.id.btnCloseReminder);

        if (btnCloseReminder != null) {
            btnCloseReminder.setOnClickListener(v -> reminderCard.setVisibility(View.GONE));
        }

        startReminderPolling();
    }

    @Override
    protected void onResume() {
        super.onResume();

        // Returning to the app after it was backgrounded requires a fresh unlock.
        if (!SecuritySession.isUnlocked()) {
            openLockScreen();
            return;
        }

        // HomeActivity owns schedule polling while it is the foreground screen.
        // MainActivity uses the same polling when ANPR is open.
        startReminderPolling();

        checkLicense(true);
        licenseHandler.removeCallbacks(licenseCheckRunnable);
        licenseHandler.postDelayed(licenseCheckRunnable, 5 * 60 * 1000L);
    }

    @Override
    protected void onPause() {
        licenseHandler.removeCallbacks(licenseCheckRunnable);
        stopReminderPolling();
        super.onPause();
    }

    private void checkLicense(boolean firstCheck) {
        if (licenseCheckInFlight || !SecuritySession.isUnlocked()) return;
        licenseCheckInFlight = true;
        LicenseValidator.checkCurrentSession(this, (valid, message, expiresAtMs) -> runOnUiThread(() -> {
            licenseCheckInFlight = false;

            if (!valid) {
                // On the Spark-plan build, Firebase Auth + Firestore are the authority.
                // If the network is temporarily unavailable, retain the last server-confirmed
                // access until the stored expiry. Explicit Firebase permission/expiry denials
                // still lock immediately. This preserves offline ANPR operation after a valid
                // activation while acknowledging that local device time is not tamper-proof.
                if (canUseLastServerExpiryOffline(message)) {
                    scheduleNextLicenseCheck();
                    return;
                }

                SecuritySession.lock();
                Toast.makeText(
                        this,
                        message == null || message.isEmpty() ? "License unavailable" : message,
                        Toast.LENGTH_LONG
                ).show();
                openLockScreen();
                return;
            }

            if (expiresAtMs > 0) {
                getSharedPreferences(SecurityConfig.PREFS, MODE_PRIVATE)
                        .edit()
                        .putLong(SecurityConfig.KEY_LAST_SERVER_EXPIRY, expiresAtMs)
                        .apply();
            }

            if (!firstCheck) {
                scheduleNextLicenseCheck();
            }
        }));
    }

    private boolean canUseLastServerExpiryOffline(String message) {
        String lower = message == null ? "" : message.toLowerCase(Locale.US);

        // These are authoritative denials and must not be bypassed by cached access.
        if (lower.contains("expired")
                || lower.contains("revoked")
                || lower.contains("disabled")
                || lower.contains("bound to another device")
                || lower.contains("license not found")
                || lower.contains("invalid license")) {
            return false;
        }

        long expiresAt = getSharedPreferences(SecurityConfig.PREFS, MODE_PRIVATE)
                .getLong(SecurityConfig.KEY_LAST_SERVER_EXPIRY, 0L);
        return expiresAt > System.currentTimeMillis();
    }

    private void scheduleNextLicenseCheck() {
        licenseHandler.removeCallbacks(licenseCheckRunnable);
        licenseHandler.postDelayed(licenseCheckRunnable, 5 * 60 * 1000L);
    }

    private void openLockScreen() {
        Intent lock = new Intent(this, LockActivity.class);
        lock.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(lock);
        finish();
    }

    @Override
    public void onInit(int status) {
        if (status == TextToSpeech.SUCCESS) {
            int result = tts.setLanguage(Locale.US);
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e(TAG, "TTS Language not supported");
            } else {
                isTtsInitialized = true;
            }
        } else {
            Log.e(TAG, "TTS Initialization failed");
        }
    }

    private void startReminderPolling() {
        stopReminderPolling();

        // Local clock check: no network request.
        checkReminderRunnable = () -> {
            if (cachedScheduleData != null) {
                updateReminderUI(cachedScheduleData);
            }
            reminderHandler.postDelayed(checkReminderRunnable, LOCAL_TIME_CHECK_INTERVAL);
        };
        reminderHandler.post(checkReminderRunnable);

        // Foreground schedule sync. This keeps a newly-created web schedule visible
        // quickly without creating a permanent background service.
        serverSyncRunnable = () -> {
            fetchSchedule();
            reminderHandler.postDelayed(serverSyncRunnable, SERVER_SYNC_INTERVAL);
        };
        reminderHandler.post(serverSyncRunnable);
    }

    private void stopReminderPolling() {
        if (checkReminderRunnable != null) {
            reminderHandler.removeCallbacks(checkReminderRunnable);
        }
        if (serverSyncRunnable != null) {
            reminderHandler.removeCallbacks(serverSyncRunnable);
        }
    }

    private boolean isNetworkAvailable() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo activeNetwork = cm != null ? cm.getActiveNetworkInfo() : null;
        return activeNetwork != null && activeNetwork.isConnected();
    }

    private void fetchSchedule() {
        if (!isNetworkAvailable() || isScheduleRequestInFlight) return;
        isScheduleRequestInFlight = true;
        RetrofitClient.getService().getSchedule().enqueue(new Callback<ScheduleResponse>() {
            @Override
            public void onResponse(@NonNull Call<ScheduleResponse> call, @NonNull Response<ScheduleResponse> response) {
                isScheduleRequestInFlight = false;
                if (response.isSuccessful() && response.body() != null && response.body().success) {
                    cachedScheduleData = response.body().data;
                    updateReminderUI(cachedScheduleData);
                }
            }

            @Override
            public void onFailure(@NonNull Call<ScheduleResponse> call, @NonNull Throwable t) {
                isScheduleRequestInFlight = false;
                Log.e(TAG, "Network error fetching schedule", t);
            }
        });
    }

    private int timeToMinutes(String timeStr) {
        if (timeStr == null || !timeStr.contains(":")) return -1;
        try {
            String[] parts = timeStr.split(":");
            int h = Integer.parseInt(parts[0]);
            int m = Integer.parseInt(parts[1]);
            return h * 60 + m;
        } catch (Exception e) {
            return -1;
        }
    }

    private void sendStartReminderOnce(ScheduleResponse.ScheduleItem item, SharedPreferences prefs) {
        if (item == null) return;
        String today = new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date());
        String startAlertKey = today + "_" + item.startTime + "_start";
        if (startAlertKey.equals(prefs.getString(KEY_LAST_START_NOTIFIED, ""))) return;

        boolean saved = prefs.edit()
                .putString(KEY_LAST_START_NOTIFIED, startAlertKey)
                .commit();
        if (!saved) return;

        String message = "Go now to " + item.location + ".";
        openReminderScreen("LOCATION TIME", item, message);
        showSystemNotification("Location Time", message);
        if (isTtsInitialized && tts != null) {
            tts.speak(
                    "Location alert. Go now to " + item.location + ".",
                    TextToSpeech.QUEUE_FLUSH,
                    null,
                    "start_" + startAlertKey
            );
        }
    }

    private void openReminderScreen(
            String title,
            ScheduleResponse.ScheduleItem item,
            String message
    ) {
        if (item == null) return;

        Intent intent = new Intent(HomeActivity.this, ReminderActivity.class);
        intent.putExtra(ReminderActivity.EXTRA_TITLE, title);
        intent.putExtra(ReminderActivity.EXTRA_LOCATION, item.location);
        intent.putExtra(ReminderActivity.EXTRA_MESSAGE, message);
        intent.putExtra(ReminderActivity.EXTRA_START_TIME, item.startTime);
        intent.putExtra(ReminderActivity.EXTRA_LATITUDE, item.latitude);
        intent.putExtra(ReminderActivity.EXTRA_LONGITUDE, item.longitude);

        // No CLEAR_TASK/NEW_TASK flags: ReminderActivity is placed above the
        // current screen. Calling finish() returns to the previous navigation.
        startActivity(intent);
    }


    private void updateReminderUI(ScheduleResponse.ScheduleData data) {
        if (data == null) return;

        runOnUiThread(() -> {
            String localCurrentTime = new SimpleDateFormat("HH:mm", Locale.US).format(new Date());
            String scheduleCurrentTime = (data.currentTime != null && !data.currentTime.trim().isEmpty())
                    ? data.currentTime.trim()
                    : localCurrentTime;
            if (reminderTime != null) {
                reminderTime.setText("Current Time: " + scheduleCurrentTime);
            }

            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            boolean inWarningZone = false;

            // 1. Check for 5-MINUTE BEFORE WARNING for Upcoming Task
            if (data.upcoming != null) {
                int currentMins = timeToMinutes(scheduleCurrentTime);
                int upcomingMins = timeToMinutes(data.upcoming.startTime);

                if (currentMins != -1 && upcomingMins != -1) {
                    int diff = upcomingMins - currentMins;
                    if (diff < 0) diff += 24 * 60; // Handle day wrap around

                    // Exact-time alert from cached data. This does not wait for
                    // the next one-minute server sync.
                    if (diff == 0) {
                        sendStartReminderOnce(data.upcoming, prefs);
                    }

                    // Notify only during the exact minute that is 5 minutes before
                    if (diff == 5) {
                        inWarningZone = true;
                        String upcomingKey = getScheduleKey(data.upcoming) + "_5min";
                        String lastFiveMinNotified = prefs.getString(KEY_LAST_FIVE_MIN_NOTIFIED, "");

                        if (!lastFiveMinNotified.equals(upcomingKey)) {
                            prefs.edit().putString(KEY_LAST_FIVE_MIN_NOTIFIED, upcomingKey).commit();

                            String warningMessage = "In 5 minutes, go to " + data.upcoming.location + ".";
                            openReminderScreen("UPCOMING REMINDER", data.upcoming, warningMessage);

                            // Trigger System Notification
                            showSystemNotification("Upcoming Location", warningMessage);

                            // Trigger TTS
                            if (isTtsInitialized && tts != null) {
                                tts.speak("Reminder. In 5 minutes, go to " + data.upcoming.location + ".", TextToSpeech.QUEUE_FLUSH, null, null);
                            }
                        }
                    }
                }
            }

            // 2. Check for ON-TIME Alert for Active Task
            if (data.active != null) {
                String currentScheduleKey = getScheduleKey(data.active);
                int currentMins = timeToMinutes(scheduleCurrentTime);
                int activeStartMins = timeToMinutes(data.active.startTime);
                int activeEndMins = timeToMinutes(data.active.endTime);
                boolean isInsideActiveWindow = currentMins != -1
                        && activeStartMins != -1
                        && currentMins >= activeStartMins
                        && (activeEndMins == -1 || currentMins <= activeEndMins);

                // Trigger once as soon as the active schedule is reached. This is
                // deliberately tolerant of a few seconds of network/polling delay.
                if (isInsideActiveWindow) {
                    String activePopupKey = currentScheduleKey + "_active_popup";
                    String lastActiveNotified = prefs.getString(KEY_LAST_NOTIFIED_SCHEDULE, "");

                    if (!lastActiveNotified.equals(activePopupKey)) {
                        prefs.edit().putString(KEY_LAST_NOTIFIED_SCHEDULE, activePopupKey).apply();

                        // Trigger System Notification & TTS immediately alongside the UI Card
                        sendStartReminderOnce(data.active, prefs);
                    }
                }

                // Populate Active Details
                if (reminderTitle != null) reminderTitle.setText("CURRENT LOCATION");
                if (reminderLocation != null) {
                    reminderLocation.setText(data.active.location);
                    reminderLocation.setTextColor(ContextCompat.getColor(this, R.color.white));
                }

                // Handle Next Location Sub-Alert
                if (data.upcoming != null && reminderAlertContainer != null) {
                    reminderAlertContainer.setVisibility(View.VISIBLE);
                    if (reminderAlert != null) {
                        reminderAlert.setText("NEXT: " + data.upcoming.location + " at " + data.upcoming.startTime);
                        reminderAlert.setTextColor(ContextCompat.getColor(this, R.color.primary_blue));
                    }
                    if (reminderCoords != null) {
                        reminderCoords.setText(String.format(Locale.US, "📍 Dest: %.6f, %.6f", data.upcoming.latitude, data.upcoming.longitude));
                        reminderCoords.setTextColor(ContextCompat.getColor(this, R.color.primary_blue));
                    }
                } else if (reminderAlertContainer != null) {
                    reminderAlertContainer.setVisibility(View.GONE);
                }

            } else if (!inWarningZone) {
                // No active schedule and not in warning zone. HIDE EVERYTHING.
                if (reminderCard != null) {
                    reminderCard.setVisibility(View.GONE);
                }
            }
        });
    }
    private String getLastNotifiedSchedule() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getString(KEY_LAST_NOTIFIED_SCHEDULE, "");
    }

    private void setLastNotifiedSchedule(String scheduleKey) {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putString(KEY_LAST_NOTIFIED_SCHEDULE, scheduleKey).apply();
    }

    private String getScheduleKey(ScheduleResponse.ScheduleItem item) {
        if (item == null) return "";
        // Including the date lets a repeating daily schedule notify again
        // tomorrow, while still preventing repeats today.
        String today = new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date());
        return today + "_" + item.location.trim() + "_" + item.startTime;
    }

    private void showSystemNotification(String title, String message) {
        NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel("schedule_channel", "Schedule Alerts", NotificationManager.IMPORTANCE_HIGH);
            notificationManager.createNotificationChannel(channel);
        }
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, "schedule_channel")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(message)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(message))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true);
        notificationManager.notify(1001, builder.build());
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopReminderPolling();

        // Free up TTS resources
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
    }
}
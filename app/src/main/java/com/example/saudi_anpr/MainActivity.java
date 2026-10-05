package com.example.saudi_anpr;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.util.Base64;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.cardview.widget.CardView;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.exifinterface.media.ExifInterface;

import com.example.saudi_anpr.api.AnprApiService;
import com.example.saudi_anpr.api.DetectResponse;
import com.example.saudi_anpr.api.RetrofitClient;
import com.example.saudi_anpr.api.ScheduleResponse;
import com.google.common.util.concurrent.ListenableFuture;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.RequestBody;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

import android.content.Intent;

public class MainActivity extends AppCompatActivity implements TextToSpeech.OnInitListener {

    private static final String TAG = "ANPR_APP";
    private static final int REQUEST_CODE_PERMISSIONS = 10;
    private static final String[] REQUIRED_PERMISSIONS = new String[]{Manifest.permission.CAMERA};
    private static final long POLLING_INTERVAL = 10000; // responsive foreground schedule sync

    private static final String PREFS_NAME = "AnprReminderPrefs";
    private static final String KEY_LAST_NOTIFIED_SCHEDULE = "last_notified_schedule";
    private static final String KEY_LAST_FIVE_MIN_NOTIFIED = "last_five_min_notified";

    // Reminder UI Components
    private CardView reminderCard;
    private TextView reminderTitle;
    private TextView reminderLocation;
    private TextView reminderTime;
    private View reminderAlertContainer;
    private TextView reminderAlert;
    private TextView reminderCoords;
    private ImageView btnCloseReminder;

    // Camera & ANPR UI
    private PreviewView viewFinder;
    private TextView plateTextView;
    private TextView timestampTextView;
    private ImageView resultImageView;
    private ProgressBar progressBar;
    private Button captureButton;

    private ImageCapture imageCapture;
    private ExecutorService cameraExecutor;
    private final Handler reminderHandler = new Handler(Looper.getMainLooper());
    private Runnable checkReminderRunnable;

    // Text-to-Speech
    private TextToSpeech tts;
    private boolean isTtsInitialized = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Initialize TTS
        tts = new TextToSpeech(this, this);

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

        // Initialize Camera UI
        viewFinder = findViewById(R.id.viewFinder);
        plateTextView = findViewById(R.id.plateTextView);
        timestampTextView = findViewById(R.id.timestampTextView);
        resultImageView = findViewById(R.id.resultImageView);
        progressBar = findViewById(R.id.progressBar);
        captureButton = findViewById(R.id.captureButton);

        if (captureButton != null) {
            captureButton.setOnClickListener(v -> takePhoto());
        }

        cameraExecutor = Executors.newSingleThreadExecutor();

        if (allPermissionsGranted()) {
            startCamera();
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS);
        }

        // Schedule polling also runs while ANPR is open so a scheduled navigation
        // popup can appear on top of the live ANPR screen.
    }

    @Override
    protected void onResume() {
        super.onResume();
        startReminderPolling();
    }

    @Override
    protected void onPause() {
        stopReminderPolling();
        super.onPause();
    }

    @Override
    public void onInit(int status) {
        if (status == TextToSpeech.SUCCESS) {
            int result = tts.setLanguage(Locale.US);
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e(TAG, "TTS Language not supported");
            } else {
                isTtsInitialized = true;
                Log.d(TAG, "TTS Initialized successfully");
            }
        } else {
            Log.e(TAG, "TTS Initialization failed");
        }
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
        String today = new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date());
        return today + "_" + item.location.trim() + "_" + item.startTime;
    }

    private int timeToMinutes(String timeStr) {
        if (timeStr == null || !timeStr.contains(":")) return -1;
        try {
            String[] parts = timeStr.split(":");
            return Integer.parseInt(parts[0]) * 60 + Integer.parseInt(parts[1]);
        } catch (Exception e) {
            return -1;
        }
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);
        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();
                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(viewFinder.getSurfaceProvider());
                imageCapture = new ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .setTargetRotation(viewFinder.getDisplay().getRotation())
                        .build();
                CameraSelector cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA;
                try {
                    cameraProvider.unbindAll();
                    cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture);
                } catch (Exception exc) {
                    Log.e(TAG, "Use case binding failed", exc);
                }
            } catch (ExecutionException | InterruptedException e) {
                Log.e(TAG, "Camera provider initialization failed", e);
                Thread.currentThread().interrupt();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void takePhoto() {
        if (imageCapture == null) {
            Toast.makeText(this, "Camera not ready", Toast.LENGTH_SHORT).show();
            return;
        }
        File photoFile;
        try {
            photoFile = createImageFile();
        } catch (IOException e) {
            Toast.makeText(this, "Failed to create file", Toast.LENGTH_SHORT).show();
            return;
        }
        ImageCapture.OutputFileOptions outputOptions = new ImageCapture.OutputFileOptions.Builder(photoFile).build();
        if (progressBar != null) progressBar.setVisibility(View.VISIBLE);
        if (plateTextView != null) plateTextView.setText("Capturing...");
        if (captureButton != null) captureButton.setEnabled(false);

        final File finalPhotoFile = photoFile;
        imageCapture.takePicture(outputOptions, ContextCompat.getMainExecutor(this), new ImageCapture.OnImageSavedCallback() {
            @Override
            public void onImageSaved(@NonNull ImageCapture.OutputFileResults outputFileResults) {
                processAndSendImage(finalPhotoFile);
            }
            @Override
            public void onError(@NonNull ImageCaptureException exception) {
                runOnUiThread(() -> {
                    if (progressBar != null) progressBar.setVisibility(View.GONE);
                    if (plateTextView != null) plateTextView.setText("Capture failed");
                    if (captureButton != null) captureButton.setEnabled(true);
                });
            }
        });
    }

    private File createImageFile() throws IOException {
        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
        File storageDir = getExternalFilesDir(Environment.DIRECTORY_PICTURES);
        if (storageDir != null && !storageDir.exists()) storageDir.mkdirs();
        return File.createTempFile("ANPR_" + timeStamp + "_", ".jpg", storageDir);
    }

    private void processAndSendImage(File originalFile) {
        new Thread(() -> {
            try {
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(originalFile.getAbsolutePath(), options);
                int inSampleSize = 1;
                if (options.outHeight > 1024 || options.outWidth > 1024) {
                    while ((options.outHeight / (inSampleSize * 2)) >= 1024 && (options.outWidth / (inSampleSize * 2)) >= 1024) {
                        inSampleSize *= 2;
                    }
                }
                options.inJustDecodeBounds = false;
                options.inSampleSize = inSampleSize;
                Bitmap bitmap = BitmapFactory.decodeFile(originalFile.getAbsolutePath(), options);

                if (bitmap == null) {
                    throw new IOException("Could not decode captured image");
                }

                ExifInterface exif = new ExifInterface(originalFile.getAbsolutePath());
                int orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
                if (orientation != ExifInterface.ORIENTATION_NORMAL) {
                    Matrix matrix = new Matrix();
                    if (orientation == ExifInterface.ORIENTATION_ROTATE_90) matrix.postRotate(90);
                    else if (orientation == ExifInterface.ORIENTATION_ROTATE_180) matrix.postRotate(180);
                    else if (orientation == ExifInterface.ORIENTATION_ROTATE_270) matrix.postRotate(270);
                    bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
                }

                final int maxUploadDimension = 1280;
                int width = bitmap.getWidth();
                int height = bitmap.getHeight();
                int largestDimension = Math.max(width, height);
                if (largestDimension > maxUploadDimension) {
                    float scale = maxUploadDimension / (float) largestDimension;
                    Bitmap resized = Bitmap.createScaledBitmap(
                            bitmap,
                            Math.max(1, Math.round(width * scale)),
                            Math.max(1, Math.round(height * scale)),
                            true
                    );
                    if (resized != bitmap) bitmap.recycle();
                    bitmap = resized;
                }

                File optimizedFile = new File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), "upload_optimized.jpg");
                try (FileOutputStream out = new FileOutputStream(optimizedFile)) {
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 70, out);
                }
                bitmap.recycle();
                sendImageToServer(optimizedFile);
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (progressBar != null) progressBar.setVisibility(View.GONE);
                    if (captureButton != null) captureButton.setEnabled(true);
                });
            }
        }).start();
    }

    private boolean isNetworkAvailable() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo activeNetwork = cm != null ? cm.getActiveNetworkInfo() : null;
        return activeNetwork != null && activeNetwork.isConnected();
    }

    private void sendImageToServer(File imageFile) {
        if (!isNetworkAvailable()) {
            runOnUiThread(() -> {
                if (progressBar != null) progressBar.setVisibility(View.GONE);
                if (captureButton != null) captureButton.setEnabled(true);
                Toast.makeText(this, "No Internet", Toast.LENGTH_LONG).show();
            });
            return;
        }
        RequestBody requestFile = RequestBody.create(imageFile, MediaType.parse("image/jpeg"));
        MultipartBody.Part filePart = MultipartBody.Part.createFormData("file", imageFile.getName(), requestFile);
        RetrofitClient.getService().detectPlate(filePart).enqueue(new Callback<DetectResponse>() {
            @Override
            public void onResponse(@NonNull Call<DetectResponse> call, @NonNull Response<DetectResponse> response) {
                runOnUiThread(() -> {
                    if (progressBar != null) progressBar.setVisibility(View.GONE);
                    if (captureButton != null) captureButton.setEnabled(true);
                    if (response.isSuccessful() && response.body() != null) {
                        DetectResponse result = response.body();
                        plateTextView.setText(result.plates != null && !result.plates.isEmpty() ? String.join(", ", result.plates) : "No plate detected");
                        if (timestampTextView != null && result.timestamp != null) timestampTextView.setText(result.timestamp);
                        if (result.imageBase64 != null && resultImageView != null) {
                            byte[] decodedImage = Base64.decode(result.imageBase64, Base64.DEFAULT);
                            resultImageView.setVisibility(View.VISIBLE);
                            resultImageView.setImageBitmap(
                                    BitmapFactory.decodeByteArray(decodedImage, 0, decodedImage.length)
                            );
                        }
                    }
                });
            }
            @Override
            public void onFailure(@NonNull Call<DetectResponse> call, @NonNull Throwable t) {
                runOnUiThread(() -> {
                    if (progressBar != null) progressBar.setVisibility(View.GONE);
                    if (captureButton != null) captureButton.setEnabled(true);
                });
            }
        });
    }

    private void startReminderPolling() {
        stopReminderPolling();
        checkReminderRunnable = () -> {
            fetchSchedule();
            reminderHandler.postDelayed(checkReminderRunnable, POLLING_INTERVAL);
        };
        reminderHandler.post(checkReminderRunnable);
    }

    private void stopReminderPolling() {
        if (checkReminderRunnable != null) {
            reminderHandler.removeCallbacks(checkReminderRunnable);
        }
    }

    private void fetchSchedule() {
        if (!isNetworkAvailable()) return;
        RetrofitClient.getService().getSchedule().enqueue(new Callback<ScheduleResponse>() {
            @Override
            public void onResponse(@NonNull Call<ScheduleResponse> call, @NonNull Response<ScheduleResponse> response) {
                if (response.isSuccessful() && response.body() != null && response.body().success) {
                    updateReminderUI(response.body().data);
                }
            }
            @Override
            public void onFailure(@NonNull Call<ScheduleResponse> call, @NonNull Throwable t) {
                Log.e(TAG, "Network error fetching schedule", t);
            }
        });
    }

    private void updateReminderUI(ScheduleResponse.ScheduleData data) {
        if (data == null) return;

        runOnUiThread(() -> {
            String scheduleCurrentTime = (data.currentTime != null && !data.currentTime.trim().isEmpty())
                    ? data.currentTime.trim()
                    : new SimpleDateFormat("HH:mm", Locale.US).format(new Date());
            if (reminderTime != null) {
                reminderTime.setText("Current Time: " + scheduleCurrentTime);
            }

            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

            // First alert: exactly five minutes before the upcoming schedule.
            if (data.upcoming != null && data.currentTime != null) {
                int currentMins = timeToMinutes(scheduleCurrentTime);
                int upcomingMins = timeToMinutes(data.upcoming.startTime);
                if (currentMins != -1 && upcomingMins != -1) {
                    int diff = upcomingMins - currentMins;
                    if (diff < 0) diff += 24 * 60;

                    if (diff == 5) {
                        String fiveMinuteKey = getScheduleKey(data.upcoming) + "_5min";
                        String lastFiveMinuteKey = prefs.getString(KEY_LAST_FIVE_MIN_NOTIFIED, "");
                        if (!fiveMinuteKey.equals(lastFiveMinuteKey)) {
                            prefs.edit().putString(KEY_LAST_FIVE_MIN_NOTIFIED, fiveMinuteKey).apply();
                            reminderCard.setVisibility(View.VISIBLE);
                            reminderTitle.setText("UPCOMING REMINDER");
                            reminderLocation.setText(data.upcoming.location);
                            reminderAlertContainer.setVisibility(View.VISIBLE);
                            reminderAlert.setText("GO TO THIS LOCATION IN 5 MINUTES");
                            showSystemNotification(
                                    "Upcoming Location",
                                    "In 5 minutes, go to " + data.upcoming.location + "."
                            );
                            if (isTtsInitialized && tts != null) {
                                tts.speak(
                                        "Reminder. In 5 minutes, go to " + data.upcoming.location + ".",
                                        TextToSpeech.QUEUE_FLUSH,
                                        null,
                                        "five_minute_" + fiveMinuteKey
                                );
                            }
                        }
                    }
                }
            }

            String currentScheduleKey = getScheduleKey(data.active);
            String lastNotified = getLastNotifiedSchedule();

            if (data.active != null) {
                int currentMins = timeToMinutes(scheduleCurrentTime);
                int startMins = timeToMinutes(data.active.startTime);
                int endMins = timeToMinutes(data.active.endTime);
                boolean isInsideActiveWindow = currentMins != -1
                        && startMins != -1
                        && currentMins >= startMins
                        && (endMins == -1 || currentMins <= endMins);

                // Trigger once when the schedule is active, tolerating polling/network delay.
                if (isInsideActiveWindow && !lastNotified.equals(currentScheduleKey)) {
                    // Keep the existing ANPR screen untouched until the scheduled
                    // time is reached. At that moment, show the same navigation
                    // activity used by the original working project.
                    setLastNotifiedSchedule(currentScheduleKey);

                    String notifMessage = "Go now to " + data.active.location + ".";
                    showSystemNotification("Location Time", notifMessage);

                    if (isTtsInitialized && tts != null) {
                        String spokenText = "Location alert. Go now to " + data.active.location;
                        if (data.active.latitude != 0.0 || data.active.longitude != 0.0) {
                            spokenText += ". Coordinates are: Latitude " + data.active.latitude + ", Longitude " + data.active.longitude;
                        }
                        tts.speak(spokenText, TextToSpeech.QUEUE_FLUSH, null,
                                "main_schedule_" + currentScheduleKey);
                    }

                    openReminderScreen(
                            "LOCATION TIME",
                            data.active,
                            notifMessage
                    );
                }

                if (reminderTitle != null) reminderTitle.setText("CURRENT LOCATION");
                if (reminderLocation != null) {
                    reminderLocation.setText(data.active.location);
                    reminderLocation.setTextColor(ContextCompat.getColor(this, R.color.white));
                }

                if (data.upcoming != null) {
                    reminderAlertContainer.setVisibility(View.VISIBLE);
                    reminderAlert.setText("NEXT: " + data.upcoming.location + " at " + data.upcoming.startTime);
                    reminderAlert.setTextColor(ContextCompat.getColor(this, R.color.primary_blue));
                    reminderCoords.setText(String.format(Locale.US, "📍 Dest: %.6f, %.6f", data.upcoming.latitude, data.upcoming.longitude));
                    reminderCoords.setTextColor(ContextCompat.getColor(this, R.color.primary_blue));
                } else {
                    reminderAlertContainer.setVisibility(View.GONE);
                }

            } else if (data.upcoming != null) {
                reminderCard.setVisibility(View.VISIBLE);
                if (reminderTitle != null) reminderTitle.setText("NEXT LOCATION");
                if (reminderLocation != null) {
                    reminderLocation.setText(data.upcoming.location);
                    reminderLocation.setTextColor(ContextCompat.getColor(this, R.color.primary_blue));
                }
                reminderAlertContainer.setVisibility(View.VISIBLE);
                reminderAlert.setText("Starts at " + data.upcoming.startTime);
                reminderAlert.setTextColor(ContextCompat.getColor(this, R.color.primary_blue));
                reminderCoords.setText(String.format(Locale.US, "📍 Dest: %.6f, %.6f", data.upcoming.latitude, data.upcoming.longitude));
                reminderCoords.setTextColor(ContextCompat.getColor(this, R.color.primary_blue));
            } else {
                reminderCard.setVisibility(View.GONE);
            }
        });
    }

    private void openReminderScreen(
            String title,
            ScheduleResponse.ScheduleItem item,
            String message
    ) {
        if (item == null) return;

        Intent intent = new Intent(MainActivity.this, ReminderActivity.class);
        intent.putExtra(ReminderActivity.EXTRA_TITLE, title);
        intent.putExtra(ReminderActivity.EXTRA_LOCATION, item.location);
        intent.putExtra(ReminderActivity.EXTRA_MESSAGE, message);
        intent.putExtra(ReminderActivity.EXTRA_START_TIME, item.startTime);
        intent.putExtra(ReminderActivity.EXTRA_LATITUDE, item.latitude);
        intent.putExtra(ReminderActivity.EXTRA_LONGITUDE, item.longitude);

        // This is the original working navigation screen. It is placed above
        // the current LPR activity, so CLOSE returns to the live ANPR screen.
        startActivity(intent);
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

    private boolean allPermissionsGranted() {
        for (String permission : REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) startCamera();
            else {
                Toast.makeText(this, "Camera permission is required.", Toast.LENGTH_SHORT).show();
                finish();
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (checkReminderRunnable != null) reminderHandler.removeCallbacks(checkReminderRunnable);
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
        cameraExecutor.shutdown();
    }
}
package com.example.saudi_anpr.scanner;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.os.Bundle;
import android.os.Build;
import android.util.Log;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.example.saudi_anpr.R;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.barcode.BarcodeScanner;
import com.google.mlkit.vision.barcode.BarcodeScannerOptions;
import com.google.mlkit.vision.barcode.BarcodeScanning;
import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.common.InputImage;
import com.vuzix.sdk.barcode.BarcodeType2;
import com.vuzix.sdk.barcode.ScanResult2;
import com.vuzix.sdk.barcode.ScannerIntent;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Compact QR scanner for both phone and Vuzix M400/M4000.
 *
 * The previous M400 flow depended on launching the external Vuzix scanner UI.
 * This version uses the same small CameraX + ML Kit QR scanner on the M400,
 * which gives us a compact centered scan window and avoids depending on the
 * external scanner application's UI being available.
 *
 * If CameraX cannot start on a Vuzix device, the code falls back to the Vuzix
 * intent scanner as a last resort.
 */
public final class QrScannerActivity extends AppCompatActivity {

    public static final String EXTRA_QR_TEXT = "qr_text";

    private static final String TAG = "QrScannerActivity";
    private static final int REQUEST_CAMERA = 9102;
    private static final int REQUEST_VUZIX_SCAN = 9101;

    private PreviewView previewView;
    private TextView statusText;
    private ExecutorService cameraExecutor;
    private BarcodeScanner barcodeScanner;
    private ProcessCameraProvider cameraProvider;
    private final AtomicBoolean resultDelivered = new AtomicBoolean(false);
    private boolean vuzixDevice;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_qr_scanner);

        previewView = findViewById(R.id.qrPreview);
        statusText = findViewById(R.id.scannerStatus);

        if (previewView != null) {
            previewView.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);
            previewView.setScaleType(PreviewView.ScaleType.FILL_CENTER);
        }

        View cancelButton = findViewById(R.id.cancelScannerButton);
        if (cancelButton != null) {
            cancelButton.setOnClickListener(v -> cancelScan());
        }

        cameraExecutor = Executors.newSingleThreadExecutor();
        vuzixDevice = isVuzixDevice();

        // Always try the compact in-app scanner first. This makes the M400
        // independent of whether its external Vuzix scanner UI is installed.
        startCompactCameraScanner();
    }

    private boolean isVuzixDevice() {
        String manufacturer = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER;
        String model = Build.MODEL == null ? "" : Build.MODEL;
        return manufacturer.toLowerCase(java.util.Locale.US).contains("vuzix")
                || model.toLowerCase(java.util.Locale.US).contains("m400")
                || model.toLowerCase(java.util.Locale.US).contains("m4000");
    }

    private void startCompactCameraScanner() {
        if (isFinishing()) return;

        statusText.setText(vuzixDevice
                ? "Look at the QR code inside the small box"
                : "Point the camera at the QR code");

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                    this,
                    new String[]{Manifest.permission.CAMERA},
                    REQUEST_CAMERA
            );
            return;
        }

        startCamera();
    }

    private void startCamera() {
        BarcodeScannerOptions options = new BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build();

        barcodeScanner = BarcodeScanning.getClient(options);

        ListenableFuture<ProcessCameraProvider> providerFuture =
                ProcessCameraProvider.getInstance(this);

        providerFuture.addListener(() -> {
            try {
                cameraProvider = providerFuture.get();
                bindCamera(cameraProvider);
            } catch (Exception e) {
                Log.e(TAG, "Compact camera scanner failed", e);
                if (vuzixDevice) {
                    launchVuzixFallback();
                } else {
                    Toast.makeText(this, "Could not start the camera scanner.", Toast.LENGTH_LONG).show();
                    cancelScan();
                }
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void bindCamera(ProcessCameraProvider provider) {
        if (resultDelivered.get()) return;

        provider.unbindAll();

        Preview preview = new Preview.Builder().build();
        preview.setSurfaceProvider(previewView.getSurfaceProvider());

        ImageAnalysis analysis = new ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .build();
        analysis.setAnalyzer(cameraExecutor, this::analyzeFrame);

        CameraSelector selector = CameraSelector.DEFAULT_BACK_CAMERA;

        try {
            provider.bindToLifecycle(this, selector, preview, analysis);
        } catch (Exception e) {
            Log.e(TAG, "Failed to bind compact camera scanner", e);
            if (vuzixDevice) {
                launchVuzixFallback();
            } else {
                Toast.makeText(this, "Unable to access the camera.", Toast.LENGTH_LONG).show();
                cancelScan();
            }
        }
    }

    private void analyzeFrame(@NonNull ImageProxy imageProxy) {
        if (resultDelivered.get()) {
            imageProxy.close();
            return;
        }

        if (imageProxy.getFormat() != ImageFormat.YUV_420_888) {
            imageProxy.close();
            return;
        }

        android.media.Image mediaImage = imageProxy.getImage();
        if (mediaImage == null || barcodeScanner == null) {
            imageProxy.close();
            return;
        }

        InputImage image = InputImage.fromMediaImage(
                mediaImage,
                imageProxy.getImageInfo().getRotationDegrees()
        );

        barcodeScanner.process(image)
                .addOnSuccessListener(barcodes -> {
                    if (resultDelivered.get()) return;
                    for (Barcode barcode : barcodes) {
                        String raw = barcode.getRawValue();
                        if (raw != null && !raw.trim().isEmpty()) {
                            deliverResult(raw.trim());
                            return;
                        }
                    }
                })
                .addOnFailureListener(e -> Log.d(TAG, "QR frame processing failed", e))
                .addOnCompleteListener(task -> imageProxy.close());
    }

    private void deliverResult(String value) {
        if (!resultDelivered.compareAndSet(false, true)) return;

        runOnUiThread(() -> {
            if (cameraProvider != null) {
                cameraProvider.unbindAll();
            }
            Intent resultIntent = new Intent();
            resultIntent.putExtra(EXTRA_QR_TEXT, value);
            setResult(Activity.RESULT_OK, resultIntent);
            finish();
        });
    }

    private void launchVuzixFallback() {
        if (resultDelivered.get()) return;
        statusText.setText("Opening Vuzix QR scanner…");

        try {
            Intent scannerIntent = new Intent(ScannerIntent.ACTION);
            scannerIntent.putExtra(
                    ScannerIntent.EXTRA_BARCODE2_TYPES,
                    new String[]{BarcodeType2.QR_CODE.name()}
            );
            startActivityForResult(scannerIntent, REQUEST_VUZIX_SCAN);
        } catch (ActivityNotFoundException e) {
            Log.e(TAG, "Vuzix scanner app unavailable", e);
            Toast.makeText(this, "Vuzix QR scanner unavailable.", Toast.LENGTH_LONG).show();
            cancelScan();
        } catch (RuntimeException e) {
            Log.e(TAG, "Vuzix fallback scanner failed", e);
            cancelScan();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_VUZIX_SCAN) return;

        if (resultCode != Activity.RESULT_OK || data == null) {
            cancelScan();
            return;
        }

        try {
            ScanResult2 result = data.getParcelableExtra(
                    ScannerIntent.RESULT_EXTRA_SCAN_RESULT2
            );
            String text = result == null ? null : result.getText();
            if (text == null || text.trim().isEmpty()) {
                cancelScan();
            } else {
                deliverResult(text.trim());
            }
        } catch (RuntimeException e) {
            Log.e(TAG, "Unable to read Vuzix scan result", e);
            cancelScan();
        }
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            @NonNull String[] permissions,
            @NonNull int[] grantResults
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_CAMERA) return;

        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            Toast.makeText(this, "Camera permission is required for QR scanning.", Toast.LENGTH_LONG).show();
            cancelScan();
        }
    }

    private void cancelScan() {
        if (resultDelivered.compareAndSet(false, true)) {
            setResult(Activity.RESULT_CANCELED);
        }
        finish();
    }

    @Override
    protected void onDestroy() {
        if (cameraProvider != null) {
            cameraProvider.unbindAll();
        }
        if (barcodeScanner != null) {
            barcodeScanner.close();
            barcodeScanner = null;
        }
        if (cameraExecutor != null) {
            cameraExecutor.shutdownNow();
            cameraExecutor = null;
        }
        super.onDestroy();
    }
}

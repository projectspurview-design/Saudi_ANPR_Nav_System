# M400 QR Scanner integration

The project contains the scanner source inside the same Android app:

```text
app/src/main/java/com/example/saudi_anpr/scanner/QrScannerActivity.java
```

Flow:

```text
LockActivity
   -> QrScannerActivity
   -> Vuzix Barcode SDK Intent
   -> M400 built-in Barcode Scanner UI
   -> QR result returned to QrScannerActivity
   -> LockActivity validates the QR payload format
   -> PIN + Firebase validation
```

The scanner is restricted to `QR_CODE` so it does not accept arbitrary barcode formats.

The current Vuzix Barcode SDK 2.x integration uses the Intent mechanism. Vuzix documents this as the supported low-code mechanism for current hardware, where the built-in scanner application provides the scanning UI and returns the scan result.

The project does not copy the Vuzix binary SDK into the ZIP. Gradle resolves the SDK from Vuzix's JitPack-hosted Maven artifact:

```text
implementation 'com.vuzix:sdk-barcode:2.0.0'
```

The M400 must have its built-in Vuzix Barcode Scanner application available. If it is unavailable, the app returns to the lock screen and displays an error.

Voice path:

```text
Hello Vuzix -> Scan QR -> QrScannerActivity -> Vuzix scanner
```

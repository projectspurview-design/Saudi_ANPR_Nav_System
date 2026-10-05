# Saudi ANPR Secure Reseller Build

This is the single consolidated project for the reseller lock.

## App flow

```text
APK launch
  ↓
LockActivity
  ↓
SCAN QR (M400 touchpad or "Hello Vuzix" → "Scan QR")
  ↓
QrScannerActivity
  ↓
M400 built-in QR scanner
  ↓
Six-digit PIN
  ↓
Firebase Anonymous Auth
  ↓
Cloud Function validateLicense
  ↓
30-day server-side license + first-device binding
  ↓
HomeActivity
  ↓
Existing Saudi ANPR application
```

## What is fixed

- `LockActivity` is the launcher.
- QR uses a dedicated `QrScannerActivity` which launches the M400 built-in Vuzix Barcode Scanner intent and limits scanning to QR.
- Voice commands are registered through the Vuzix Speech SDK.
- `VoiceReceiver` catches the Android 13+ exported-receiver requirement and handles `RemoteException` correctly.
- Firebase Functions are explicitly called in `asia-south1`.
- QR token and PIN are never stored in the Android source.
- The PIN is checked server-side as a SHA-256 hash.
- First successful activation starts 30 days.
- The first successful M400 becomes the bound device.
- Five failed credential attempts create a 15-minute server-side lockout.
- The session locks again when the whole app leaves the foreground.
- Home/Main/Reminder internal navigation does not trigger the lock.
- Home periodically rechecks the server license. Temporary connectivity loss can use the last server-confirmed expiry; explicit server denials still lock immediately.
- Duplicate INTERNET permission and obsolete manifest `package` attribute were removed.
- The old unlocked `HomeActivity` launcher is gone.

## Firebase setup before testing

1. Firebase Console → Security → Authentication → Sign-in method → Anonymous → Enable.
2. Deploy the included Cloud Functions and Firestore rules:

```text
firebase login
firebase use anpr-eef87
firebase deploy --only functions,firestore
```

3. Provision the license from `admin/` on the developer machine.
4. Build a **signed release APK** from Android Studio.

## Important delivery rule

The reseller receives only:

- the signed APK
- `qr/SAUDI_RESELLER_QR.png`
- the six-digit PIN, delivered separately

Do not send the Android Studio source, Firebase service-account JSON, or `admin/` folder to the reseller.

## Vuzix SDKs

The project references:

- Vuzix Barcode SDK 2.0.0
- Vuzix Speech Recognition Service 1.98.0

The SDKs are binary third-party artifacts and are intentionally referenced through Gradle/JitPack rather than redistributed in source. Vuzix states that use of these SDKs is subject to its SDK license.

## ANPR backend

The existing ANPR backend URL/configuration in `api/RetrofitClient.java` has not been replaced. Set it to the actual reseller deployment endpoint before producing the final APK.

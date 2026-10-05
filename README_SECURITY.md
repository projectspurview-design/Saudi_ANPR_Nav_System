# Saudi ANPR — Secure Reseller Build

This is the consolidated source project for the production reseller lock.

## Runtime flow

1. APK launches into `LockActivity`.
2. M400 touchpad selects **SCAN QR**, or voice command **Hello Vuzix → Scan QR** opens the built-in Vuzix QR scanner.
3. The QR payload is checked for the expected prefix/license ID. The opaque QR token is kept only in memory.
4. The reseller scans the one-scan QR; the QR carries the six-digit Firebase Auth password.
5. The Android app signs in anonymously to Firebase and calls the `validateLicense` callable in `asia-south1`.
6. The server compares SHA-256 hashes, checks status, applies failed-attempt throttling, starts the 30-day period on first successful activation, and binds the license to the first M400 Android ID.
7. On success, `HomeActivity` opens.
8. The existing ANPR application remains behind `HomeActivity`.
9. When the entire app leaves the foreground, the in-memory session locks again. Returning to the app requires one-scan QR + Firebase validation.
10. While running, the app periodically rechecks the license. Explicit server revocation/expiry/device mismatch locks immediately.

## Firebase

Enable **Anonymous** authentication in Firebase Console. The Android app uses anonymous authentication only to obtain an authenticated Firebase token for the callable license functions; it does not expose an email/password login screen.

Deploy:

```text
firebase login
firebase use anpr-eef87
firebase deploy --only functions,firestore
```

The Firestore rules deny all direct client access to the license records. License validation is performed by the callable functions. Firebase callable functions automatically carry the Firebase Authentication token to the backend. See the Firebase documentation for callable functions and anonymous authentication.

## License model

Firestore document:

```text
licenses/SAUDI-RESELLER-001
```

Stored secrets are hashes only:

```text
qrTokenHash
pinHash
```

The raw QR token and PIN are never embedded in `SecurityConfig.java`.

First successful activation:

```text
activatedAt = server time
expiresAt   = activatedAt + 30 days
```

The first successful M400 `ANDROID_ID` is stored as `deviceId`. A different device is rejected.

## Delivery

The reseller should receive only:

- signed release APK
- `delivery/SAUDI_RESELLER_QR.png`
- six-digit PIN separately

Do not send:

- `admin/`
- Firebase service-account JSON
- Android Studio source project
- raw QR token

## Existing ANPR backend

The existing backend configuration in `api/RetrofitClient.java` is intentionally retained. Set it to the actual deployment endpoint before producing the final release APK.

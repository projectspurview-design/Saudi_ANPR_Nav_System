# Firebase integration — Saudi ANPR

This project is already wired to the Firebase project `anpr-eef87`.

## Android configuration

- `app/google-services.json` matches package `com.example.saudi_anpr`.
- Project-level plugin: `com.google.gms.google-services:4.5.0`.
- Firebase Android BoM: `34.19.0`.
- Firebase Auth, Firestore and Cloud Functions are declared in `app/build.gradle`.
- `SaudiAnprApplication` explicitly initializes `FirebaseApp`.
- Callable Functions use region `asia-south1`.

## Firebase console prerequisites

Enable **Authentication → Anonymous** for the Android app.

Deploy the included backend from the project root:

```text
firebase login
firebase use anpr-eef87
firebase deploy --only functions,firestore
```

The Android app does not contain a Firebase Admin/service-account credential. Only the public Android Firebase configuration is shipped in `google-services.json`; Firebase describes that file as containing unique, non-secret project/app identifiers.

## License flow

```text
LockActivity
  -> QrScannerActivity
  -> Vuzix built-in QR Scanner
  -> one-scan QR containing license ID, Firebase Auth email and six-digit PIN
  -> Firebase Anonymous Auth
  -> asia-south1 validateLicense callable
  -> first-device binding + 30-day expiry
  -> HomeActivity
```

The raw PIN and QR token are not stored in the Android source.

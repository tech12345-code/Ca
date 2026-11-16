# Smart Expense Manager — Android Studio & Signed APK Build Guide

## 1. Prerequisites
- Android Studio Ladybug (2024.2+) or newer
- JDK 17
- Android SDK Platform 35 (`compileSdk = 35`, `minSdk = 26` / Android 8.0+)
- Firebase project (`river-host-cv0ns` or your own Firebase project)

## 2. Firebase Configuration
1. Open the Firebase Console for project `river-host-cv0ns` (or your project).
2. Add an Android app with package name **`com.smartexpense.manager`**.
3. Add your Debug and Release SHA-1 / SHA-256 signing fingerprints (required for Google Credential Manager Sign-In).
4. Download `google-services.json` and place it at:
   `android-project/app/google-services.json`
5. Enable **Authentication -> Google Sign-In**, **Cloud Firestore**, and **Cloud Messaging (FCM)**.
6. Deploy the hardened rules located in `/firestore.rules`:
   ```bash
   firebase deploy --only firestore:rules
   ```
7. Copy the OAuth Web Client ID from `google-services.json` (`client_type: 3`) into `app/src/main/res/values/strings.xml` (`default_web_client_id`).
8. (Optional) Deploy server-side FCM triggers from `firebase-functions/`:
   ```bash
   cd firebase-functions && npm install && npm run deploy
   ```

## 3. Required Android Permissions Explained
- `CAMERA`: Capture physical paper bills for on-device ML Kit OCR.
- `POST_NOTIFICATIONS` (Android 13+): Display budget alerts, reminders, and transaction confirmations ("Where did you spend this money?").
- `INTERNET` & `ACCESS_NETWORK_STATE`: Synchronize local Room ledger with Cloud Firestore via WorkManager when online.
- `<queries>` package visibility: Check whether Google Pay, PhonePe, Paytm, BHIM, or Amazon Pay are installed without scraping private data or using Accessibility services.

## 4. Building Debug & Signed Release APK
### Debug APK
```bash
./gradlew assembleDebug
# Output: app/build/outputs/apk/debug/app-debug.apk
```

### Signed Release APK
1. Generate a release keystore:
   ```bash
   keytool -genkey -v -keystore release-keystore.jks \
     -keyalg RSA -keysize 2048 -validity 10000 -alias smart_expense_key
   ```
2. Copy `keystore.properties.example` to `keystore.properties` and fill in your passwords.
3. Build the minified, resource-shrunk signed release APK:
   ```bash
   ./gradlew assembleRelease
   # Output: app/build/outputs/apk/release/app-release.apk
   ```
4. Install onto a connected device via ADB:
   ```bash
   adb install -r app/build/outputs/apk/release/app-release.apk
   ```

## 5. Implemented vs. External-Credential Features
- **Implemented in Source**: Material 3 dark theme UI, First-Time Setup, Expense/Income CRUD, Chronological `BalanceCalculator` + unit test, Room offline DB, WorkManager background sync & scheduled reminders, ML Kit OCR + `PdfRenderer` + duplicate guard, Official `<queries>` UPI detector + Share Intent receiver, Native `PdfDocument` statement exporter, 7 FCM notification channels + `QuickCategorizeReceiver`, Hardened `firestore.rules`, Signed release Gradle config.
- **Requires Your Firebase Console Credentials**: Adding `app/google-services.json` + SHA-1 fingerprint for Android Google Sign-In and deploying `firebase-functions/` on Blaze plan if using scheduled server-side FCM pushes.

## 6. Automated GitHub Actions Debug APK & Releases (`tech12345-code/Smart-Expenses`)
- **Workflow File**: `.github/workflows/android-debug-apk.yml`
- **GitHub Actions Builds**: `https://github.com/tech12345-code/Smart-Expenses/actions/workflows/android-debug-apk.yml`
- **Latest GitHub Release**: `https://github.com/tech12345-code/Smart-Expenses/releases/latest`
- **Direct APK Download URL**: `https://github.com/tech12345-code/Smart-Expenses/releases/latest/download/SmartExpenseManager-debug.apk`


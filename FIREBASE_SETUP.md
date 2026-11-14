# KaiserTV+ Firebase setup

This branch prepares the Android client and the server-side three-device registration flow. A Firebase project must be owned and configured by the app owner before live authentication can work.

## Android app registration
1. Create a Firebase project in the Firebase Console.
2. Register an Android app with package name `com.example.androtviptv`.
3. Add the app's SHA-1 and SHA-256 signing fingerprints for Google sign-in.
4. Download the generated `google-services.json` and place it at `app/google-services.json`. Do not rename it or commit it to a public repository.
5. Enable Email/Password and Google providers in Authentication.
6. For Google sign-in, configure the OAuth consent screen and use the Web client ID from the Firebase/Google configuration as the server client ID in the Android Credential Manager flow.
7. Enable Cloud Firestore and create the database in a region compatible with the selected Cloud Functions region.
8. Enable Firebase App Check and register the Android app. Use the debug provider only for local development; use Play Integrity for production.

## Cloud Functions
1. Install Firebase CLI and authenticate as the project owner.
2. Select the Firebase project with `firebase use --add`.
3. Deploy Firestore rules and functions with `firebase deploy --only firestore:rules,functions`.
4. The callable functions use `us-central1` and enforce App Check. The client must initialize App Check with Play Integrity before calling them.

## Current implementation boundary
- The Android repository contains email/password registration, sign-in, password reset, Google ID-token sign-in/linking, and device registration/removal calls.
- Cloud Functions atomically enforce a maximum of three registered Android devices per Firebase account.
- Firebase configuration and OAuth client IDs are intentionally not included in source control.
- UI wiring, Google Credential Manager token acquisition, App Check initialization, account/profile data migration, and end-to-end device revocation enforcement remain follow-up implementation and test tasks.
- IPTV source credentials, MAC addresses, tokens, and portal/playlist URLs must remain in encrypted local storage. Never copy them into cloud profile documents.

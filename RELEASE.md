# Android release setup for CodeMagic

## Build release artifacts

CodeMagic can build both formats:

- APK: `app/build/outputs/apk/release/app-release.apk`
- Play Store AAB: `app/build/outputs/bundle/release/app-release.aab`

## Add signing in CodeMagic

1. Open the app in CodeMagic.
2. Create a Java keystore for the app.
3. Upload it under **Android code signing**.
4. Add it to the environment:
   - `CM_KEYSTORE_PATH`
   - `CM_KEYSTORE_PASSWORD`
   - `CM_KEY_ALIAS`
   - `CM_KEY_PASSWORD`
5. Start the `android-release` workflow.

The signing configuration reads these environment variables automatically. Do not commit the keystore or passwords to Git.

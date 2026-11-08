# World TV

Android IPTV/M3U player built with Kotlin and AndroidX Media3.

## Features

- Plays HLS and other streams supported by ExoPlayer
- Loads `.m3u` or `.m3u8` playlists from a URL
- Imports a playlist file from device storage
- Channel names and playlist groups

## Run

1. Open this folder in Android Studio.
2. Let Gradle download the Android SDK and project dependencies.
3. Select an Android phone or emulator and run the app.

From the terminal, use:

```bash
./gradlew assembleDebug
```

The APK is generated at `app/build/outputs/apk/debug/app-debug.apk`.

Only use streams and playlists that you are authorized to access.

# AVTOHIT

AVTOHIT is an Android app that turns an MP3 plus a picture or short video into a new MP4 video.

## Build

Open this folder in Android Studio and run the `app` configuration.

Command-line build, when Gradle is available:

```powershell
.\gradlew.bat :app:assembleDebug
```

Download the current versioned APK from [GitHub Releases](https://github.com/efremandrei/Avtohit/releases/latest). Choose the `arm64-v8a` variant for a modern Samsung/Android phone. APK names include the version, build number, and architecture.

The repository also includes older prebuilt debug APKs under `artifacts/`:

- `AVTOHIT-arm64-v8a-debug.apk`
- `AVTOHIT-armeabi-v7a-debug.apk`
- `AVTOHIT-x86_64-debug.apk`

These are split by CPU architecture so each APK stays below GitHub's 100 MB file limit.

## Media Contract

Loaded photos or videos play in the numbered list order, from top to bottom. Numeric filename sorting sets the initial order only. Long-press a drag handle to move an item to the insertion line; hold near the screen edge to scroll a long list. The arranged order is preserved when the screen is recreated and when exporting.

Reorder regression checks:

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug assembleDebugAndroidTest lintDebug
adb install -r app\build\outputs\apk\debug\app-x86_64-debug.apk
adb install -r app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk
adb shell am instrument -w com.avtohit.app.test/com.avtohit.app.MediaOrderInstrumentation
```

The device checks use a 1080x3000 test display, real drag gestures, activity recreation, and encoded frame sampling for both photo and video sequences. They also check auto-scroll. Use an isolated emulator with `adb shell wm size 1080x3000`; restore it afterward with `adb shell wm size reset`.

- MP3 + picture: the picture is looped as the only video image, and the MP3 is copied into the MP4 as the soundtrack.
- MP3 + video: the original video audio is ignored, the video is looped to the MP3 length, and the MP3 is copied into the MP4 as the soundtrack.
- Audio quality is preserved by using FFmpeg `-c:a copy`; the app does not re-encode the MP3.
- Output duration is controlled by FFmpeg `-shortest`, so the generated MP4 ends when the MP3 stream ends.

## Dependency Note

The implementation uses an Android-only FFmpegKit-compatible Maven package:

```gradle
implementation "com.moizhassan.ffmpeg:ffmpeg-kit-16kb:6.1.1"
```

Android `MediaMuxer` does not list MP3 as a supported MP4 audio codec, so FFmpeg is used to preserve the MP3 stream. FFmpegKit's original upstream repository is archived and retired; the media runner is isolated so this package can be replaced later by another compatible fork or an in-house FFmpeg Android AAR.

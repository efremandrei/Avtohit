# AVTOHIT

AVTOHIT is an Android app that turns an MP3 plus a picture or short video into a new MP4 video.

## Build

Open this folder in Android Studio and run the `app` configuration.

Command-line build, when Gradle is available:

```powershell
.\gradlew.bat :app:assembleDebug
```

The repository also includes prebuilt debug APKs under `artifacts/`:

- `AVTOHIT-arm64-v8a-debug.apk`
- `AVTOHIT-armeabi-v7a-debug.apk`
- `AVTOHIT-x86_64-debug.apk`

These are split by CPU architecture so each APK stays below GitHub's 100 MB file limit.

## Media Contract

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

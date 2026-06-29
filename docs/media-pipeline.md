# AVTOHIT Media Pipeline

The app copies Storage Access Framework inputs into its cache, runs FFmpeg against normal file paths, and copies the finished MP4 into the user-selected destination.

## Picture Input

```text
ffmpeg -loop 1 -framerate 30 -i picture -i audio.mp3 \
  -map 0:v:0 -map 1:a:0 \
  -vf scale=1080:1920:force_original_aspect_ratio=decrease,pad=1080:1920:(ow-iw)/2:(oh-ih)/2,setsar=1,format=yuv420p \
  -c:v mpeg4 -q:v 3 -c:a copy -shortest -movflags +faststart output.mp4
```

## Video Input

```text
ffmpeg -stream_loop -1 -i video -i audio.mp3 \
  -map 0:v:0 -map 1:a:0 \
  -c:v copy -c:a copy -shortest -movflags +faststart output.mp4
```

If stream-copy looping fails because of timestamp or container constraints, the app retries with video re-encoding while still copying the MP3 stream:

```text
ffmpeg -fflags +genpts -stream_loop -1 -i video -i audio.mp3 \
  -map 0:v:0 -map 1:a:0 \
  -vf scale=trunc(iw/2)*2:trunc(ih/2)*2,setsar=1,format=yuv420p \
  -c:v mpeg4 -q:v 3 -c:a copy -shortest -movflags +faststart output.mp4
```

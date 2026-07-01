package com.avtohit.app.media;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.webkit.MimeTypeMap;

import com.arthenica.ffmpegkit.FFmpegKit;
import com.arthenica.ffmpegkit.FFmpegSession;
import com.arthenica.ffmpegkit.ReturnCode;
import com.arthenica.ffmpegkit.Statistics;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

public final class AvtohitProcessor {
    public enum VisualKind {
        IMAGE,
        VIDEO
    }

    public static final class Result {
        public final VisualKind visualKind;
        public final boolean videoReencoded;
        public final long outputBytes;
        public final String ffmpegOutput;

        private Result(
                VisualKind visualKind,
                boolean videoReencoded,
                long outputBytes,
                String ffmpegOutput
        ) {
            this.visualKind = visualKind;
            this.videoReencoded = videoReencoded;
            this.outputBytes = outputBytes;
            this.ffmpegOutput = ffmpegOutput;
        }
    }

    public interface ProgressListener {
        void onProgress(long currentMs, long totalMs);
    }

    public Result render(
            Context context,
            Uri audioUri,
            Uri visualUri,
            String visualMimeType,
            Uri destinationUri,
            ExportProfile exportProfile,
            int frameRate,
            long targetDurationMs,
            ProgressListener progressListener
    ) throws IOException, AvtohitException {
        ContentResolver resolver = context.getContentResolver();
        File workDir = new File(context.getCacheDir(), "avtohit");
        if (!workDir.exists() && !workDir.mkdirs()) {
            throw new IOException("Could not create AVTOHIT cache directory.");
        }

        long runId = System.currentTimeMillis();
        File audioFile = new File(workDir, "audio-" + runId + ".mp3");
        File visualFile = new File(workDir, "visual-" + runId + "." + visualExtension(resolver, visualUri, visualMimeType));
        File outputFile = new File(workDir, "output-" + runId + ".mp4");

        try {
            copyUriToFile(resolver, audioUri, audioFile);
            copyUriToFile(resolver, visualUri, visualFile);

            VisualKind visualKind = detectVisualKind(resolver, visualUri, visualMimeType);
            FFmpegSession session;
            boolean videoReencoded = visualKind == VisualKind.VIDEO;

            if (visualKind == VisualKind.IMAGE) {
                session = execute(buildImageCommand(audioFile, visualFile, outputFile, exportProfile, frameRate), targetDurationMs, progressListener);
            } else {
                session = execute(buildVideoCommand(audioFile, visualFile, outputFile, exportProfile, frameRate), targetDurationMs, progressListener);
            }

            if (!ReturnCode.isSuccess(session.getReturnCode())) {
                throw new AvtohitException("FFmpeg failed: " + session.getOutput() + "\n" + session.getFailStackTrace());
            }

            copyFileToUri(resolver, outputFile, destinationUri);
            return new Result(visualKind, videoReencoded, outputFile.length(), session.getOutput());
        } finally {
            deleteIfExists(audioFile);
            deleteIfExists(visualFile);
            deleteIfExists(outputFile);
        }
    }

    public static String displayName(Context context, Uri uri) {
        Cursor cursor = context.getContentResolver().query(uri, null, null, null, null);
        if (cursor == null) {
            return uri.getLastPathSegment();
        }
        try {
            int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
            if (nameIndex >= 0 && cursor.moveToFirst()) {
                String value = cursor.getString(nameIndex);
                if (value != null && !value.trim().isEmpty()) {
                    return value;
                }
            }
            return uri.getLastPathSegment();
        } finally {
            cursor.close();
        }
    }

    private static FFmpegSession execute(List<String> arguments, long targetDurationMs, ProgressListener progressListener) throws IOException {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<FFmpegSession> sessionRef = new AtomicReference<>();

        FFmpegKit.executeWithArgumentsAsync(
                arguments.toArray(new String[0]),
                session -> {
                    sessionRef.set(session);
                    latch.countDown();
                },
                log -> { },
                statistics -> publishProgress(statistics, targetDurationMs, progressListener)
        );

        try {
            latch.await();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("Render interrupted.", error);
        }

        return sessionRef.get();
    }

    private static void publishProgress(Statistics statistics, long targetDurationMs, ProgressListener progressListener) {
        if (progressListener == null || targetDurationMs <= 0L || statistics == null) {
            return;
        }
        long statisticTimeMs = Math.round(statistics.getTime());
        long currentMs = Math.min(Math.max(0L, statisticTimeMs), targetDurationMs);
        progressListener.onProgress(currentMs, targetDurationMs);
    }

    private static List<String> buildImageCommand(
            File audioFile,
            File imageFile,
            File outputFile,
            ExportProfile exportProfile,
            int frameRate
    ) {
        List<String> args = baseArgs();
        args.add("-loop");
        args.add("1");
        args.add("-framerate");
        args.add(String.valueOf(frameRate));
        args.add("-i");
        args.add(imageFile.getAbsolutePath());
        args.add("-i");
        args.add(audioFile.getAbsolutePath());
        args.add("-map");
        args.add("0:v:0");
        args.add("-map");
        args.add("1:a:0");
        args.add("-vf");
        args.add(buildScalePadFilter(exportProfile, frameRate));
        args.add("-c:v");
        args.add("mpeg4");
        args.add("-q:v");
        args.add("3");
        args.add("-c:a");
        args.add("copy");
        args.add("-shortest");
        args.add("-movflags");
        args.add("+faststart");
        args.add(outputFile.getAbsolutePath());
        return args;
    }

    private static List<String> buildVideoCommand(
            File audioFile,
            File videoFile,
            File outputFile,
            ExportProfile exportProfile,
            int frameRate
    ) {
        List<String> args = baseArgs();
        args.add("-fflags");
        args.add("+genpts");
        args.add("-stream_loop");
        args.add("-1");
        args.add("-i");
        args.add(videoFile.getAbsolutePath());
        args.add("-i");
        args.add(audioFile.getAbsolutePath());
        args.add("-map");
        args.add("0:v:0");
        args.add("-map");
        args.add("1:a:0");
        args.add("-vf");
        args.add(buildScalePadFilter(exportProfile, frameRate));
        args.add("-c:v");
        args.add("mpeg4");
        args.add("-q:v");
        args.add("3");
        args.add("-c:a");
        args.add("copy");
        args.add("-shortest");
        args.add("-movflags");
        args.add("+faststart");
        args.add(outputFile.getAbsolutePath());
        return args;
    }

    private static List<String> baseArgs() {
        List<String> args = new ArrayList<>();
        args.add("-hide_banner");
        args.add("-y");
        return args;
    }

    private static String buildScalePadFilter(ExportProfile exportProfile, int frameRate) {
        return "fps=" + frameRate
                + ",scale=" + exportProfile.width + ":" + exportProfile.height
                + ":force_original_aspect_ratio=decrease,pad="
                + exportProfile.width + ":" + exportProfile.height + ":(ow-iw)/2:(oh-ih)/2,setsar=1,format=yuv420p";
    }

    private static VisualKind detectVisualKind(ContentResolver resolver, Uri uri, String givenMime) throws AvtohitException {
        String mime = firstNonBlank(givenMime, resolver.getType(uri));
        if (mime != null) {
            String lower = mime.toLowerCase(Locale.US);
            if (lower.startsWith("image/")) {
                return VisualKind.IMAGE;
            }
            if (lower.startsWith("video/")) {
                return VisualKind.VIDEO;
            }
        }

        String path = uri.toString().toLowerCase(Locale.US);
        if (path.endsWith(".jpg") || path.endsWith(".jpeg") || path.endsWith(".png") || path.endsWith(".webp")) {
            return VisualKind.IMAGE;
        }
        if (path.endsWith(".mp4") || path.endsWith(".mov") || path.endsWith(".m4v") || path.endsWith(".webm")) {
            return VisualKind.VIDEO;
        }
        throw new AvtohitException("Selected visual file is not a supported picture or video.");
    }

    private static String visualExtension(ContentResolver resolver, Uri uri, String givenMime) {
        String mime = firstNonBlank(givenMime, resolver.getType(uri));
        if (mime != null) {
            String extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime);
            if (extension != null && !extension.trim().isEmpty()) {
                return extension;
            }
        }

        String path = uri.toString();
        int dot = path.lastIndexOf('.');
        if (dot >= 0 && dot < path.length() - 1) {
            String candidate = path.substring(dot + 1).toLowerCase(Locale.US);
            if (candidate.matches("[a-z0-9]{2,5}")) {
                return candidate;
            }
        }
        return "bin";
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.trim().isEmpty()) {
            return first;
        }
        if (second != null && !second.trim().isEmpty()) {
            return second;
        }
        return null;
    }

    private static void copyUriToFile(ContentResolver resolver, Uri sourceUri, File target) throws IOException {
        InputStream input = resolver.openInputStream(sourceUri);
        if (input == null) {
            throw new IOException("Could not open selected input.");
        }
        try (InputStream in = input; OutputStream out = new FileOutputStream(target)) {
            copy(in, out);
        }
    }

    private static void copyFileToUri(ContentResolver resolver, File sourceFile, Uri destinationUri) throws IOException {
        OutputStream output = resolver.openOutputStream(destinationUri, "w");
        if (output == null) {
            throw new IOException("Could not open selected output destination.");
        }
        try (InputStream in = new FileInputStream(sourceFile); OutputStream out = output) {
            copy(in, out);
        }
    }

    private static void copy(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[1024 * 256];
        int read;
        while ((read = input.read(buffer)) != -1) {
            output.write(buffer, 0, read);
        }
        output.flush();
    }

    private static void deleteIfExists(File file) {
        if (file.exists() && !file.delete()) {
            file.deleteOnExit();
        }
    }
}

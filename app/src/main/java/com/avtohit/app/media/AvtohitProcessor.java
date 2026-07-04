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
import com.avtohit.app.AvtohitDebugLogger;

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
    private static final long STALE_WORK_FILE_AGE_MS = 6L * 60L * 60L * 1000L;

    public enum VisualKind {
        IMAGE,
        VIDEO,
        VIDEO_SEQUENCE
    }

    public static final class Result {
        public final VisualKind visualKind;
        public final boolean videoReencoded;
        public final boolean usesImportedMp3;
        public final long outputBytes;
        public final String ffmpegOutput;

        private Result(
                VisualKind visualKind,
                boolean videoReencoded,
                long outputBytes,
                String ffmpegOutput
        ) {
            this(visualKind, videoReencoded, true, outputBytes, ffmpegOutput);
        }

        private Result(
                VisualKind visualKind,
                boolean videoReencoded,
                boolean usesImportedMp3,
                long outputBytes,
                String ffmpegOutput
        ) {
            this.visualKind = visualKind;
            this.videoReencoded = videoReencoded;
            this.usesImportedMp3 = usesImportedMp3;
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
        return render(context, audioUri, visualUri, visualMimeType, destinationUri, exportProfile, frameRate, targetDurationMs, progressListener, null);
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
            ProgressListener progressListener,
            AvtohitDebugLogger debugLogger
    ) throws IOException, AvtohitException {
        if (audioUri == null || visualUri == null || destinationUri == null) {
            throw new AvtohitException("Audio, visual, and output targets must all be selected.");
        }
        if (targetDurationMs <= 0L) {
            throw new AvtohitException("Selected MP3 has no readable duration.");
        }

        ContentResolver resolver = context.getContentResolver();
        File workDir = new File(context.getCacheDir(), "avtohit");
        if (!workDir.exists() && !workDir.mkdirs()) {
            throw new IOException("Could not create AVTOHIT cache directory.");
        }

        pruneStaleWorkFiles(workDir);
        long runId = System.currentTimeMillis();
        File audioFile = new File(workDir, "audio-" + runId + ".mp3");
        File visualFile = new File(workDir, "visual-" + runId + "." + visualExtension(resolver, visualUri, visualMimeType));
        File outputFile = new File(workDir, "output-" + runId + ".mp4");

        try {
            // Work from cache copies so SAF streams stay stable for FFmpeg and never leak raw provider paths.
            log(debugLogger, "copy_audio_to_cache target=" + audioFile.getAbsolutePath());
            copyUriToFile(resolver, audioUri, audioFile);
            log(debugLogger, "audio_cache_bytes=" + audioFile.length());
            log(debugLogger, "copy_visual_to_cache target=" + visualFile.getAbsolutePath());
            copyUriToFile(resolver, visualUri, visualFile);
            log(debugLogger, "visual_cache_bytes=" + visualFile.length());

            VisualKind visualKind = detectVisualKind(resolver, visualUri, visualMimeType);
            log(debugLogger, "visual_kind=" + visualKind);
            FFmpegSession session;
            boolean videoReencoded = visualKind == VisualKind.VIDEO;

            if (visualKind == VisualKind.IMAGE) {
                session = execute("single_image", buildImageCommand(audioFile, visualFile, outputFile, exportProfile, frameRate), targetDurationMs, progressListener, debugLogger);
            } else {
                session = execute("loop_video", buildVideoCommand(audioFile, visualFile, outputFile, exportProfile, frameRate), targetDurationMs, progressListener, debugLogger);
            }

            if (!ReturnCode.isSuccess(session.getReturnCode())) {
                throw new AvtohitException("FFmpeg failed: " + session.getOutput() + "\n" + session.getFailStackTrace());
            }
            if (!outputFile.exists() || outputFile.length() <= 0L) {
                throw new IOException("Merged video file was not created.");
            }

            copyFileToUri(resolver, outputFile, destinationUri);
            log(debugLogger, "copied_output_to_destination bytes=" + outputFile.length());
            return new Result(visualKind, videoReencoded, outputFile.length(), session.getOutput());
        } finally {
            deleteIfExists(audioFile);
            deleteIfExists(visualFile);
            deleteIfExists(outputFile);
        }
    }

    public Result renderImages(
            Context context,
            Uri audioUri,
            List<Uri> imageUris,
            Uri destinationUri,
            ExportProfile exportProfile,
            int frameRate,
            long targetDurationMs,
            int slideSeconds,
            ProgressListener progressListener
    ) throws IOException, AvtohitException {
        return renderImages(context, audioUri, imageUris, destinationUri, exportProfile, frameRate, targetDurationMs, slideSeconds, progressListener, null);
    }

    public Result renderImages(
            Context context,
            Uri audioUri,
            List<Uri> imageUris,
            Uri destinationUri,
            ExportProfile exportProfile,
            int frameRate,
            long targetDurationMs,
            int slideSeconds,
            ProgressListener progressListener,
            AvtohitDebugLogger debugLogger
    ) throws IOException, AvtohitException {
        if (imageUris == null || imageUris.isEmpty()) {
            throw new AvtohitException("At least one image must be selected.");
        }
        if (slideSeconds <= 0 || imageUris.size() == 1) {
            log(debugLogger, "slideshow_delegated_to_single_image slideSeconds=" + slideSeconds + " imageCount=" + imageUris.size());
            return render(context, audioUri, imageUris.get(0), "image/*", destinationUri, exportProfile, frameRate, targetDurationMs, progressListener, debugLogger);
        }
        if (slideSeconds > 60) {
            throw new AvtohitException("Image time must be between 0 and 60 seconds.");
        }
        if (audioUri == null || destinationUri == null) {
            throw new AvtohitException("Audio and output targets must be selected.");
        }
        if (targetDurationMs <= 0L) {
            throw new AvtohitException("Selected MP3 has no readable duration.");
        }

        ContentResolver resolver = context.getContentResolver();
        File workDir = new File(context.getCacheDir(), "avtohit");
        if (!workDir.exists() && !workDir.mkdirs()) {
            throw new IOException("Could not create AVTOHIT cache directory.");
        }

        pruneStaleWorkFiles(workDir);
        long runId = System.currentTimeMillis();
        File audioFile = new File(workDir, "audio-" + runId + ".mp3");
        File outputFile = new File(workDir, "output-" + runId + ".mp4");
        File cycleFile = new File(workDir, "cycle-" + runId + ".mp4");
        ArrayList<File> imageFiles = new ArrayList<>();

        try {
            log(debugLogger, "copy_audio_to_cache target=" + audioFile.getAbsolutePath());
            copyUriToFile(resolver, audioUri, audioFile);
            log(debugLogger, "audio_cache_bytes=" + audioFile.length());
            int cycleImageCount = slideshowCycleImageCount(imageUris.size(), targetDurationMs, slideSeconds);
            int skippedImageCount = Math.max(0, imageUris.size() - cycleImageCount);
            log(debugLogger, "slideshow_cycle_selection selectedImageCount=" + imageUris.size()
                    + " usedImageCount=" + cycleImageCount
                    + " skippedImageCount=" + skippedImageCount);
            for (int i = 0; i < cycleImageCount; i++) {
                Uri imageUri = imageUris.get(i);
                File imageFile = new File(workDir, "image-" + runId + "-" + i + "." + visualExtension(resolver, imageUri, resolver.getType(imageUri)));
                log(debugLogger, "copy_image_to_cache index=" + i + " target=" + imageFile.getAbsolutePath());
                copyUriToFile(resolver, imageUri, imageFile);
                log(debugLogger, "image_cache_bytes index=" + i + " bytes=" + imageFile.length());
                imageFiles.add(imageFile);
            }

            long cycleDurationMs = (long) imageFiles.size() * slideSeconds * 1000L;
            long cycleProgressBudgetMs = Math.max(1L, targetDurationMs / 3L);
            log(debugLogger, "slideshow_cycle imageCount=" + imageFiles.size()
                    + " slideSeconds=" + slideSeconds
                    + " cycleDurationMs=" + cycleDurationMs
                    + " targetDurationMs=" + targetDurationMs
                    + " cycleProgressBudgetMs=" + cycleProgressBudgetMs);
            FFmpegSession cycleSession = execute(
                    "slideshow_cycle",
                    buildSlideshowCycleCommand(imageFiles, cycleFile, exportProfile, frameRate, slideSeconds),
                    cycleDurationMs,
                    scaledProgressListener(progressListener, 0L, cycleProgressBudgetMs, cycleDurationMs, targetDurationMs),
                    debugLogger
            );
            if (!ReturnCode.isSuccess(cycleSession.getReturnCode())) {
                throw new AvtohitException("FFmpeg failed while preparing slideshow images: " + cycleSession.getOutput() + "\n" + cycleSession.getFailStackTrace());
            }
            if (!cycleFile.exists() || cycleFile.length() <= 0L) {
                throw new IOException("Prepared slideshow cycle was not created.");
            }
            log(debugLogger, "slideshow_cycle_output_bytes=" + cycleFile.length());

            FFmpegSession session = execute(
                    "loop_slideshow_to_audio",
                    buildLoopedSlideshowCommand(audioFile, cycleFile, outputFile, targetDurationMs),
                    targetDurationMs,
                    scaledProgressListener(progressListener, cycleProgressBudgetMs, targetDurationMs - cycleProgressBudgetMs, targetDurationMs, targetDurationMs),
                    debugLogger
            );

            if (!ReturnCode.isSuccess(session.getReturnCode())) {
                throw new AvtohitException("FFmpeg failed: " + session.getOutput() + "\n" + session.getFailStackTrace());
            }
            if (!outputFile.exists() || outputFile.length() <= 0L) {
                throw new IOException("Merged video file was not created.");
            }

            copyFileToUri(resolver, outputFile, destinationUri);
            log(debugLogger, "copied_output_to_destination bytes=" + outputFile.length());
            return new Result(VisualKind.IMAGE, false, outputFile.length(), cycleSession.getOutput() + "\n" + session.getOutput());
        } finally {
            deleteIfExists(audioFile);
            deleteIfExists(outputFile);
            deleteIfExists(cycleFile);
            for (File imageFile : imageFiles) {
                deleteIfExists(imageFile);
            }
        }
    }

    public Result renderVideos(
            Context context,
            List<Uri> videoUris,
            Uri destinationUri,
            ExportProfile exportProfile,
            int frameRate,
            long targetDurationMs,
            ProgressListener progressListener,
            AvtohitDebugLogger debugLogger
    ) throws IOException, AvtohitException {
        if (videoUris == null || videoUris.size() < 2) {
            throw new AvtohitException("Select at least two videos to merge.");
        }
        if (destinationUri == null) {
            throw new AvtohitException("Output target must be selected.");
        }

        ContentResolver resolver = context.getContentResolver();
        File workDir = new File(context.getCacheDir(), "avtohit");
        if (!workDir.exists() && !workDir.mkdirs()) {
            throw new IOException("Could not create AVTOHIT cache directory.");
        }

        pruneStaleWorkFiles(workDir);
        long runId = System.currentTimeMillis();
        File outputFile = new File(workDir, "output-video-sequence-" + runId + ".mp4");
        ArrayList<File> videoFiles = new ArrayList<>();

        try {
            log(debugLogger, "video_sequence_input_count=" + videoUris.size()
                    + " targetDurationMs=" + targetDurationMs
                    + " frameRate=" + frameRate
                    + " export=" + exportProfile.label);
            for (int i = 0; i < videoUris.size(); i++) {
                Uri videoUri = videoUris.get(i);
                File videoFile = new File(workDir, "video-" + runId + "-" + i + "." + visualExtension(resolver, videoUri, resolver.getType(videoUri)));
                log(debugLogger, "copy_video_to_cache index=" + i + " target=" + videoFile.getAbsolutePath());
                copyUriToFile(resolver, videoUri, videoFile);
                log(debugLogger, "video_cache_bytes index=" + i + " bytes=" + videoFile.length());
                videoFiles.add(videoFile);
            }

            FFmpegSession session = execute(
                    "concat_videos",
                    buildVideoSequenceCommand(videoFiles, outputFile, exportProfile, frameRate),
                    targetDurationMs,
                    progressListener,
                    debugLogger
            );
            if (!ReturnCode.isSuccess(session.getReturnCode())) {
                throw new AvtohitException("FFmpeg failed while merging videos: " + session.getOutput() + "\n" + session.getFailStackTrace());
            }
            if (!outputFile.exists() || outputFile.length() <= 0L) {
                throw new IOException("Merged video file was not created.");
            }

            copyFileToUri(resolver, outputFile, destinationUri);
            log(debugLogger, "copied_output_to_destination bytes=" + outputFile.length());
            return new Result(VisualKind.VIDEO_SEQUENCE, true, false, outputFile.length(), session.getOutput());
        } finally {
            deleteIfExists(outputFile);
            for (File videoFile : videoFiles) {
                deleteIfExists(videoFile);
            }
        }
    }

    public static String displayName(Context context, Uri uri) {
        Cursor cursor;
        try {
            cursor = context.getContentResolver().query(uri, null, null, null, null);
        } catch (RuntimeException ignored) {
            cursor = null;
        }
        if (cursor == null) {
            return fallbackName(uri);
        }
        try {
            int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
            if (nameIndex >= 0 && cursor.moveToFirst()) {
                String value = cursor.getString(nameIndex);
                if (value != null && !value.trim().isEmpty()) {
                    return value;
                }
            }
            return fallbackName(uri);
        } finally {
            cursor.close();
        }
    }

    private static FFmpegSession execute(
            String phaseName,
            List<String> arguments,
            long targetDurationMs,
            ProgressListener progressListener,
            AvtohitDebugLogger debugLogger
    ) throws IOException {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<FFmpegSession> sessionRef = new AtomicReference<>();
        log(debugLogger, "ffmpeg_phase_start=" + phaseName + " targetDurationMs=" + targetDurationMs);
        log(debugLogger, "ffmpeg_command=" + joinArguments(arguments));
        long startedAt = System.currentTimeMillis();
        long[] lastLoggedProgressMs = new long[]{-1L};
        int[] lastLoggedPercent = new int[]{-1};

        FFmpegKit.executeWithArgumentsAsync(
                arguments.toArray(new String[0]),
                session -> {
                    sessionRef.set(session);
                    log(debugLogger, "ffmpeg_phase_end=" + phaseName
                            + " returnCode=" + session.getReturnCode()
                            + " elapsedMs=" + (System.currentTimeMillis() - startedAt));
                    latch.countDown();
                },
                ffmpegLog -> log(debugLogger, "ffmpeg[" + phaseName + "] " + ffmpegLog.getMessage()),
                statistics -> {
                    publishProgress(statistics, targetDurationMs, progressListener);
                    publishLoggedProgress(phaseName, statistics, targetDurationMs, debugLogger, lastLoggedProgressMs, lastLoggedPercent);
                }
        );

        try {
            latch.await();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("Render interrupted.", error);
        }

        FFmpegSession session = sessionRef.get();
        if (session == null) {
            throw new IOException("FFmpeg session finished without a result.");
        }
        return session;
    }

    private static void publishLoggedProgress(
            String phaseName,
            Statistics statistics,
            long targetDurationMs,
            AvtohitDebugLogger debugLogger,
            long[] lastLoggedProgressMs,
            int[] lastLoggedPercent
    ) {
        if (debugLogger == null || statistics == null || targetDurationMs <= 0L) {
            return;
        }
        long statisticTimeMs = Math.round(statistics.getTime());
        long currentMs = Math.min(Math.max(0L, statisticTimeMs), targetDurationMs);
        int percent = (int) Math.min(100L, Math.max(0L, Math.round((currentMs * 100.0) / targetDurationMs)));
        if (lastLoggedProgressMs[0] < 0L
                || currentMs - lastLoggedProgressMs[0] >= 5000L
                || percent - lastLoggedPercent[0] >= 5) {
            lastLoggedProgressMs[0] = currentMs;
            lastLoggedPercent[0] = percent;
            log(debugLogger, "progress[" + phaseName + "] "
                    + percent + "% currentMs=" + currentMs
                    + " totalMs=" + targetDurationMs
                    + " speed=" + statistics.getSpeed()
                    + " fps=" + statistics.getVideoFps()
                    + " sizeBytes=" + statistics.getSize());
        }
    }

    private static void publishProgress(Statistics statistics, long targetDurationMs, ProgressListener progressListener) {
        if (progressListener == null || targetDurationMs <= 0L || statistics == null) {
            return;
        }
        long statisticTimeMs = Math.round(statistics.getTime());
        long currentMs = Math.min(Math.max(0L, statisticTimeMs), targetDurationMs);
        progressListener.onProgress(currentMs, targetDurationMs);
    }

    private static ProgressListener scaledProgressListener(
            ProgressListener delegate,
            long offsetMs,
            long phaseSpanMs,
            long phaseTotalMs,
            long reportedTotalMs
    ) {
        if (delegate == null) {
            return null;
        }
        return (currentMs, ignoredTotalMs) -> {
            long safePhaseTotal = Math.max(1L, phaseTotalMs);
            long clampedCurrent = Math.min(Math.max(0L, currentMs), safePhaseTotal);
            long scaledCurrent = offsetMs + Math.round((clampedCurrent / (double) safePhaseTotal) * Math.max(1L, phaseSpanMs));
            delegate.onProgress(Math.min(Math.max(0L, scaledCurrent), reportedTotalMs), reportedTotalMs);
        };
    }

    private static int slideshowCycleImageCount(int selectedImageCount, long targetDurationMs, int slideSeconds) {
        if (selectedImageCount <= 0) {
            return 0;
        }
        long slideDurationMs = Math.max(1L, (long) slideSeconds * 1000L);
        long imagesThatCanAppear = (targetDurationMs + slideDurationMs - 1L) / slideDurationMs;
        if (imagesThatCanAppear <= 0L) {
            return 1;
        }
        return (int) Math.min(selectedImageCount, Math.min((long) Integer.MAX_VALUE, imagesThatCanAppear));
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
        // Loop the visual input forever and let -shortest trim the output exactly to the MP3 length.
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

    private static List<String> buildVideoSequenceCommand(
            List<File> videoFiles,
            File outputFile,
            ExportProfile exportProfile,
            int frameRate
    ) {
        List<String> args = baseArgs();
        for (File videoFile : videoFiles) {
            args.add("-i");
            args.add(videoFile.getAbsolutePath());
        }

        StringBuilder filter = new StringBuilder();
        for (int i = 0; i < videoFiles.size(); i++) {
            filter.append('[')
                    .append(i)
                    .append(":v:0]")
                    .append(buildScalePadFilter(exportProfile, frameRate))
                    .append("[v")
                    .append(i)
                    .append("];");
            filter.append('[')
                    .append(i)
                    .append(":a:0]")
                    .append("aresample=44100,aformat=sample_fmts=fltp:channel_layouts=stereo")
                    .append("[a")
                    .append(i)
                    .append("];");
        }
        for (int i = 0; i < videoFiles.size(); i++) {
            filter.append("[v").append(i).append("][a").append(i).append(']');
        }
        filter.append("concat=n=")
                .append(videoFiles.size())
                .append(":v=1:a=1[v][a]");

        args.add("-filter_complex");
        args.add(filter.toString());
        args.add("-map");
        args.add("[v]");
        args.add("-map");
        args.add("[a]");
        args.add("-c:v");
        args.add("mpeg4");
        args.add("-q:v");
        args.add("3");
        args.add("-c:a");
        args.add("aac");
        args.add("-b:a");
        args.add("192k");
        args.add("-movflags");
        args.add("+faststart");
        args.add(outputFile.getAbsolutePath());
        return args;
    }

    private static List<String> buildSlideshowCycleCommand(
            List<File> imageFiles,
            File outputFile,
            ExportProfile exportProfile,
            int frameRate,
            int slideSeconds
    ) {
        List<String> args = baseArgs();
        String slideDuration = formatSeconds(slideSeconds * 1000L);
        for (File imageFile : imageFiles) {
            args.add("-loop");
            args.add("1");
            args.add("-framerate");
            args.add(String.valueOf(frameRate));
            args.add("-t");
            args.add(slideDuration);
            args.add("-i");
            args.add(imageFile.getAbsolutePath());
        }

        StringBuilder filter = new StringBuilder();
        for (int i = 0; i < imageFiles.size(); i++) {
            filter.append('[')
                    .append(i)
                    .append(":v]")
                    .append(buildScalePadFilter(exportProfile, frameRate))
                    .append("[v")
                    .append(i)
                    .append("];");
        }
        for (int i = 0; i < imageFiles.size(); i++) {
            filter.append("[v").append(i).append(']');
        }
        filter.append("concat=n=")
                .append(imageFiles.size())
                .append(":v=1:a=0[v]");
        args.add("-filter_complex");
        args.add(filter.toString());
        args.add("-map");
        args.add("[v]");
        args.add("-c:v");
        args.add("mpeg4");
        args.add("-q:v");
        args.add("3");
        args.add("-an");
        args.add("-movflags");
        args.add("+faststart");
        args.add(outputFile.getAbsolutePath());
        return args;
    }

    private static List<String> buildLoopedSlideshowCommand(
            File audioFile,
            File cycleFile,
            File outputFile,
            long targetDurationMs
    ) {
        List<String> args = baseArgs();
        args.add("-fflags");
        args.add("+genpts");
        args.add("-stream_loop");
        args.add("-1");
        args.add("-i");
        args.add(cycleFile.getAbsolutePath());
        args.add("-i");
        args.add(audioFile.getAbsolutePath());
        args.add("-map");
        args.add("0:v:0");
        args.add("-map");
        args.add("1:a:0");
        args.add("-t");
        args.add(formatSeconds(targetDurationMs));
        args.add("-c:v");
        args.add("copy");
        args.add("-c:a");
        args.add("copy");
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

    private static String formatSeconds(long millis) {
        return String.format(Locale.US, "%.3f", Math.max(0L, millis) / 1000.0);
    }

    private static String joinArguments(List<String> arguments) {
        StringBuilder builder = new StringBuilder();
        for (String argument : arguments) {
            if (builder.length() > 0) {
                builder.append(' ');
            }
            builder.append('"').append(argument.replace("\"", "\\\"")).append('"');
        }
        return builder.toString();
    }

    private static void log(AvtohitDebugLogger debugLogger, String message) {
        if (debugLogger != null) {
            debugLogger.append(message);
        }
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
        if (path.endsWith(".heic") || path.endsWith(".heif")) {
            return VisualKind.IMAGE;
        }
        if (path.endsWith(".mp4") || path.endsWith(".mov") || path.endsWith(".m4v") || path.endsWith(".webm") || path.endsWith(".3gp")) {
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
            if (Thread.currentThread().isInterrupted()) {
                throw new IOException("Render interrupted.");
            }
            output.write(buffer, 0, read);
        }
        output.flush();
    }

    private static void pruneStaleWorkFiles(File workDir) {
        File[] files = workDir.listFiles();
        if (files == null) {
            return;
        }
        long cutoff = System.currentTimeMillis() - STALE_WORK_FILE_AGE_MS;
        for (File file : files) {
            if (file != null && file.isFile() && file.lastModified() < cutoff) {
                deleteIfExists(file);
            }
        }
    }

    private static String fallbackName(Uri uri) {
        String path = uri != null ? uri.getLastPathSegment() : null;
        return (path == null || path.trim().isEmpty()) ? "selected-file" : path;
    }

    private static void deleteIfExists(File file) {
        if (file != null && file.exists() && !file.delete()) {
            file.deleteOnExit();
        }
    }
}

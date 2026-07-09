package com.avtohit.app.media;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
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
import java.util.Collections;
import java.util.Comparator;
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

    private interface PairwiseCommandBuilder {
        List<String> build(File leftFile, File rightFile, File outputFile);
    }

    private static final class TimedClip {
        final File file;
        final long durationMs;
        final long sortKey;

        TimedClip(File file, long durationMs, long sortKey) {
            this.file = file;
            this.durationMs = Math.max(1L, durationMs);
            this.sortKey = sortKey;
        }
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
        File outputFile = new File(workDir, "output-" + runId + ".mp4");
        VisualKind visualKind = detectVisualKind(resolver, visualUri, visualMimeType);
        File visualFile = new File(workDir, "visual-" + runId + "." + (visualKind == VisualKind.IMAGE
                ? "jpg"
                : visualExtension(resolver, visualUri, visualMimeType)));

        try {
            // Work from cache copies so SAF streams stay stable for FFmpeg and never leak raw provider paths.
            log(debugLogger, "copy_audio_to_cache target=" + audioFile.getAbsolutePath());
            copyUriToFile(resolver, audioUri, audioFile);
            log(debugLogger, "audio_cache_bytes=" + audioFile.length());
            log(debugLogger, "visual_kind=" + visualKind);
            if (visualKind == VisualKind.IMAGE) {
                log(debugLogger, "normalize_visual_image_to_cache target=" + visualFile.getAbsolutePath());
                prepareImageForFfmpeg(resolver, visualUri, visualFile, exportProfile, debugLogger, "visual_image", 0);
            } else {
                log(debugLogger, "copy_visual_to_cache target=" + visualFile.getAbsolutePath());
                copyUriToFile(resolver, visualUri, visualFile);
            }
            log(debugLogger, "visual_cache_bytes=" + visualFile.length());

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
        return renderImages(context, audioUri, imageUris, destinationUri, exportProfile, frameRate, targetDurationMs, slideSeconds, false, progressListener, null);
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
        return renderImages(context, audioUri, imageUris, destinationUri, exportProfile, frameRate, targetDurationMs, slideSeconds, false, progressListener, debugLogger);
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
            boolean autoSplitTime,
            ProgressListener progressListener,
            AvtohitDebugLogger debugLogger
    ) throws IOException, AvtohitException {
        if (imageUris == null || imageUris.isEmpty()) {
            throw new AvtohitException("At least one image must be selected.");
        }
        boolean useAutoSplit = autoSplitTime && slideSeconds <= 0 && imageUris.size() > 1;
        if (!useAutoSplit && (slideSeconds <= 0 || imageUris.size() == 1)) {
            log(debugLogger, "slideshow_delegated_to_single_image slideSeconds=" + slideSeconds
                    + " imageCount=" + imageUris.size()
                    + " autoSplitTime=" + autoSplitTime);
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
            int cycleImageCount = useAutoSplit
                    ? imageUris.size()
                    : slideshowCycleImageCount(imageUris.size(), targetDurationMs, slideSeconds);
            int skippedImageCount = Math.max(0, imageUris.size() - cycleImageCount);
            log(debugLogger, "slideshow_cycle_selection selectedImageCount=" + imageUris.size()
                    + " usedImageCount=" + cycleImageCount
                    + " skippedImageCount=" + skippedImageCount
                    + " autoSplitTime=" + useAutoSplit);
            for (int i = 0; i < cycleImageCount; i++) {
                Uri imageUri = imageUris.get(i);
                File imageFile = new File(workDir, "image-" + runId + "-" + i + ".jpg");
                log(debugLogger, "normalize_image_to_cache index=" + i + " target=" + imageFile.getAbsolutePath());
                prepareImageForFfmpeg(resolver, imageUri, imageFile, exportProfile, debugLogger, "image", i);
                log(debugLogger, "image_cache_bytes index=" + i + " bytes=" + imageFile.length());
                imageFiles.add(imageFile);
            }

            long cycleDurationMs = useAutoSplit
                    ? targetDurationMs
                    : (long) imageFiles.size() * slideSeconds * 1000L;
            long cycleProgressBudgetMs = Math.max(1L, targetDurationMs / 3L);
            log(debugLogger, "slideshow_cycle imageCount=" + imageFiles.size()
                    + " slideSeconds=" + slideSeconds
                    + " autoSplitTime=" + useAutoSplit
                    + " cycleDurationMs=" + cycleDurationMs
                    + " targetDurationMs=" + targetDurationMs
                    + " cycleProgressBudgetMs=" + cycleProgressBudgetMs);
            String cycleOutput = buildSlideshowCyclePairwise(
                    workDir,
                    runId,
                    imageFiles,
                    cycleFile,
                    exportProfile,
                    frameRate,
                    slideshowDurationsMs(imageFiles.size(), slideSeconds, useAutoSplit ? targetDurationMs : 0L, debugLogger),
                    progressListener,
                    0L,
                    cycleProgressBudgetMs,
                    targetDurationMs,
                    debugLogger
            );
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
            return new Result(VisualKind.IMAGE, false, outputFile.length(), cycleOutput + "\n" + session.getOutput());
        } finally {
            deleteIfExists(audioFile);
            deleteIfExists(outputFile);
            deleteIfExists(cycleFile);
            for (File imageFile : imageFiles) {
                deleteIfExists(imageFile);
            }
        }
    }

    public Result renderImagesSilent(
            Context context,
            List<Uri> imageUris,
            Uri destinationUri,
            ExportProfile exportProfile,
            int frameRate,
            int slideSeconds,
            ProgressListener progressListener,
            AvtohitDebugLogger debugLogger
    ) throws IOException, AvtohitException {
        if (imageUris == null || imageUris.isEmpty()) {
            throw new AvtohitException("At least one image must be selected.");
        }
        if (slideSeconds <= 0) {
            throw new AvtohitException("Image time must be above 0 seconds when no MP3 is selected.");
        }
        if (slideSeconds > 60) {
            throw new AvtohitException("Image time must be between 0 and 60 seconds.");
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
        File outputFile = new File(workDir, "silent-images-" + runId + ".mp4");
        ArrayList<File> imageFiles = new ArrayList<>();
        long targetDurationMs = (long) imageUris.size() * slideSeconds * 1000L;

        try {
            log(debugLogger, "silent_slideshow imageCount=" + imageUris.size()
                    + " slideSeconds=" + slideSeconds
                    + " targetDurationMs=" + targetDurationMs
                    + " frameRate=" + frameRate
                    + " export=" + exportProfile.label);
            for (int i = 0; i < imageUris.size(); i++) {
                Uri imageUri = imageUris.get(i);
                File imageFile = new File(workDir, "silent-image-" + runId + "-" + i + ".jpg");
                log(debugLogger, "normalize_silent_image_to_cache index=" + i + " target=" + imageFile.getAbsolutePath());
                prepareImageForFfmpeg(resolver, imageUri, imageFile, exportProfile, debugLogger, "silent_image", i);
                log(debugLogger, "silent_image_cache_bytes index=" + i + " bytes=" + imageFile.length());
                imageFiles.add(imageFile);
            }

            String output = buildSlideshowCyclePairwise(
                    workDir,
                    runId,
                    imageFiles,
                    outputFile,
                    exportProfile,
                    frameRate,
                    slideshowDurationsMs(imageFiles.size(), slideSeconds, 0L, debugLogger),
                    progressListener,
                    0L,
                    targetDurationMs,
                    targetDurationMs,
                    debugLogger
            );
            if (!outputFile.exists() || outputFile.length() <= 0L) {
                throw new IOException("Silent video file was not created.");
            }

            copyFileToUri(resolver, outputFile, destinationUri);
            log(debugLogger, "copied_output_to_destination bytes=" + outputFile.length());
            return new Result(VisualKind.IMAGE, false, false, outputFile.length(), output);
        } finally {
            deleteIfExists(outputFile);
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
            List<Long> videoDurationsMs,
            List<? extends List<VideoSoundEffect>> videoSoundEffects,
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
        ArrayList<TimedClip> videoClips = new ArrayList<>();
        ArrayList<File> pairwiseTempFiles = new ArrayList<>();
        long reportedVideoDurationMs = Math.max(1L, targetDurationMs);

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
                long clipDurationMs = videoDurationAt(videoDurationsMs, i, targetDurationMs, videoUris.size());
                File editedVideoFile = new File(workDir, "video-sound-edited-" + runId + "-" + i + ".mp4");
                File clipFile = applySoundEffectsToVideoIfNeeded(
                        videoFile,
                        editedVideoFile,
                        clipDurationMs,
                        soundEffectsAt(videoSoundEffects, i),
                        debugLogger
                );
                if (!sameFile(videoFile, clipFile)) {
                    videoFiles.add(clipFile);
                }
                videoClips.add(new TimedClip(clipFile, clipDurationMs, i));
            }

            String output = reduceTimedClipsPairwise(
                    "concat_video_pair",
                    videoClips,
                    outputFile,
                    workDir,
                    runId,
                    progressListener,
                    0L,
                    reportedVideoDurationMs,
                    reportedVideoDurationMs,
                    pairwiseWorkMs(videoClips),
                    new long[]{0L},
                    pairwiseTempFiles,
                    (leftFile, rightFile, pairOutputFile) -> buildVideoPairCommand(leftFile, rightFile, pairOutputFile, exportProfile, frameRate),
                    debugLogger
            );
            if (!outputFile.exists() || outputFile.length() <= 0L) {
                throw new IOException("Merged video file was not created.");
            }

            copyFileToUri(resolver, outputFile, destinationUri);
            log(debugLogger, "copied_output_to_destination bytes=" + outputFile.length());
            return new Result(VisualKind.VIDEO_SEQUENCE, true, false, outputFile.length(), output);
        } finally {
            deleteIfExists(outputFile);
            for (File videoFile : videoFiles) {
                deleteIfExists(videoFile);
            }
            for (File tempFile : pairwiseTempFiles) {
                deleteIfExists(tempFile);
            }
        }
    }

    public Result renderVideoRepeated(
            Context context,
            Uri videoUri,
            Uri destinationUri,
            ExportProfile exportProfile,
            int frameRate,
            long videoDurationMs,
            int repeatCount,
            List<VideoSoundEffect> soundEffects,
            ProgressListener progressListener,
            AvtohitDebugLogger debugLogger
    ) throws IOException, AvtohitException {
        if (videoUri == null) {
            throw new AvtohitException("Select a video to repeat.");
        }
        if (destinationUri == null) {
            throw new AvtohitException("Output target must be selected.");
        }
        if (repeatCount < 1) {
            throw new AvtohitException("Video repeat count must be at least 1.");
        }

        ContentResolver resolver = context.getContentResolver();
        File workDir = new File(context.getCacheDir(), "avtohit");
        if (!workDir.exists() && !workDir.mkdirs()) {
            throw new IOException("Could not create AVTOHIT cache directory.");
        }

        pruneStaleWorkFiles(workDir);
        long runId = System.currentTimeMillis();
        File videoFile = new File(workDir, "single-video-repeat-" + runId + "." + visualExtension(resolver, videoUri, resolver.getType(videoUri)));
        File outputFile = new File(workDir, "output-video-repeat-" + runId + ".mp4");
        long targetDurationMs = Math.max(1L, videoDurationMs > 0L ? videoDurationMs * (long) repeatCount : 1L);

        try {
            log(debugLogger, "video_repeat_input repeatCount=" + repeatCount
                    + " sourceDurationMs=" + videoDurationMs
                    + " targetDurationMs=" + targetDurationMs
                    + " frameRate=" + frameRate
                    + " export=" + exportProfile.label);
            copyUriToFile(resolver, videoUri, videoFile);
            log(debugLogger, "video_repeat_cache_bytes bytes=" + videoFile.length());
            File repeatSourceFile = applySoundEffectsToVideoIfNeeded(
                    videoFile,
                    new File(workDir, "single-video-repeat-sound-edited-" + runId + ".mp4"),
                    videoDurationMs,
                    soundEffects,
                    debugLogger
            );

            FFmpegSession session = execute(
                    "repeat_video",
                    buildRepeatedVideoCommand(repeatSourceFile, outputFile, exportProfile, frameRate, repeatCount),
                    targetDurationMs,
                    progressListener,
                    debugLogger
            );
            ensureSuccessfulSession(session, "FFmpeg failed while repeating the selected video.");
            ensureOutputFile(outputFile, "Repeated video file was not created.");
            StringBuilder output = new StringBuilder();
            appendSessionOutput(output, session);

            copyFileToUri(resolver, outputFile, destinationUri);
            log(debugLogger, "copied_output_to_destination bytes=" + outputFile.length());
            return new Result(VisualKind.VIDEO, true, false, outputFile.length(), output.toString());
        } finally {
            deleteIfExists(videoFile);
            deleteIfExists(new File(workDir, "single-video-repeat-sound-edited-" + runId + ".mp4"));
            deleteIfExists(outputFile);
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

    private static File applySoundEffectsToVideoIfNeeded(
            File videoFile,
            File outputFile,
            long durationMs,
            List<VideoSoundEffect> soundEffects,
            AvtohitDebugLogger debugLogger
    ) throws IOException, AvtohitException {
        List<VideoSoundEffect> safeEffects = safeSoundEffects(soundEffects, durationMs);
        if (safeEffects.isEmpty()) {
            return videoFile;
        }

        long safeDurationMs = Math.max(1L, durationMs);
        log(debugLogger, "video_sound_effects_prepare source=" + videoFile.getName()
                + " effectCount=" + safeEffects.size()
                + " durationMs=" + safeDurationMs);

        FFmpegSession session = execute(
                "video_sound_effects",
                buildVideoSoundEffectsCommand(videoFile, outputFile, safeDurationMs, safeEffects, true),
                safeDurationMs,
                null,
                debugLogger
        );
        if (!ReturnCode.isSuccess(session.getReturnCode())) {
            // Some imported clips have no audio stream. Retry against generated silence so the effect still renders.
            log(debugLogger, "video_sound_effects_retry_silent_base source=" + videoFile.getName());
            deleteIfExists(outputFile);
            session = execute(
                    "video_sound_effects_silent_base",
                    buildVideoSoundEffectsCommand(videoFile, outputFile, safeDurationMs, safeEffects, false),
                    safeDurationMs,
                    null,
                    debugLogger
            );
        }

        ensureSuccessfulSession(session, "FFmpeg failed while applying video sound effects.");
        ensureOutputFile(outputFile, "Edited video sound file was not created.");
        log(debugLogger, "video_sound_effects_output bytes=" + outputFile.length());
        return outputFile;
    }

    private static List<VideoSoundEffect> safeSoundEffects(List<VideoSoundEffect> soundEffects, long durationMs) {
        ArrayList<VideoSoundEffect> safeEffects = new ArrayList<>();
        if (soundEffects == null || soundEffects.isEmpty()) {
            return safeEffects;
        }

        long safeDurationMs = Math.max(1L, durationMs);
        for (VideoSoundEffect effect : soundEffects) {
            if (effect == null) {
                continue;
            }
            long startMs = Math.max(0L, effect.startMs);
            if (startMs >= safeDurationMs) {
                continue;
            }
            long maxDurationMs = Math.max(1L, safeDurationMs - startMs);
            long effectDurationMs = Math.min(Math.max(1L, effect.durationMs), maxDurationMs);
            safeEffects.add(new VideoSoundEffect(effect.type, startMs, effectDurationMs));
        }
        return safeEffects;
    }

    private static List<VideoSoundEffect> soundEffectsAt(List<? extends List<VideoSoundEffect>> values, int index) {
        if (values == null || index < 0 || index >= values.size()) {
            return Collections.emptyList();
        }
        List<VideoSoundEffect> effects = values.get(index);
        return effects != null ? effects : Collections.emptyList();
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

    private static List<Long> slideshowDurationsMs(
            int imageCount,
            int slideSeconds,
            long autoSplitTargetDurationMs,
            AvtohitDebugLogger debugLogger
    ) {
        if (autoSplitTargetDurationMs > 0L) {
            List<Long> durations = splitDurationAcrossImages(autoSplitTargetDurationMs, imageCount);
            log(debugLogger, "auto_split_plan imageCount=" + imageCount
                    + " targetDurationMs=" + autoSplitTargetDurationMs
                    + " firstImageDurationMs=" + (durations.isEmpty() ? 0L : durations.get(0)));
            return durations;
        }

        ArrayList<Long> durations = new ArrayList<>();
        long durationMs = Math.max(1L, (long) slideSeconds * 1000L);
        for (int i = 0; i < imageCount; i++) {
            durations.add(durationMs);
        }
        return durations;
    }

    private static String buildSlideshowCyclePairwise(
            File workDir,
            long runId,
            List<File> imageFiles,
            File outputFile,
            ExportProfile exportProfile,
            int frameRate,
            List<Long> durationsMs,
            ProgressListener progressListener,
            long progressOffsetMs,
            long progressSpanMs,
            long reportedTotalMs,
            AvtohitDebugLogger debugLogger
    ) throws IOException, AvtohitException {
        if (imageFiles == null || imageFiles.isEmpty()) {
            throw new AvtohitException("At least one image must be selected.");
        }
        ArrayList<TimedClip> clips = new ArrayList<>();
        ArrayList<Long> safeDurationsMs = new ArrayList<>();
        for (int i = 0; i < imageFiles.size(); i++) {
            safeDurationsMs.add(durationAt(durationsMs, i, 1000L));
        }

        long totalWorkMs = pairwiseWorkMsFromDurations(safeDurationsMs);
        long[] completedWorkMs = new long[]{0L};
        ArrayList<File> temporaryFiles = new ArrayList<>();
        StringBuilder output = new StringBuilder();
        log(debugLogger, "pairwise_slideshow_start imageCount=" + imageFiles.size()
                + " totalWorkMs=" + totalWorkMs
                + " progressSpanMs=" + progressSpanMs);

        try {
            for (int i = 0; i < imageFiles.size(); i++) {
                long durationMs = safeDurationsMs.get(i);
                boolean singleImageOutput = imageFiles.size() == 1;
                File segmentFile = singleImageOutput
                        ? outputFile
                        : new File(workDir, "slide-segment-" + runId + "-" + i + ".mp4");
                FFmpegSession segmentSession = execute(
                        "slideshow_segment_" + i,
                        buildImageSegmentCommand(imageFiles.get(i), segmentFile, exportProfile, frameRate, durationMs),
                        durationMs,
                        pairwiseStepProgress(progressListener, progressOffsetMs, progressSpanMs, reportedTotalMs, totalWorkMs, completedWorkMs[0], durationMs, durationMs),
                        debugLogger
                );
                ensureSuccessfulSession(segmentSession, "FFmpeg failed while preparing slideshow image segment.");
                ensureOutputFile(segmentFile, "Prepared slideshow image segment was not created.");
                appendSessionOutput(output, segmentSession);
                completedWorkMs[0] += durationMs;
                publishPairwiseProgress(progressListener, progressOffsetMs, progressSpanMs, reportedTotalMs, totalWorkMs, completedWorkMs[0]);

                if (!singleImageOutput) {
                    temporaryFiles.add(segmentFile);
                    clips.add(new TimedClip(segmentFile, durationMs, i));
                }
            }

            if (imageFiles.size() == 1) {
                publishPairwiseProgress(progressListener, progressOffsetMs, progressSpanMs, reportedTotalMs, totalWorkMs, totalWorkMs);
                return output.toString();
            }

            output.append(reduceTimedClipsPairwise(
                    "slideshow_pair",
                    clips,
                    outputFile,
                    workDir,
                    runId,
                    progressListener,
                    progressOffsetMs,
                    progressSpanMs,
                    reportedTotalMs,
                    totalWorkMs,
                    completedWorkMs,
                    temporaryFiles,
                    AvtohitProcessor::buildSilentVideoPairCommand,
                    debugLogger
            ));
            return output.toString();
        } finally {
            for (File temporaryFile : temporaryFiles) {
                if (!sameFile(temporaryFile, outputFile)) {
                    deleteIfExists(temporaryFile);
                }
            }
        }
    }

    private static String reduceTimedClipsPairwise(
            String phasePrefix,
            ArrayList<TimedClip> clips,
            File outputFile,
            File workDir,
            long runId,
            ProgressListener progressListener,
            long progressOffsetMs,
            long progressSpanMs,
            long reportedTotalMs,
            long totalWorkMs,
            long[] completedWorkMs,
            ArrayList<File> temporaryFiles,
            PairwiseCommandBuilder commandBuilder,
            AvtohitDebugLogger debugLogger
    ) throws IOException, AvtohitException {
        StringBuilder output = new StringBuilder();
        int step = 0;
        sortTimedClips(clips);
        log(debugLogger, phasePrefix + "_start clipCount=" + clips.size());
        while (clips.size() > 1) {
            sortTimedClips(clips);
            TimedClip left = clips.remove(0);
            TimedClip right = clips.remove(0);
            long mergedDurationMs = Math.max(1L, left.durationMs + right.durationMs);
            boolean finalStep = clips.isEmpty();
            File pairOutputFile = finalStep
                    ? outputFile
                    : new File(workDir, phasePrefix + "-" + runId + "-" + right.sortKey + "-" + step + ".mp4");
            if (!finalStep && temporaryFiles != null) {
                temporaryFiles.add(pairOutputFile);
            }
            log(debugLogger, phasePrefix + "_step=" + step
                    + " left=" + left.file.getName()
                    + " right=" + right.file.getName()
                    + " output=" + pairOutputFile.getName()
                    + " outputSortKey=" + right.sortKey
                    + " mergedDurationMs=" + mergedDurationMs
                    + " remainingAfterPair=" + clips.size());
            FFmpegSession session = execute(
                    phasePrefix + "_" + step,
                    commandBuilder.build(left.file, right.file, pairOutputFile),
                    mergedDurationMs,
                    pairwiseStepProgress(progressListener, progressOffsetMs, progressSpanMs, reportedTotalMs, totalWorkMs, completedWorkMs[0], mergedDurationMs, mergedDurationMs),
                    debugLogger
            );
            ensureSuccessfulSession(session, "FFmpeg failed while merging pairwise clips.");
            ensureOutputFile(pairOutputFile, "Pairwise merged video file was not created.");
            appendSessionOutput(output, session);
            deleteIfExists(left.file);
            deleteIfExists(right.file);
            clips.add(new TimedClip(pairOutputFile, mergedDurationMs, right.sortKey));
            completedWorkMs[0] += mergedDurationMs;
            publishPairwiseProgress(progressListener, progressOffsetMs, progressSpanMs, reportedTotalMs, totalWorkMs, completedWorkMs[0]);
            step++;
        }
        publishPairwiseProgress(progressListener, progressOffsetMs, progressSpanMs, reportedTotalMs, totalWorkMs, totalWorkMs);
        return output.toString();
    }

    private static void sortTimedClips(ArrayList<TimedClip> clips) {
        Collections.sort(clips, Comparator.comparingLong(clip -> clip.sortKey));
    }

    private static long pairwiseWorkMsFromDurations(List<Long> durationsMs) {
        ArrayList<Long> queue = new ArrayList<>();
        long total = 0L;
        if (durationsMs != null) {
            for (Long durationMs : durationsMs) {
                long safeDurationMs = Math.max(1L, durationMs != null ? durationMs : 1L);
                queue.add(safeDurationMs);
                total += safeDurationMs;
            }
        }
        while (queue.size() > 1) {
            long mergedDurationMs = Math.max(1L, queue.remove(0) + queue.remove(0));
            total += mergedDurationMs;
            queue.add(0, mergedDurationMs);
        }
        return Math.max(1L, total);
    }

    private static long pairwiseWorkMs(List<TimedClip> clips) {
        ArrayList<Long> durationsMs = new ArrayList<>();
        if (clips != null) {
            for (TimedClip clip : clips) {
                durationsMs.add(clip.durationMs);
            }
        }
        return pairwiseWorkMsFromDurations(durationsMs);
    }

    private static ProgressListener pairwiseStepProgress(
            ProgressListener delegate,
            long progressOffsetMs,
            long progressSpanMs,
            long reportedTotalMs,
            long totalWorkMs,
            long completedWorkMs,
            long stepWorkMs,
            long stepDurationMs
    ) {
        long stepOffsetMs = progressOffsetMs + scalePairwiseProgress(completedWorkMs, totalWorkMs, progressSpanMs);
        long stepSpanMs = Math.max(1L, scalePairwiseProgress(stepWorkMs, totalWorkMs, progressSpanMs));
        return scaledProgressListener(delegate, stepOffsetMs, stepSpanMs, stepDurationMs, reportedTotalMs);
    }

    private static void publishPairwiseProgress(
            ProgressListener delegate,
            long progressOffsetMs,
            long progressSpanMs,
            long reportedTotalMs,
            long totalWorkMs,
            long completedWorkMs
    ) {
        if (delegate == null) {
            return;
        }
        long currentMs = progressOffsetMs + scalePairwiseProgress(completedWorkMs, totalWorkMs, progressSpanMs);
        long maxMs = progressOffsetMs + Math.max(1L, progressSpanMs);
        delegate.onProgress(Math.min(currentMs, maxMs), Math.max(1L, reportedTotalMs));
    }

    private static long scalePairwiseProgress(long workMs, long totalWorkMs, long progressSpanMs) {
        long safeTotalWorkMs = Math.max(1L, totalWorkMs);
        long safeProgressSpanMs = Math.max(1L, progressSpanMs);
        return Math.round((Math.max(0L, workMs) / (double) safeTotalWorkMs) * safeProgressSpanMs);
    }

    private static long durationAt(List<Long> durationsMs, int index, long fallbackDurationMs) {
        if (durationsMs != null && index >= 0 && index < durationsMs.size()) {
            Long durationMs = durationsMs.get(index);
            if (durationMs != null && durationMs > 0L) {
                return durationMs;
            }
        }
        return Math.max(1L, fallbackDurationMs);
    }

    private static long videoDurationAt(List<Long> durationsMs, int index, long totalDurationMs, int itemCount) {
        long fallbackDurationMs = Math.max(1L, totalDurationMs / Math.max(1, itemCount));
        return durationAt(durationsMs, index, fallbackDurationMs);
    }

    private static void ensureSuccessfulSession(FFmpegSession session, String message) throws AvtohitException {
        if (!ReturnCode.isSuccess(session.getReturnCode())) {
            throw new AvtohitException(message + " " + session.getOutput() + "\n" + session.getFailStackTrace());
        }
    }

    private static void ensureOutputFile(File outputFile, String message) throws IOException {
        if (!outputFile.exists() || outputFile.length() <= 0L) {
            throw new IOException(message);
        }
    }

    private static void appendSessionOutput(StringBuilder output, FFmpegSession session) {
        if (output.length() > 0) {
            output.append('\n');
        }
        output.append(session.getOutput());
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

    private static List<String> buildImageSegmentCommand(
            File imageFile,
            File outputFile,
            ExportProfile exportProfile,
            int frameRate,
            long durationMs
    ) {
        List<String> args = baseArgs();
        args.add("-loop");
        args.add("1");
        args.add("-framerate");
        args.add(String.valueOf(frameRate));
        args.add("-t");
        args.add(formatSeconds(durationMs));
        args.add("-i");
        args.add(imageFile.getAbsolutePath());
        args.add("-vf");
        args.add(buildScalePadFilter(exportProfile, frameRate));
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

    private static List<String> buildSilentVideoPairCommand(
            File leftFile,
            File rightFile,
            File outputFile
    ) {
        List<String> args = baseArgs();
        args.add("-i");
        args.add(leftFile.getAbsolutePath());
        args.add("-i");
        args.add(rightFile.getAbsolutePath());
        args.add("-filter_complex");
        args.add("[0:v:0]setpts=PTS-STARTPTS[v0];[1:v:0]setpts=PTS-STARTPTS[v1];[v0][v1]concat=n=2:v=1:a=0[v]");
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

    private static List<String> buildVideoPairCommand(
            File leftFile,
            File rightFile,
            File outputFile,
            ExportProfile exportProfile,
            int frameRate
    ) {
        ArrayList<File> pair = new ArrayList<>();
        pair.add(leftFile);
        pair.add(rightFile);
        return buildVideoSequenceCommand(pair, outputFile, exportProfile, frameRate);
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

    private static List<String> buildRepeatedVideoCommand(
            File videoFile,
            File outputFile,
            ExportProfile exportProfile,
            int frameRate,
            int repeatCount
    ) {
        List<String> args = baseArgs();
        args.add("-fflags");
        args.add("+genpts");
        if (repeatCount > 1) {
            args.add("-stream_loop");
            args.add(String.valueOf(repeatCount - 1));
        }
        args.add("-i");
        args.add(videoFile.getAbsolutePath());
        args.add("-vf");
        args.add(buildScalePadFilter(exportProfile, frameRate));
        args.add("-map");
        args.add("0:v:0");
        args.add("-map");
        args.add("0:a?");
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

    private static List<String> buildVideoSoundEffectsCommand(
            File videoFile,
            File outputFile,
            long durationMs,
            List<VideoSoundEffect> soundEffects,
            boolean useOriginalAudio
    ) {
        List<String> args = baseArgs();
        args.add("-i");
        args.add(videoFile.getAbsolutePath());
        if (!useOriginalAudio) {
            args.add("-f");
            args.add("lavfi");
            args.add("-t");
            args.add(formatSeconds(durationMs));
            args.add("-i");
            args.add("anullsrc=channel_layout=stereo:sample_rate=44100");
        }

        StringBuilder filter = new StringBuilder();
        int baseInputIndex = useOriginalAudio ? 0 : 1;
        filter.append('[')
                .append(baseInputIndex)
                .append(":a:0]aresample=44100,aformat=sample_fmts=fltp:channel_layouts=stereo,apad,atrim=0:")
                .append(formatSeconds(durationMs))
                .append("[base];");
        for (int i = 0; i < soundEffects.size(); i++) {
            VideoSoundEffect effect = soundEffects.get(i);
            filter.append("sine=frequency=")
                    .append(soundEffectFrequency(effect.type))
                    .append(":sample_rate=44100:duration=")
                    .append(formatSeconds(effect.durationMs))
                    .append(",volume=0.85,adelay=")
                    .append(effect.startMs)
                    .append('|')
                    .append(effect.startMs)
                    .append(",apad,atrim=0:")
                    .append(formatSeconds(durationMs))
                    .append("[fx")
                    .append(i)
                    .append("];");
        }
        filter.append("[base]");
        for (int i = 0; i < soundEffects.size(); i++) {
            filter.append("[fx").append(i).append(']');
        }
        filter.append("amix=inputs=")
                .append(soundEffects.size() + 1)
                .append(":duration=first:dropout_transition=0[a]");

        args.add("-filter_complex");
        args.add(filter.toString());
        args.add("-map");
        args.add("0:v:0");
        args.add("-map");
        args.add("[a]");
        args.add("-t");
        args.add(formatSeconds(durationMs));
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

    private static int soundEffectFrequency(String type) {
        if (VideoSoundEffect.TYPE_HIGH_BEEP.equals(type)) {
            return 1600;
        }
        if (VideoSoundEffect.TYPE_LOW_BEEP.equals(type)) {
            return 420;
        }
        return 1000;
    }

    private static List<String> buildAutoSplitSlideshowCycleCommand(
            List<File> imageFiles,
            File outputFile,
            ExportProfile exportProfile,
            int frameRate,
            long targetDurationMs,
            AvtohitDebugLogger debugLogger
    ) {
        List<Long> durationsMs = splitDurationAcrossImages(targetDurationMs, imageFiles.size());
        log(debugLogger, "auto_split_plan imageCount=" + imageFiles.size()
                + " targetDurationMs=" + targetDurationMs
                + " firstImageDurationMs=" + (durationsMs.isEmpty() ? 0L : durationsMs.get(0)));

        List<String> args = baseArgs();
        for (int i = 0; i < imageFiles.size(); i++) {
            args.add("-loop");
            args.add("1");
            args.add("-framerate");
            args.add(String.valueOf(frameRate));
            args.add("-t");
            args.add(formatSeconds(durationsMs.get(i)));
            args.add("-i");
            args.add(imageFiles.get(i).getAbsolutePath());
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

    private static List<String> buildSilentImageCommand(
            File imageFile,
            File outputFile,
            ExportProfile exportProfile,
            int frameRate,
            int slideSeconds
    ) {
        List<String> args = baseArgs();
        args.add("-loop");
        args.add("1");
        args.add("-framerate");
        args.add(String.valueOf(frameRate));
        args.add("-t");
        args.add(formatSeconds(slideSeconds * 1000L));
        args.add("-i");
        args.add(imageFile.getAbsolutePath());
        args.add("-vf");
        args.add(buildScalePadFilter(exportProfile, frameRate));
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

    private static List<Long> splitDurationAcrossImages(long targetDurationMs, int imageCount) {
        ArrayList<Long> durations = new ArrayList<>();
        if (imageCount <= 0) {
            return durations;
        }
        long safeTargetMs = Math.max(1L, targetDurationMs);
        long baseMs = Math.max(1L, safeTargetMs / imageCount);
        long remainderMs = Math.max(0L, safeTargetMs - (baseMs * imageCount));
        for (int i = 0; i < imageCount; i++) {
            long extraMs = i < remainderMs ? 1L : 0L;
            durations.add(baseMs + extraMs);
        }
        return durations;
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

    private static void prepareImageForFfmpeg(
            ContentResolver resolver,
            Uri imageUri,
            File target,
            ExportProfile exportProfile,
            AvtohitDebugLogger debugLogger,
            String label,
            int index
    ) throws IOException, AvtohitException {
        Bitmap bitmap = decodeScaledBitmap(resolver, imageUri, exportProfile);
        if (bitmap == null) {
            throw new AvtohitException("Image " + (index + 1) + " could not be decoded. Re-save it as JPG or PNG and try again.");
        }

        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        try (OutputStream output = new FileOutputStream(target)) {
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 95, output)) {
                throw new IOException("Could not normalize selected image.");
            }
            output.flush();
        } finally {
            bitmap.recycle();
        }
        if (!target.exists() || target.length() <= 0L) {
            throw new IOException("Normalized image file was not created.");
        }
        log(debugLogger, label + "_normalized_jpeg index=" + index
                + " width=" + width
                + " height=" + height
                + " bytes=" + target.length());
    }

    private static Bitmap decodeScaledBitmap(ContentResolver resolver, Uri imageUri, ExportProfile exportProfile) throws IOException {
        BitmapFactory.Options boundsOptions = new BitmapFactory.Options();
        boundsOptions.inJustDecodeBounds = true;
        try (InputStream boundsInput = openInput(resolver, imageUri)) {
            BitmapFactory.decodeStream(boundsInput, null, boundsOptions);
        }
        if (boundsOptions.outWidth <= 0 || boundsOptions.outHeight <= 0) {
            return null;
        }

        BitmapFactory.Options decodeOptions = new BitmapFactory.Options();
        decodeOptions.inPreferredConfig = Bitmap.Config.ARGB_8888;
        decodeOptions.inSampleSize = imageSampleSize(
                boundsOptions.outWidth,
                boundsOptions.outHeight,
                Math.max(exportProfile.width, exportProfile.height)
        );
        try (InputStream decodeInput = openInput(resolver, imageUri)) {
            return BitmapFactory.decodeStream(decodeInput, null, decodeOptions);
        }
    }

    private static InputStream openInput(ContentResolver resolver, Uri sourceUri) throws IOException {
        InputStream input = resolver.openInputStream(sourceUri);
        if (input == null) {
            throw new IOException("Could not open selected input.");
        }
        return input;
    }

    private static int imageSampleSize(int width, int height, int targetMaxDimension) {
        int sampleSize = 1;
        int sourceMax = Math.max(width, height);
        int allowedMax = Math.max(1, targetMaxDimension) * 2;
        while (sourceMax / sampleSize > allowedMax && sampleSize < 64) {
            sampleSize *= 2;
        }
        return sampleSize;
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

    private static boolean sameFile(File left, File right) {
        if (left == null || right == null) {
            return false;
        }
        return left.getAbsolutePath().equals(right.getAbsolutePath());
    }
}

package com.avtohit.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import com.avtohit.app.media.AvtohitException;
import com.avtohit.app.media.AvtohitProcessor;
import com.avtohit.app.media.ExportProfile;
import com.avtohit.app.media.VideoSoundEffect;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class AvtohitMergeService extends Service {
    private static final String ACTION_RUN_MERGE = "com.avtohit.app.action.RUN_MERGE";
    private static final String CHANNEL_PROGRESS = "avtohit_merge_progress";
    private static final String CHANNEL_RESULT = "avtohit_merge_result";
    private static final int NOTIFICATION_ID_PROGRESS = 7101;
    private static final int NOTIFICATION_ID_RESULT = 7102;

    public static final class MergeRequest {
        public final Uri selectedAudio;
        public final String selectedAudioName;
        public final Uri selectedVisual;
        public final String selectedVisualMime;
        public final String selectedVisualName;
        public final boolean selectedVisualIsVideo;
        public final ArrayList<Uri> selectedImageUris;
        public final ArrayList<String> selectedImageNames;
        public final ArrayList<Integer> selectedImageSeconds;
        public final ArrayList<Uri> selectedVideoUris;
        public final ArrayList<String> selectedVideoNames;
        public final ArrayList<Long> selectedVideoEpochs;
        public final ArrayList<Long> selectedVideoDurations;
        public final ArrayList<ArrayList<VideoSoundEffect>> selectedVideoSoundEffects;
        public final Uri destinationUri;
        public final ExportProfile selectedProfile;
        public final int selectedFrameRate;
        public final long selectedAudioDurationMs;
        public final long selectedVisualDurationMs;
        public final int selectedSlideSeconds;
        public final boolean selectedAutoSplitTime;
        public final int selectedVideoRepeatCount;

        public MergeRequest(
                Uri selectedAudio,
                String selectedAudioName,
                Uri selectedVisual,
                String selectedVisualMime,
                String selectedVisualName,
                boolean selectedVisualIsVideo,
                ArrayList<Uri> selectedImageUris,
                ArrayList<String> selectedImageNames,
                ArrayList<Integer> selectedImageSeconds,
                ArrayList<Uri> selectedVideoUris,
                ArrayList<String> selectedVideoNames,
                ArrayList<Long> selectedVideoEpochs,
                ArrayList<Long> selectedVideoDurations,
                ArrayList<ArrayList<VideoSoundEffect>> selectedVideoSoundEffects,
                Uri destinationUri,
                ExportProfile selectedProfile,
                int selectedFrameRate,
                long selectedAudioDurationMs,
                long selectedVisualDurationMs,
                int selectedSlideSeconds,
                boolean selectedAutoSplitTime,
                int selectedVideoRepeatCount
        ) {
            this.selectedAudio = selectedAudio;
            this.selectedAudioName = selectedAudioName;
            this.selectedVisual = selectedVisual;
            this.selectedVisualMime = selectedVisualMime;
            this.selectedVisualName = selectedVisualName;
            this.selectedVisualIsVideo = selectedVisualIsVideo;
            this.selectedImageUris = selectedImageUris;
            this.selectedImageNames = selectedImageNames;
            this.selectedImageSeconds = selectedImageSeconds;
            this.selectedVideoUris = selectedVideoUris;
            this.selectedVideoNames = selectedVideoNames;
            this.selectedVideoEpochs = selectedVideoEpochs;
            this.selectedVideoDurations = selectedVideoDurations;
            this.selectedVideoSoundEffects = selectedVideoSoundEffects;
            this.destinationUri = destinationUri;
            this.selectedProfile = selectedProfile;
            this.selectedFrameRate = selectedFrameRate;
            this.selectedAudioDurationMs = selectedAudioDurationMs;
            this.selectedVisualDurationMs = selectedVisualDurationMs;
            this.selectedSlideSeconds = selectedSlideSeconds;
            this.selectedAutoSplitTime = selectedAutoSplitTime;
            this.selectedVideoRepeatCount = selectedVideoRepeatCount;
        }
    }

    public static final class MergeState {
        public final boolean running;
        public final int progressScaled; // 0..1000
        public final AvtohitProcessor.Result lastResult;
        public final Throwable lastError;
        public final long completedAtMs;

        MergeState(boolean running, int progressScaled, AvtohitProcessor.Result lastResult, Throwable lastError, long completedAtMs) {
            this.running = running;
            this.progressScaled = progressScaled;
            this.lastResult = lastResult;
            this.lastError = lastError;
            this.completedAtMs = completedAtMs;
        }
    }

    public interface Listener {
        void onStateChanged(MergeState state);
    }

    private static final ExecutorService MERGE_EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static final CopyOnWriteArrayList<Listener> LISTENERS = new CopyOnWriteArrayList<>();
    private static volatile MergeRequest pendingRequest;
    private static volatile MergeState currentState = new MergeState(false, 0, null, null, 0L);

    private final AvtohitProcessor processor = new AvtohitProcessor();

    public static MergeState getCurrentState() {
        return currentState;
    }

    public static void clearCompletedState() {
        MergeState state = currentState;
        if (!state.running && (state.lastResult != null || state.lastError != null)) {
            currentState = new MergeState(false, 0, null, null, 0L);
        }
    }

    public static void addListener(Listener listener) {
        if (listener != null && !LISTENERS.contains(listener)) {
            LISTENERS.add(listener);
            listener.onStateChanged(currentState);
        }
    }

    public static void removeListener(Listener listener) {
        LISTENERS.remove(listener);
    }

    public static boolean startMerge(Context context, MergeRequest request) {
        if (context == null || request == null) {
            return false;
        }
        if (currentState.running) {
            return false;
        }
        Context appContext = context.getApplicationContext();
        pendingRequest = request;
        updateState(new MergeState(true, 0, null, null, 0L));
        Intent intent = new Intent(appContext, AvtohitMergeService.class);
        intent.setAction(ACTION_RUN_MERGE);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                appContext.startForegroundService(intent);
            } else {
                appContext.startService(intent);
            }
            return true;
        } catch (RuntimeException error) {
            updateState(new MergeState(false, 0, null, error, System.currentTimeMillis()));
            return false;
        }
    }

    private static void updateState(MergeState newState) {
        currentState = newState;
        MAIN_HANDLER.post(() -> {
            for (Listener listener : LISTENERS) {
                listener.onStateChanged(newState);
            }
        });
    }

    @Override
    public void onCreate() {
        super.onCreate();
        ensureChannels();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        ensureChannels();
        Notification initialNotification = buildProgressNotification(0);
        startForegroundCompat(initialNotification);

        final MergeRequest request = pendingRequest;
        pendingRequest = null;
        if (request == null) {
            stopForegroundCompat();
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        MERGE_EXECUTOR.submit(() -> executeMerge(request, startId));
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void executeMerge(MergeRequest req, int startId) {
        Context appContext = getApplicationContext();
        AvtohitDebugLogger logger = new AvtohitDebugLogger(appContext);
        PowerManager.WakeLock wakeLock = acquireWakeLock(appContext);
        final int[] lastNotifiedPercent = new int[]{-1};

        try {
            logger.startRun("AVTOHIT background merge");
            logRenderInputs(logger, req);
            AvtohitProcessor.ProgressListener progressListener = (currentMs, totalMs) -> {
                if (totalMs <= 0L) {
                    return;
                }
                int scaled = (int) Math.min(1000L, Math.max(0L, (currentMs * 1000L) / totalMs));
                int percent = Math.min(100, Math.max(0, scaled / 10));
                updateState(new MergeState(true, scaled, null, null, 0L));
                if (percent != lastNotifiedPercent[0]) {
                    lastNotifiedPercent[0] = percent;
                    notifyProgress(percent);
                }
            };

            AvtohitProcessor.Result result;
            if (req.selectedAudio == null && !req.selectedVisualIsVideo && !req.selectedImageUris.isEmpty()) {
                result = processor.renderImagesSilent(
                        appContext,
                        req.selectedImageUris,
                        req.destinationUri,
                        req.selectedProfile,
                        req.selectedFrameRate,
                        req.selectedSlideSeconds,
                        req.selectedImageSeconds,
                        progressListener,
                        logger
                );
            } else if (req.selectedAudio == null && req.selectedVisualIsVideo && req.selectedVideoUris.size() == 1) {
                result = processor.renderVideoRepeated(
                        appContext,
                        req.selectedVideoUris.get(0),
                        req.destinationUri,
                        req.selectedProfile,
                        req.selectedFrameRate,
                        req.selectedVisualDurationMs,
                        req.selectedVideoRepeatCount,
                        req.selectedVideoSoundEffects.isEmpty() ? null : req.selectedVideoSoundEffects.get(0),
                        progressListener,
                        logger
                );
            } else if (req.selectedAudio == null && req.selectedVideoUris.size() > 1) {
                result = processor.renderVideos(
                        appContext,
                        req.selectedVideoUris,
                        req.destinationUri,
                        req.selectedProfile,
                        req.selectedFrameRate,
                        req.selectedVideoDurations,
                        req.selectedVideoSoundEffects,
                        req.selectedVisualDurationMs,
                        progressListener,
                        logger
                );
            } else if (!req.selectedVisualIsVideo && !req.selectedImageUris.isEmpty()) {
                result = processor.renderImages(
                        appContext,
                        req.selectedAudio,
                        req.selectedImageUris,
                        req.destinationUri,
                        req.selectedProfile,
                        req.selectedFrameRate,
                        req.selectedAudioDurationMs,
                        req.selectedSlideSeconds,
                        req.selectedAutoSplitTime,
                        req.selectedImageSeconds,
                        progressListener,
                        logger
                );
            } else {
                result = processor.render(
                        appContext,
                        req.selectedAudio,
                        req.selectedVisual,
                        req.selectedVisualMime,
                        req.destinationUri,
                        req.selectedProfile,
                        req.selectedFrameRate,
                        req.selectedAudioDurationMs,
                        progressListener,
                        logger
                );
            }

            logger.append("render_success outputBytes=" + result.outputBytes
                    + " visualKind=" + result.visualKind
                    + " videoReencoded=" + result.videoReencoded
                    + " usesImportedMp3=" + result.usesImportedMp3);
            updateState(new MergeState(false, 1000, result, null, System.currentTimeMillis()));
            notifyResult(true, getString(R.string.notif_merge_done_body));
        } catch (IOException | AvtohitException | RuntimeException error) {
            logger.append(error);
            updateState(new MergeState(false, 0, null, error, System.currentTimeMillis()));
            String msg = error.getMessage() != null ? error.getMessage() : error.getClass().getSimpleName();
            notifyResult(false, getString(R.string.failed_detail, msg));
        } finally {
            releaseWakeLock(wakeLock);
            MAIN_HANDLER.post(() -> {
                stopForegroundCompat();
                stopSelf(startId);
            });
        }
    }

    private PowerManager.WakeLock acquireWakeLock(Context context) {
        try {
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            if (pm == null) {
                return null;
            }
            PowerManager.WakeLock lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "avtohit:merge_wakelock");
            lock.setReferenceCounted(false);
            lock.acquire(30L * 60L * 1000L);
            return lock;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private void releaseWakeLock(PowerManager.WakeLock wakeLock) {
        if (wakeLock != null) {
            try {
                if (wakeLock.isHeld()) {
                    wakeLock.release();
                }
            } catch (RuntimeException ignored) {
            }
        }
    }

    private void startForegroundCompat(Notification notification) {
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(
                        NOTIFICATION_ID_PROGRESS,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                );
            } else {
                startForeground(NOTIFICATION_ID_PROGRESS, notification);
            }
        } catch (RuntimeException ignored) {
        }
    }

    private void stopForegroundCompat() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE);
            } else {
                stopForeground(true);
            }
        } catch (RuntimeException ignored) {
        }
    }

    private void ensureChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) {
            return;
        }
        NotificationChannel progressChannel = new NotificationChannel(
                CHANNEL_PROGRESS,
                getString(R.string.notif_channel_merge_progress),
                NotificationManager.IMPORTANCE_LOW
        );
        progressChannel.setShowBadge(false);
        nm.createNotificationChannel(progressChannel);

        NotificationChannel resultChannel = new NotificationChannel(
                CHANNEL_RESULT,
                getString(R.string.notif_channel_merge_result),
                NotificationManager.IMPORTANCE_DEFAULT
        );
        nm.createNotificationChannel(resultChannel);
    }

    private void notifyProgress(int percent) {
        if (!canPostNotifications()) {
            return;
        }
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(NOTIFICATION_ID_PROGRESS, buildProgressNotification(percent));
        }
    }

    private void notifyResult(boolean success, String message) {
        if (!canPostNotifications()) {
            return;
        }
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) {
            return;
        }
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_RESULT)
                : new Notification.Builder(this);
        builder.setSmallIcon(R.drawable.ic_film)
                .setContentTitle(getString(success ? R.string.notif_merge_done_title : R.string.notif_merge_failed_title))
                .setContentText(message)
                .setContentIntent(contentPendingIntent())
                .setAutoCancel(true);
        nm.notify(NOTIFICATION_ID_RESULT, builder.build());
    }

    private Notification buildProgressNotification(int percent) {
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_PROGRESS)
                : new Notification.Builder(this);
        String text = percent > 0
                ? String.format(Locale.US, "Merging %d%%", percent)
                : getString(R.string.creating);
        builder.setSmallIcon(R.drawable.ic_film)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(text)
                .setContentIntent(contentPendingIntent())
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setProgress(100, Math.min(100, Math.max(0, percent)), percent <= 0);
        return builder.build();
    }

    private PendingIntent contentPendingIntent() {
        Intent openIntent = new Intent(this, MainActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(
                this,
                7100,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
    }

    private boolean canPostNotifications() {
        if (Build.VERSION.SDK_INT < 33) {
            return true;
        }
        return checkSelfPermission("android.permission.POST_NOTIFICATIONS") == PackageManager.PERMISSION_GRANTED;
    }

    private static void logRenderInputs(AvtohitDebugLogger logger, MergeRequest req) {
        logger.append("audio_name=" + safeLogValue(req.selectedAudioName));
        logger.append("audio_uri=" + uriSummary(req.selectedAudio));
        logger.append("audio_duration_ms=" + req.selectedAudioDurationMs);
        logger.append("visual_name=" + safeLogValue(req.selectedVisualName));
        logger.append("visual_uri=" + uriSummary(req.selectedVisual));
        logger.append("visual_mime=" + safeLogValue(req.selectedVisualMime));
        logger.append("visual_is_video=" + req.selectedVisualIsVideo);
        logger.append("visual_duration_ms=" + req.selectedVisualDurationMs);
        logger.append("image_count=" + req.selectedImageUris.size());
        logger.append("slide_seconds=" + req.selectedSlideSeconds);
        logger.append("auto_split_time=" + req.selectedAutoSplitTime);
        logger.append("video_repeat_count=" + req.selectedVideoRepeatCount);
        for (int i = 0; i < req.selectedImageUris.size(); i++) {
            String imageName = i < req.selectedImageNames.size() ? req.selectedImageNames.get(i) : "";
            logger.append("image_order[" + i + "] name=" + safeLogValue(imageName) + " uri=" + uriSummary(req.selectedImageUris.get(i)));
        }
        logger.append("video_count=" + req.selectedVideoUris.size());
        for (int i = 0; i < req.selectedVideoUris.size(); i++) {
            String videoName = i < req.selectedVideoNames.size() ? req.selectedVideoNames.get(i) : "";
            long epochMs = i < req.selectedVideoEpochs.size() ? req.selectedVideoEpochs.get(i) : 0L;
            long durationMs = i < req.selectedVideoDurations.size() ? req.selectedVideoDurations.get(i) : 0L;
            List<VideoSoundEffect> effects = i < req.selectedVideoSoundEffects.size() ? req.selectedVideoSoundEffects.get(i) : null;
            int soundEffectCount = effects != null ? effects.size() : 0;
            logger.append("video_order[" + i + "] name=" + safeLogValue(videoName)
                    + " modifiedEpochMs=" + epochMs
                    + " durationMs=" + durationMs
                    + " soundEffectCount=" + soundEffectCount
                    + " uri=" + uriSummary(req.selectedVideoUris.get(i)));
        }
        logger.append("destination_uri=" + uriSummary(req.destinationUri));
        logger.append("export_profile=" + req.selectedProfile.label + " " + req.selectedProfile.width + "x" + req.selectedProfile.height);
        logger.append("frame_rate=" + req.selectedFrameRate);
        logger.append("log_location=" + logger.displayLocation());
    }

    private static String safeLogValue(String value) {
        if (value == null) {
            return "";
        }
        return value.replace('\n', ' ').replace('\r', ' ').trim();
    }

    private static String uriSummary(Uri uri) {
        if (uri == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        builder.append(uri.getScheme() != null ? uri.getScheme() : "");
        if (uri.getAuthority() != null) {
            builder.append("://").append(uri.getAuthority());
        }
        String path = uri.getPath();
        if (path != null && !path.trim().isEmpty()) {
            builder.append(path);
        }
        return builder.toString();
    }
}

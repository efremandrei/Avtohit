package com.avtohit.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.WindowInsets;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.TextView;

import com.avtohit.app.media.AvtohitException;
import com.avtohit.app.media.AvtohitProcessor;
import com.avtohit.app.media.ExportProfile;

import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int REQUEST_AUDIO = 1001;
    private static final int REQUEST_VISUAL = 1002;
    private static final int REQUEST_OUTPUT = 1003;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AvtohitProcessor processor = new AvtohitProcessor();

    private final Runnable previewTicker = new Runnable() {
        @Override
        public void run() {
            if (previewPlayer == null || !previewPlaying) {
                return;
            }
            int current = previewPlayer.getCurrentPosition();
            previewSeek.setProgress(current);
            updatePreviewTime(current, audioDurationMs);
            mainHandler.postDelayed(this, 200L);
        }
    };

    private TextView projectTitle;
    private TextView projectSubtitle;
    private TextView projectMode;
    private TextView visualChip;
    private TextView audioChip;
    private TextView exportChip;
    private TextView previewEmptyState;
    private TextView previewModeLabel;
    private TextView previewTime;
    private TextView status;
    private TextView timelineHint;
    private TextView visualTrackClip;
    private TextView visualTrackMeta;
    private TextView audioTrackClip;
    private TextView audioTrackMeta;
    private TextView musicTrackPlaceholder;
    private ImageView previewArtwork;
    private ImageButton playButton;
    private SeekBar previewSeek;
    private Button exportButton;
    private Button selectVisualButton;
    private Button selectAudioButton;
    private ImageButton settingsButton;
    private ProgressBar progress;
    private LinearLayout audioWaveform;
    private View visualTrackLane;
    private View audioTrackLane;

    private Uri audioUri;
    private Uri visualUri;
    private String visualMimeType;
    private String customProjectTitle;
    private String audioDisplayName;
    private String visualDisplayName;
    private boolean visualIsVideo;
    private boolean rendering;
    private boolean previewPlaying;
    private long audioDurationMs;
    private long visualDurationMs;
    private ExportProfile exportProfile = ExportProfile.P1080;
    private int frameRate = 30;
    private MediaPlayer previewPlayer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        bindViews();
        applyTopInset();
        bindActions();
        refreshUi();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        releasePreviewPlayer();
        executor.shutdownNow();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }

        Uri uri = data.getData();
        if (requestCode == REQUEST_AUDIO) {
            takeReadPermission(data, uri);
            audioUri = uri;
            audioDisplayName = AvtohitProcessor.displayName(this, uri);
            audioDurationMs = readDuration(uri);
            releasePreviewPlayer();
            status.setText(R.string.ready);
        } else if (requestCode == REQUEST_VISUAL) {
            takeReadPermission(data, uri);
            visualUri = uri;
            visualMimeType = getContentResolver().getType(uri);
            visualDisplayName = AvtohitProcessor.displayName(this, uri);
            visualIsVideo = isVideoVisual(uri, visualMimeType);
            visualDurationMs = visualIsVideo ? readDuration(uri) : 0L;
            status.setText(R.string.ready);
        } else if (requestCode == REQUEST_OUTPUT) {
            takeWritePermission(data, uri);
            renderTo(uri);
            return;
        }

        refreshUi();
    }

    private void bindViews() {
        projectTitle = findViewById(R.id.projectTitle);
        projectSubtitle = findViewById(R.id.projectSubtitle);
        projectMode = findViewById(R.id.projectMode);
        visualChip = findViewById(R.id.visualChip);
        audioChip = findViewById(R.id.audioChip);
        exportChip = findViewById(R.id.exportChip);
        previewArtwork = findViewById(R.id.previewArtwork);
        previewEmptyState = findViewById(R.id.previewEmptyState);
        previewModeLabel = findViewById(R.id.previewModeLabel);
        previewTime = findViewById(R.id.previewTime);
        status = findViewById(R.id.status);
        timelineHint = findViewById(R.id.timelineHint);
        visualTrackClip = findViewById(R.id.visualTrackClip);
        visualTrackMeta = findViewById(R.id.visualTrackMeta);
        audioTrackClip = findViewById(R.id.audioTrackClip);
        audioTrackMeta = findViewById(R.id.audioTrackMeta);
        musicTrackPlaceholder = findViewById(R.id.musicTrackPlaceholder);
        playButton = findViewById(R.id.playButton);
        previewSeek = findViewById(R.id.previewSeek);
        exportButton = findViewById(R.id.exportButton);
        selectVisualButton = findViewById(R.id.selectVisualButton);
        selectAudioButton = findViewById(R.id.selectAudioButton);
        settingsButton = findViewById(R.id.settingsButton);
        progress = findViewById(R.id.progress);
        audioWaveform = findViewById(R.id.audioWaveform);
        visualTrackLane = findViewById(R.id.visualTrackLane);
        audioTrackLane = findViewById(R.id.audioTrackLane);
    }

    private void bindActions() {
        findViewById(R.id.backButton).setOnClickListener(view -> finish());
        projectTitle.setOnClickListener(view -> showRenameDialog());
        selectVisualButton.setOnClickListener(view -> openVisualPicker());
        selectAudioButton.setOnClickListener(view -> openAudioPicker());
        settingsButton.setOnClickListener(view -> showExportDialog(false));
        exportButton.setOnClickListener(view -> showExportDialog(true));
        playButton.setOnClickListener(view -> togglePreviewPlayback());

        previewSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    updatePreviewTime(progress, audioDurationMs);
                    if (previewPlayer != null) {
                        previewPlayer.seekTo(progress);
                    }
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                mainHandler.removeCallbacks(previewTicker);
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                if (previewPlaying) {
                    mainHandler.post(previewTicker);
                }
            }
        });
    }

    private void openAudioPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("audio/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"audio/mpeg", "audio/mp3", "audio/x-mpeg"});
        startActivityForResult(intent, REQUEST_AUDIO);
    }

    private void openVisualPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "video/*"});
        startActivityForResult(intent, REQUEST_VISUAL);
    }

    private void openOutputPicker() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("video/mp4");
        intent.putExtra(Intent.EXTRA_TITLE, defaultOutputName());
        startActivityForResult(intent, REQUEST_OUTPUT);
    }

    private void renderTo(Uri destinationUri) {
        setRendering(true);
        releasePreviewPlayer();
        status.setText(R.string.creating);

        Uri selectedAudio = audioUri;
        Uri selectedVisual = visualUri;
        String selectedVisualMime = visualMimeType;
        ExportProfile selectedProfile = exportProfile;
        int selectedFrameRate = frameRate;

        executor.submit(() -> {
            try {
                AvtohitProcessor.Result result = processor.render(
                        getApplicationContext(),
                        selectedAudio,
                        selectedVisual,
                        selectedVisualMime,
                        destinationUri,
                        selectedProfile,
                        selectedFrameRate
                );
                mainHandler.post(() -> onRenderSuccess(result));
            } catch (IOException | AvtohitException | RuntimeException error) {
                mainHandler.post(() -> onRenderFailure(error));
            }
        });
    }

    private void onRenderSuccess(AvtohitProcessor.Result result) {
        setRendering(false);
        String mode = result.visualKind == AvtohitProcessor.VisualKind.IMAGE
                ? getString(R.string.mode_picture)
                : getString(R.string.mode_video);
        String audioMode = result.videoReencoded
                ? getString(R.string.video_reencoded_mp3_copied)
                : getString(R.string.mp3_copied);
        status.setText(getString(R.string.done_detail, mode, audioMode));
        projectSubtitle.setText(timestampedSubtitle("Last export"));
    }

    private void onRenderFailure(Throwable error) {
        setRendering(false);
        status.setText(getString(R.string.failed_detail, safeMessage(error)));
    }

    private void refreshUi() {
        refreshProjectHeader();
        refreshPreview();
        refreshTimeline();
        updateActions();
    }

    private void refreshProjectHeader() {
        projectTitle.setText(currentProjectTitle());
        projectSubtitle.setText(audioUri != null || visualUri != null
                ? timestampedSubtitle("Draft updated")
                : getString(R.string.project_subtitle_default));

        String visualSummary = visualDisplayName != null ? visualDisplayName : getString(R.string.visual_not_selected);
        String audioSummary = audioDisplayName != null ? audioDisplayName : getString(R.string.mp3_not_selected);
        String exportSummary = exportProfile.label + " / " + frameRate + "fps";

        visualChip.setText(getString(R.string.project_chip_visual, ellipsize(visualSummary, 20)));
        audioChip.setText(getString(R.string.project_chip_audio, ellipsize(audioSummary, 20)));
        exportChip.setText(getString(R.string.project_chip_export, exportSummary));

        if (audioUri == null && visualUri == null) {
            projectMode.setText(R.string.project_mode_default);
            status.setText(R.string.empty_project_status);
        } else if (visualUri == null) {
            projectMode.setText(R.string.no_visual_preview);
        } else if (audioUri == null) {
            projectMode.setText(R.string.no_audio_preview);
        } else if (visualIsVideo) {
            projectMode.setText(R.string.visual_video_meta);
        } else if (visualUri != null) {
            projectMode.setText(R.string.visual_image_meta);
        }
    }

    private void refreshPreview() {
        if (visualUri == null) {
            previewArtwork.setImageResource(R.drawable.ic_launcher_foreground);
            previewEmptyState.setVisibility(View.VISIBLE);
            previewModeLabel.setText(R.string.preview_audio_only);
        } else {
            Bitmap bitmap = loadPreviewBitmap();
            if (bitmap != null) {
                previewArtwork.setImageBitmap(bitmap);
            } else {
                previewArtwork.setImageResource(R.drawable.ic_launcher_foreground);
            }
            previewEmptyState.setVisibility(View.GONE);
            previewModeLabel.setText(visualIsVideo ? R.string.mode_video : R.string.mode_picture);
        }

        int totalMs = (int) Math.min(Integer.MAX_VALUE, audioDurationMs);
        previewSeek.setMax(Math.max(totalMs, 1));
        if (!previewPlaying) {
            previewSeek.setProgress(0);
            updatePreviewTime(0, audioDurationMs);
        }

        if (audioUri == null) {
            audioTrackMeta.setText(R.string.no_audio_preview);
        } else {
            audioTrackMeta.setText(getString(R.string.audio_meta, formatDuration(audioDurationMs)));
        }
    }

    private void refreshTimeline() {
        int laneWidth = Math.max(0, getResources().getDisplayMetrics().widthPixels - dp(32) - dp(36));
        int clipWidth = calculateClipWidth(laneWidth);
        setViewWidth(visualTrackLane, laneWidth);
        setViewWidth(audioTrackLane, laneWidth);
        setViewWidth(visualTrackClip, clipWidth);
        setViewWidth(audioWaveform, clipWidth);
        setViewWidth(musicTrackPlaceholder, laneWidth);

        if (visualUri == null) {
            visualTrackClip.setText(R.string.visual_track_default);
            visualTrackMeta.setText(R.string.no_visual_preview);
        } else {
            visualTrackClip.setText(getString(R.string.visual_track_clip, ellipsize(visualDisplayName, 28)));
            visualTrackMeta.setText(visualIsVideo ? R.string.visual_video_meta : R.string.visual_image_meta);
        }

        if (audioUri == null) {
            audioTrackClip.setText(R.string.audio_track_default);
            audioTrackMeta.setText(R.string.no_audio_preview);
        } else {
            audioTrackClip.setText(getString(R.string.audio_track_clip, ellipsize(audioDisplayName, 30)));
            audioTrackMeta.setText(getString(R.string.audio_meta, formatDuration(audioDurationMs)));
        }

        populateWaveform(audioWaveform, clipWidth);
        timelineHint.setText(audioUri == null
                ? getString(R.string.timeline_hint)
                : "Timeline scaled to " + formatDuration(audioDurationMs) + " of audio.");
    }

    private void updateActions() {
        boolean readyToExport = !rendering && audioUri != null && visualUri != null;
        selectVisualButton.setEnabled(!rendering);
        selectAudioButton.setEnabled(!rendering);
        selectVisualButton.setSelected(visualUri != null);
        selectAudioButton.setSelected(audioUri != null);
        settingsButton.setEnabled(!rendering);
        exportButton.setEnabled(readyToExport);
        playButton.setEnabled(!rendering && audioUri != null);
        previewSeek.setEnabled(!rendering && audioUri != null);
        progress.setVisibility(rendering ? View.VISIBLE : View.GONE);
        playButton.setAlpha(playButton.isEnabled() ? 1f : 0.45f);
    }

    private void showExportDialog(boolean startExportWhenSaved) {
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_export, null);
        RadioGroup resolutionGroup = dialogView.findViewById(R.id.resolutionGroup);
        RadioGroup fpsGroup = dialogView.findViewById(R.id.fpsGroup);
        TextView exportSummary = dialogView.findViewById(R.id.exportSummary);

        if (exportProfile == ExportProfile.P720) {
            resolutionGroup.check(R.id.resolution720);
        } else if (exportProfile == ExportProfile.P4K) {
            resolutionGroup.check(R.id.resolution4k);
        } else {
            resolutionGroup.check(R.id.resolution1080);
        }
        fpsGroup.check(frameRate == 60 ? R.id.fps60 : R.id.fps30);
        updateExportSummary(dialogView, exportSummary);

        RadioGroup.OnCheckedChangeListener listener = (group, checkedId) -> updateExportSummary(dialogView, exportSummary);
        resolutionGroup.setOnCheckedChangeListener(listener);
        fpsGroup.setOnCheckedChangeListener(listener);

        new AlertDialog.Builder(this)
                .setTitle(R.string.export_dialog_title)
                .setView(dialogView)
                .setNegativeButton(R.string.export_cancel, null)
                .setNeutralButton(R.string.export_apply, (dialog, which) -> {
                    applyExportSelection(dialogView);
                    status.setText(getString(R.string.export_status_saved, exportProfile.label, frameRate));
                    refreshUi();
                })
                .setPositiveButton(startExportWhenSaved ? R.string.export_start : R.string.export_apply, (dialog, which) -> {
                    applyExportSelection(dialogView);
                    refreshUi();
                    if (startExportWhenSaved) {
                        openOutputPicker();
                    } else {
                        status.setText(getString(R.string.export_status_saved, exportProfile.label, frameRate));
                    }
                })
                .show();
    }

    private void showRenameDialog() {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setText(currentProjectTitle());
        input.setSelection(input.getText().length());

        new AlertDialog.Builder(this)
                .setTitle("Rename Project")
                .setView(input)
                .setNegativeButton(R.string.export_cancel, null)
                .setPositiveButton("Save", (dialog, which) -> {
                    String value = input.getText().toString().trim();
                    customProjectTitle = value.isEmpty() ? null : value;
                    refreshProjectHeader();
                })
                .show();
    }

    private void togglePreviewPlayback() {
        if (audioUri == null) {
            status.setText(R.string.audio_preview_unavailable);
            return;
        }
        if (previewPlaying) {
            pausePreview();
        } else {
            startPreview();
        }
    }

    private void startPreview() {
        try {
            if (previewPlayer == null) {
                previewPlayer = new MediaPlayer();
                previewPlayer.setAudioAttributes(new AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build());
                previewPlayer.setDataSource(this, audioUri);
                previewPlayer.setOnCompletionListener(player -> {
                    pausePreview();
                    previewSeek.setProgress(0);
                    updatePreviewTime(0, audioDurationMs);
                });
                previewPlayer.prepare();
            }
            previewPlayer.start();
            previewPlaying = true;
            playButton.setImageResource(android.R.drawable.ic_media_pause);
            playButton.setContentDescription(getString(R.string.pause_button));
            mainHandler.removeCallbacks(previewTicker);
            mainHandler.post(previewTicker);
        } catch (IOException error) {
            status.setText(getString(R.string.failed_detail, safeMessage(error)));
        }
    }

    private void pausePreview() {
        if (previewPlayer != null && previewPlayer.isPlaying()) {
            previewPlayer.pause();
        }
        previewPlaying = false;
        playButton.setImageResource(android.R.drawable.ic_media_play);
        playButton.setContentDescription(getString(R.string.play_button));
        mainHandler.removeCallbacks(previewTicker);
    }

    private void releasePreviewPlayer() {
        pausePreview();
        if (previewPlayer != null) {
            previewPlayer.release();
            previewPlayer = null;
        }
    }

    private void applyExportSelection(View dialogView) {
        RadioGroup resolutionGroup = dialogView.findViewById(R.id.resolutionGroup);
        RadioGroup fpsGroup = dialogView.findViewById(R.id.fpsGroup);
        int resolutionId = resolutionGroup.getCheckedRadioButtonId();
        int fpsId = fpsGroup.getCheckedRadioButtonId();

        if (resolutionId == R.id.resolution720) {
            exportProfile = ExportProfile.P720;
        } else if (resolutionId == R.id.resolution4k) {
            exportProfile = ExportProfile.P4K;
        } else {
            exportProfile = ExportProfile.P1080;
        }

        frameRate = fpsId == R.id.fps60 ? 60 : 30;
    }

    private void updateExportSummary(View dialogView, TextView exportSummary) {
        RadioGroup resolutionGroup = dialogView.findViewById(R.id.resolutionGroup);
        RadioGroup fpsGroup = dialogView.findViewById(R.id.fpsGroup);

        String resolutionLabel;
        int resolutionId = resolutionGroup.getCheckedRadioButtonId();
        if (resolutionId == R.id.resolution720) {
            resolutionLabel = getString(R.string.export_resolution_720);
        } else if (resolutionId == R.id.resolution4k) {
            resolutionLabel = getString(R.string.export_resolution_4k);
        } else {
            resolutionLabel = getString(R.string.export_resolution_1080);
        }

        int selectedFrameRate = fpsGroup.getCheckedRadioButtonId() == R.id.fps60 ? 60 : 30;
        exportSummary.setText(getString(R.string.export_summary, resolutionLabel, selectedFrameRate));
    }

    private Bitmap loadPreviewBitmap() {
        if (visualUri == null) {
            return null;
        }
        if (visualIsVideo) {
            MediaMetadataRetriever retriever = new MediaMetadataRetriever();
            try {
                retriever.setDataSource(this, visualUri);
                return retriever.getFrameAtTime(0L);
            } catch (RuntimeException ignored) {
                return null;
            } finally {
                try {
                    retriever.release();
                } catch (IOException ignored) {
                    // Ignore cleanup failures for preview extraction.
                }
            }
        }

        try (InputStream input = getContentResolver().openInputStream(visualUri)) {
            if (input == null) {
                return null;
            }
            return BitmapFactory.decodeStream(input);
        } catch (IOException ignored) {
            return null;
        }
    }

    private long readDuration(Uri uri) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(this, uri);
            String value = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (value == null || value.trim().isEmpty()) {
                return 0L;
            }
            return Long.parseLong(value);
        } catch (RuntimeException ignored) {
            return 0L;
        } finally {
            try {
                retriever.release();
            } catch (IOException ignored) {
                // Ignore cleanup failures for metadata extraction.
            }
        }
    }

    private boolean isVideoVisual(Uri uri, String mimeType) {
        String source = mimeType != null ? mimeType.toLowerCase(Locale.US) : uri.toString().toLowerCase(Locale.US);
        return source.startsWith("video/") || source.endsWith(".mp4") || source.endsWith(".mov") || source.endsWith(".m4v") || source.endsWith(".webm");
    }

    private int calculateClipWidth(int laneWidth) {
        int minimum = Math.max(dp(140), laneWidth / 2);
        if (audioDurationMs <= 0L) {
            return minimum;
        }
        long seconds = Math.max(1L, audioDurationMs / 1000L);
        int scaled = minimum + (int) Math.min(laneWidth / 2, seconds * dp(4));
        return Math.min(laneWidth, Math.max(minimum, scaled));
    }

    private void populateWaveform(LinearLayout container, int widthPx) {
        container.removeAllViews();
        int barCount = Math.max(18, widthPx / dp(8));
        int seed = audioDisplayName != null ? audioDisplayName.hashCode() : 7;
        for (int i = 0; i < barCount; i++) {
            View bar = new View(this);
            int height = dp(12) + Math.abs(seed + (i * 37)) % dp(26);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(4), height);
            params.rightMargin = dp(2);
            params.topMargin = dp(6);
            params.bottomMargin = dp(6);
            bar.setLayoutParams(params);
            GradientDrawable drawable = new GradientDrawable();
            drawable.setColor(0xCCFFFFFF);
            drawable.setCornerRadius(dp(2));
            bar.setBackground(drawable);
            container.addView(bar);
        }
    }

    private void updatePreviewTime(long currentMs, long totalMs) {
        previewTime.setText(getString(R.string.time_pair, formatDuration(currentMs), formatDuration(totalMs)));
    }

    private void setRendering(boolean rendering) {
        this.rendering = rendering;
        updateActions();
    }

    private void takeReadPermission(Intent data, Uri uri) {
        if ((data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) == 0) {
            return;
        }
        try {
            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException ignored) {
            // Some providers grant only transient access.
        }
    }

    private void takeWritePermission(Intent data, Uri uri) {
        if ((data.getFlags() & Intent.FLAG_GRANT_WRITE_URI_PERMISSION) == 0) {
            return;
        }
        try {
            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (SecurityException ignored) {
            // Some providers grant only transient access.
        }
    }

    private String currentProjectTitle() {
        if (customProjectTitle != null && !customProjectTitle.trim().isEmpty()) {
            return customProjectTitle;
        }
        if (audioDisplayName != null) {
            return ellipsize(stripExtension(audioDisplayName), 26);
        }
        return getString(R.string.project_title_default);
    }

    private String timestampedSubtitle(String prefix) {
        String stamp = new SimpleDateFormat("MMM d, HH:mm", Locale.US).format(new Date());
        return prefix + " " + stamp;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void setViewWidth(View view, int widthPx) {
        android.view.ViewGroup.LayoutParams params = view.getLayoutParams();
        params.width = widthPx;
        view.setLayoutParams(params);
    }

    private String defaultOutputName() {
        if (audioDisplayName != null && !audioDisplayName.trim().isEmpty()) {
            return stripExtension(audioDisplayName) + ".mp4";
        }
        String timestamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
        return "AVTOHIT-" + timestamp + ".mp4";
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        if (message == null || message.trim().isEmpty()) {
            return error.getClass().getSimpleName();
        }
        String compact = message.replace('\n', ' ').trim();
        if (compact.length() > 280) {
            return compact.substring(0, 280) + "...";
        }
        return compact;
    }

    private static String formatDuration(long millis) {
        long totalSeconds = Math.max(0L, millis / 1000L);
        long hours = totalSeconds / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        long seconds = totalSeconds % 60L;
        if (hours > 0L) {
            return String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds);
        }
        return String.format(Locale.US, "%02d:%02d", minutes, seconds);
    }

    private static String ellipsize(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        return value.substring(0, Math.max(0, max - 1)).trim() + "...";
    }

    private void applyTopInset() {
        final View content = ((View) findViewById(android.R.id.content)).getRootView();
        content.setOnApplyWindowInsetsListener((view, insets) -> {
            int topInset = 0;
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                topInset = insets.getInsets(WindowInsets.Type.statusBars()).top;
            } else {
                topInset = insets.getSystemWindowInsetTop();
            }
            view.setPadding(
                    view.getPaddingLeft(),
                    topInset,
                    view.getPaddingRight(),
                    view.getPaddingBottom()
            );
            return insets;
        });
        content.requestApplyInsets();
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        if (dot <= 0) {
            return name;
        }
        return name.substring(0, dot);
    }
}

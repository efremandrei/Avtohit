package com.avtohit.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
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
    private static final String PREFS_NAME = "avtohit_settings";
    private static final String PREF_SKIN = "app_skin";

    private enum AppSkin {
        LIGHT("light", "Light", 0xFFF7F8F5, 0xFFFFFFFF, 0xFFEEF3EF, 0xFF151817, 0xFF5D6662, 0xFFD5DDD8, true),
        FOREST("forest", "Forest", 0xFFEAF3EF, 0xFFF9FCFA, 0xFFE2ECE7, 0xFF17342B, 0xFF567065, 0xFFC8D9D0, true),
        NIGHT("night", "Night", 0xFF111615, 0xFF1B2421, 0xFF24302B, 0xFFF3F7F5, 0xFF9FB1A9, 0xFF31403A, false);

        final String key;
        final String label;
        final int backgroundColor;
        final int surfaceColor;
        final int surfaceAltColor;
        final int textColor;
        final int mutedColor;
        final int borderColor;
        final boolean lightStatusBar;

        AppSkin(String key, String label, int backgroundColor, int surfaceColor, int surfaceAltColor, int textColor, int mutedColor, int borderColor, boolean lightStatusBar) {
            this.key = key;
            this.label = label;
            this.backgroundColor = backgroundColor;
            this.surfaceColor = surfaceColor;
            this.surfaceAltColor = surfaceAltColor;
            this.textColor = textColor;
            this.mutedColor = mutedColor;
            this.borderColor = borderColor;
            this.lightStatusBar = lightStatusBar;
        }

        static AppSkin fromKey(String value) {
            for (AppSkin skin : values()) {
                if (skin.key.equals(value)) {
                    return skin;
                }
            }
            return LIGHT;
        }
    }

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
    private TextView previewTitle;
    private ImageView previewArtwork;
    private ImageButton backButton;
    private ImageButton playButton;
    private SeekBar previewSeek;
    private Button exportButton;
    private Button selectVisualButton;
    private Button selectAudioButton;
    private ImageButton settingsButton;
    private ProgressBar progress;
    private LinearLayout rootContainer;
    private View statusBarSpacer;
    private View topBar;
    private View projectSummaryCard;
    private View previewCard;

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
    private AppSkin currentSkin = AppSkin.LIGHT;
    private MediaPlayer previewPlayer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        bindViews();
        currentSkin = readSavedSkin();
        applyTopInset();
        applySkin();
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
        previewTitle = findViewById(R.id.previewTitle);
        playButton = findViewById(R.id.playButton);
        previewSeek = findViewById(R.id.previewSeek);
        backButton = findViewById(R.id.backButton);
        exportButton = findViewById(R.id.exportButton);
        selectVisualButton = findViewById(R.id.selectVisualButton);
        selectAudioButton = findViewById(R.id.selectAudioButton);
        settingsButton = findViewById(R.id.settingsButton);
        progress = findViewById(R.id.progress);
        rootContainer = findViewById(R.id.rootContainer);
        statusBarSpacer = findViewById(R.id.statusBarSpacer);
        topBar = findViewById(R.id.topBar);
        projectSummaryCard = findViewById(R.id.projectSummaryCard);
        previewCard = findViewById(R.id.previewCard);
    }

    private void bindActions() {
        backButton.setOnClickListener(view -> finish());
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
        progress.setProgress(0);

        Uri selectedAudio = audioUri;
        Uri selectedVisual = visualUri;
        String selectedVisualMime = visualMimeType;
        ExportProfile selectedProfile = exportProfile;
        int selectedFrameRate = frameRate;
        long selectedAudioDurationMs = audioDurationMs;

        executor.submit(() -> {
            try {
                AvtohitProcessor.Result result = processor.render(
                        getApplicationContext(),
                        selectedAudio,
                        selectedVisual,
                        selectedVisualMime,
                        destinationUri,
                        selectedProfile,
                        selectedFrameRate,
                        selectedAudioDurationMs,
                        (currentMs, totalMs) -> mainHandler.post(() -> updateRenderProgress(currentMs, totalMs))
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
        RadioGroup skinGroup = dialogView.findViewById(R.id.skinGroup);
        TextView exportSummary = dialogView.findViewById(R.id.exportSummary);

        if (exportProfile == ExportProfile.P720) {
            resolutionGroup.check(R.id.resolution720);
        } else if (exportProfile == ExportProfile.P4K) {
            resolutionGroup.check(R.id.resolution4k);
        } else {
            resolutionGroup.check(R.id.resolution1080);
        }
        fpsGroup.check(frameRate == 60 ? R.id.fps60 : R.id.fps30);
        if (currentSkin == AppSkin.FOREST) {
            skinGroup.check(R.id.skinForest);
        } else if (currentSkin == AppSkin.NIGHT) {
            skinGroup.check(R.id.skinNight);
        } else {
            skinGroup.check(R.id.skinLight);
        }
        updateExportSummary(dialogView, exportSummary);

        RadioGroup.OnCheckedChangeListener listener = (group, checkedId) -> updateExportSummary(dialogView, exportSummary);
        resolutionGroup.setOnCheckedChangeListener(listener);
        fpsGroup.setOnCheckedChangeListener(listener);
        skinGroup.setOnCheckedChangeListener(listener);

        TextView dialogTitle = new TextView(this);
        dialogTitle.setPadding(dp(24), dp(20), dp(24), dp(8));
        dialogTitle.setText(startExportWhenSaved ? R.string.export_dialog_title : R.string.settings_dialog_title);
        dialogTitle.setTextSize(20f);
        dialogTitle.setTypeface(dialogTitle.getTypeface(), android.graphics.Typeface.BOLD);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setCustomTitle(dialogTitle)
                .setView(dialogView)
                .setNegativeButton(R.string.export_cancel, null)
                .setNeutralButton(R.string.export_apply, (dialogInterface, which) -> {
                    applyExportSelection(dialogView);
                    status.setText(getString(R.string.settings_status_saved, currentSkin.label, exportProfile.label, frameRate));
                    refreshUi();
                })
                .setPositiveButton(startExportWhenSaved ? R.string.export_start : R.string.export_apply, (dialogInterface, which) -> {
                    applyExportSelection(dialogView);
                    refreshUi();
                    if (startExportWhenSaved) {
                        openOutputPicker();
                    } else {
                        status.setText(getString(R.string.settings_status_saved, currentSkin.label, exportProfile.label, frameRate));
                    }
                })
                .create();
        dialog.setOnShowListener(unused -> styleSettingsDialog(dialog, dialogTitle, dialogView));
        dialog.show();
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
        RadioGroup skinGroup = dialogView.findViewById(R.id.skinGroup);
        int resolutionId = resolutionGroup.getCheckedRadioButtonId();
        int fpsId = fpsGroup.getCheckedRadioButtonId();
        int skinId = skinGroup.getCheckedRadioButtonId();

        if (resolutionId == R.id.resolution720) {
            exportProfile = ExportProfile.P720;
        } else if (resolutionId == R.id.resolution4k) {
            exportProfile = ExportProfile.P4K;
        } else {
            exportProfile = ExportProfile.P1080;
        }

        frameRate = fpsId == R.id.fps60 ? 60 : 30;

        if (skinId == R.id.skinForest) {
            currentSkin = AppSkin.FOREST;
        } else if (skinId == R.id.skinNight) {
            currentSkin = AppSkin.NIGHT;
        } else {
            currentSkin = AppSkin.LIGHT;
        }
        saveSkin(currentSkin);
        applySkin();
    }

    private void updateExportSummary(View dialogView, TextView exportSummary) {
        RadioGroup resolutionGroup = dialogView.findViewById(R.id.resolutionGroup);
        RadioGroup fpsGroup = dialogView.findViewById(R.id.fpsGroup);
        RadioGroup skinGroup = dialogView.findViewById(R.id.skinGroup);

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
        String skinLabel;
        int skinId = skinGroup.getCheckedRadioButtonId();
        if (skinId == R.id.skinForest) {
            skinLabel = AppSkin.FOREST.label;
        } else if (skinId == R.id.skinNight) {
            skinLabel = AppSkin.NIGHT.label;
        } else {
            skinLabel = AppSkin.LIGHT.label;
        }
        exportSummary.setText(getString(R.string.settings_summary, skinLabel, resolutionLabel, selectedFrameRate));
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

    private void updatePreviewTime(long currentMs, long totalMs) {
        previewTime.setText(getString(R.string.time_pair, formatDuration(currentMs), formatDuration(totalMs)));
    }

    private void updateRenderProgress(long currentMs, long totalMs) {
        if (totalMs <= 0L) {
            return;
        }
        int scaled = (int) Math.min(1000L, Math.max(0L, (currentMs * 1000L) / totalMs));
        progress.setProgress(scaled);
        status.setText("Merging " + Math.min(100, Math.max(0, scaled / 10)) + "%");
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
            android.view.ViewGroup.LayoutParams params = statusBarSpacer.getLayoutParams();
            params.height = topInset;
            statusBarSpacer.setLayoutParams(params);
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

    private AppSkin readSavedSkin() {
        String skinKey = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(PREF_SKIN, AppSkin.LIGHT.key);
        return AppSkin.fromKey(skinKey);
    }

    private void saveSkin(AppSkin skin) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putString(PREF_SKIN, skin.key)
                .apply();
    }

    private void applySkin() {
        View content = findViewById(android.R.id.content);
        content.setBackgroundColor(currentSkin.backgroundColor);
        rootContainer.setBackgroundColor(currentSkin.backgroundColor);
        topBar.setBackgroundColor(currentSkin.backgroundColor);

        styleCard(projectSummaryCard, currentSkin.surfaceColor);
        styleCard(previewCard, currentSkin.surfaceColor);

        projectTitle.setTextColor(currentSkin.textColor);
        projectSubtitle.setTextColor(currentSkin.mutedColor);
        projectMode.setTextColor(currentSkin.textColor);
        previewTitle.setTextColor(currentSkin.textColor);

        styleTopBarIconButton(backButton);
        styleTopBarIconButton(settingsButton);
        updateSystemBars();
    }

    private void styleCard(View view, int fillColor) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fillColor);
        drawable.setCornerRadius(dp(22));
        drawable.setStroke(dp(1), currentSkin.borderColor);
        view.setBackground(drawable);
    }

    private void styleSurface(View view, int fillColor, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fillColor);
        drawable.setCornerRadius(dp(radiusDp));
        drawable.setStroke(dp(1), currentSkin.borderColor);
        view.setBackground(drawable);
    }

    private void updateSystemBars() {
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(currentSkin.backgroundColor);
        View decorView = getWindow().getDecorView();
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            if (getWindow().getInsetsController() != null) {
                getWindow().getInsetsController().setSystemBarsAppearance(
                        0,
                        android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                );
            }
        } else if (android.os.Build.VERSION.SDK_INT >= 23) {
            int flags = decorView.getSystemUiVisibility();
            decorView.setSystemUiVisibility(flags & ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }
    }

    private void styleTopBarIconButton(ImageButton button) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(currentSkin.surfaceColor);
        drawable.setCornerRadius(dp(14));
        drawable.setStroke(dp(1), currentSkin.borderColor);
        button.setBackground(drawable);
        button.setImageTintList(ColorStateList.valueOf(currentSkin.textColor));
        button.setAlpha(button.isEnabled() ? 1f : 0.55f);
    }

    private void styleSettingsDialog(AlertDialog dialog, TextView dialogTitle, View dialogView) {
        boolean darkDialog = currentSkin == AppSkin.NIGHT;
        int surfaceColor = darkDialog ? currentSkin.surfaceColor : Color.WHITE;
        int summaryColor = darkDialog ? currentSkin.surfaceAltColor : 0xFFF4F6F4;
        int textColor = darkDialog ? currentSkin.textColor : 0xFF151817;
        int mutedColor = darkDialog ? currentSkin.mutedColor : 0xFF5D6662;
        int borderColor = darkDialog ? currentSkin.borderColor : 0xFFD5DDD8;

        if (dialog.getWindow() != null) {
            GradientDrawable windowDrawable = new GradientDrawable();
            windowDrawable.setColor(surfaceColor);
            windowDrawable.setCornerRadius(dp(24));
            dialog.getWindow().setBackgroundDrawable(windowDrawable);
        }

        dialogTitle.setBackgroundColor(surfaceColor);
        dialogTitle.setTextColor(textColor);

        View content = dialogView.findViewById(R.id.exportDialogContent);
        content.setBackgroundColor(surfaceColor);
        styleTextInputs(dialogView, textColor, mutedColor);

        View exportSummary = dialogView.findViewById(R.id.exportSummary);
        GradientDrawable summaryDrawable = new GradientDrawable();
        summaryDrawable.setColor(summaryColor);
        summaryDrawable.setCornerRadius(dp(16));
        summaryDrawable.setStroke(dp(1), borderColor);
        exportSummary.setBackground(summaryDrawable);

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(0xFF2E7D32);
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setTextColor(textColor);
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(0xFFC62828);
    }

    private void styleTextInputs(View root, int textColor, int mutedColor) {
        if (root instanceof TextView) {
            TextView textView = (TextView) root;
            textView.setTextColor(textColor);
            if (root instanceof android.widget.RadioButton) {
                ((android.widget.RadioButton) root).setButtonTintList(ColorStateList.valueOf(0xFF2E7D32));
            }
        }
        if (root instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                styleTextInputs(group.getChildAt(i), textColor, mutedColor);
            }
        }
    }
}

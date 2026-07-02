package com.avtohit.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.content.pm.PackageInfo;
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
import android.text.method.LinkMovementMethod;
import android.view.WindowInsets;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
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
import java.util.concurrent.RejectedExecutionException;

public final class MainActivity extends Activity {
    private static final int REQUEST_AUDIO = 1001;
    private static final int REQUEST_VISUAL = 1002;
    private static final int REQUEST_OUTPUT = 1003;
    private static final String PREFS_NAME = "avtohit_settings";
    private static final String PREF_SKIN = "app_skin";
    private static final String STATE_AUDIO_URI = "audio_uri";
    private static final String STATE_AUDIO_NAME = "audio_name";
    private static final String STATE_AUDIO_DURATION = "audio_duration";
    private static final String STATE_VISUAL_URI = "visual_uri";
    private static final String STATE_VISUAL_MIME = "visual_mime";
    private static final String STATE_VISUAL_NAME = "visual_name";
    private static final String STATE_VISUAL_IS_VIDEO = "visual_is_video";
    private static final String STATE_VISUAL_DURATION = "visual_duration";
    private static final String STATE_EXPORT_PROFILE = "export_profile";
    private static final String STATE_FRAME_RATE = "frame_rate";
    private static final int MAX_PREVIEW_BITMAP_SIZE = 1440;

    private enum AppSkin {
        LIGHT("light", "Light", 0xFFF7F8F5, 0xFFFFFFFF, 0xFFEEF3EF, 0xFF151817, 0xFFD5DDD8, true),
        FOREST("forest", "Forest", 0xFFEAF3EF, 0xFFF9FCFA, 0xFFE2ECE7, 0xFF17342B, 0xFFC8D9D0, true),
        NIGHT("night", "Night", 0xFF111615, 0xFF1B2421, 0xFF24302B, 0xFFF3F7F5, 0xFF31403A, false);

        final String key;
        final String label;
        final int backgroundColor;
        final int surfaceColor;
        final int surfaceAltColor;
        final int textColor;
        final int borderColor;
        final boolean lightStatusBar;

        AppSkin(String key, String label, int backgroundColor, int surfaceColor, int surfaceAltColor, int textColor, int borderColor, boolean lightStatusBar) {
            this.key = key;
            this.label = label;
            this.backgroundColor = backgroundColor;
            this.surfaceColor = surfaceColor;
            this.surfaceAltColor = surfaceAltColor;
            this.textColor = textColor;
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
    private ImageButton playButton;
    private ImageButton overflowMenuButton;
    private SeekBar previewSeek;
    private Button exportButton;
    private Button selectVisualButton;
    private Button selectAudioButton;
    private ProgressBar progress;
    private LinearLayout rootContainer;
    private View statusBarSpacer;
    private View projectSummaryCard;
    private View previewCard;

    private Uri audioUri;
    private Uri visualUri;
    private String visualMimeType;
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
    private volatile boolean activityActive = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        bindViews();
        currentSkin = readSavedSkin();
        restoreState(savedInstanceState);
        applyTopInset();
        applySkin();
        bindActions();
        refreshUi();
    }

    @Override
    protected void onStop() {
        super.onStop();
        pausePreview();
    }

    @Override
    protected void onDestroy() {
        activityActive = false;
        mainHandler.removeCallbacksAndMessages(null);
        releasePreviewPlayer();
        executor.shutdown();
        super.onDestroy();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString(STATE_AUDIO_URI, audioUri != null ? audioUri.toString() : null);
        outState.putString(STATE_AUDIO_NAME, audioDisplayName);
        outState.putLong(STATE_AUDIO_DURATION, audioDurationMs);
        outState.putString(STATE_VISUAL_URI, visualUri != null ? visualUri.toString() : null);
        outState.putString(STATE_VISUAL_MIME, visualMimeType);
        outState.putString(STATE_VISUAL_NAME, visualDisplayName);
        outState.putBoolean(STATE_VISUAL_IS_VIDEO, visualIsVideo);
        outState.putLong(STATE_VISUAL_DURATION, visualDurationMs);
        outState.putString(STATE_EXPORT_PROFILE, exportProfile.label);
        outState.putInt(STATE_FRAME_RATE, frameRate);
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
            handleAudioSelection(uri);
        } else if (requestCode == REQUEST_VISUAL) {
            takeReadPermission(data, uri);
            handleVisualSelection(uri);
        } else if (requestCode == REQUEST_OUTPUT) {
            takeWritePermission(data, uri);
            renderTo(uri);
            return;
        }

        refreshUi();
    }

    private void bindViews() {
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
        overflowMenuButton = findViewById(R.id.overflowMenuButton);
        previewSeek = findViewById(R.id.previewSeek);
        exportButton = findViewById(R.id.exportButton);
        selectVisualButton = findViewById(R.id.selectVisualButton);
        selectAudioButton = findViewById(R.id.selectAudioButton);
        progress = findViewById(R.id.progress);
        rootContainer = findViewById(R.id.rootContainer);
        statusBarSpacer = findViewById(R.id.statusBarSpacer);
        projectSummaryCard = findViewById(R.id.projectSummaryCard);
        previewCard = findViewById(R.id.previewCard);
    }

    private void bindActions() {
        selectVisualButton.setOnClickListener(view -> openVisualPicker());
        selectAudioButton.setOnClickListener(view -> openAudioPicker());
        exportButton.setOnClickListener(view -> showExportDialog(true));
        playButton.setOnClickListener(view -> togglePreviewPlayback());
        overflowMenuButton.setOnClickListener(view -> showOverflowMenu());

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

    private void restoreState(Bundle savedInstanceState) {
        if (savedInstanceState == null) {
            return;
        }

        // Persisted document permissions let us rebuild the working state after rotation or process recreation.
        audioUri = parseUri(savedInstanceState.getString(STATE_AUDIO_URI));
        audioDisplayName = savedInstanceState.getString(STATE_AUDIO_NAME);
        audioDurationMs = savedInstanceState.getLong(STATE_AUDIO_DURATION, 0L);
        visualUri = parseUri(savedInstanceState.getString(STATE_VISUAL_URI));
        visualMimeType = savedInstanceState.getString(STATE_VISUAL_MIME);
        visualDisplayName = savedInstanceState.getString(STATE_VISUAL_NAME);
        visualIsVideo = savedInstanceState.getBoolean(STATE_VISUAL_IS_VIDEO, false);
        visualDurationMs = savedInstanceState.getLong(STATE_VISUAL_DURATION, 0L);
        exportProfile = exportProfileFromLabel(savedInstanceState.getString(STATE_EXPORT_PROFILE));
        frameRate = savedInstanceState.getInt(STATE_FRAME_RATE, frameRate);
    }

    private void handleAudioSelection(Uri uri) {
        String mimeType = getContentResolver().getType(uri);
        String displayName = AvtohitProcessor.displayName(this, uri);
        if (!isSupportedMp3(mimeType, displayName, uri)) {
            clearAudioSelection();
            status.setText(R.string.audio_must_be_mp3);
            return;
        }

        long durationMs = readDuration(uri);
        if (durationMs <= 0L) {
            clearAudioSelection();
            status.setText(R.string.audio_duration_unavailable);
            return;
        }

        audioUri = uri;
        audioDisplayName = displayName;
        audioDurationMs = durationMs;
        releasePreviewPlayer();
        status.setText(R.string.ready);
    }

    private void handleVisualSelection(Uri uri) {
        String mimeType = getContentResolver().getType(uri);
        if (!isSupportedVisual(mimeType, uri)) {
            clearVisualSelection();
            status.setText(R.string.visual_type_unsupported);
            return;
        }

        visualUri = uri;
        visualMimeType = mimeType;
        visualDisplayName = AvtohitProcessor.displayName(this, uri);
        visualIsVideo = isVideoVisual(uri, visualMimeType);
        visualDurationMs = visualIsVideo ? readDuration(uri) : 0L;
        status.setText(R.string.ready);
    }

    private void clearAudioSelection() {
        audioUri = null;
        audioDisplayName = null;
        audioDurationMs = 0L;
        releasePreviewPlayer();
    }

    private void clearVisualSelection() {
        visualUri = null;
        visualMimeType = null;
        visualDisplayName = null;
        visualIsVideo = false;
        visualDurationMs = 0L;
    }

    private void launchPicker(Intent intent, int requestCode, int errorResId) {
        try {
            startActivityForResult(intent, requestCode);
        } catch (ActivityNotFoundException | SecurityException error) {
            status.setText(errorResId);
        }
    }

    private void postToUiIfAlive(Runnable action) {
        if (!activityActive) {
            return;
        }
        mainHandler.post(() -> {
            if (!activityActive || isFinishing() || (android.os.Build.VERSION.SDK_INT >= 17 && isDestroyed())) {
                return;
            }
            action.run();
        });
    }

    private void openAudioPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("audio/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"audio/mpeg", "audio/mp3", "audio/x-mpeg"});
        launchPicker(intent, REQUEST_AUDIO, R.string.audio_picker_unavailable);
    }

    private void openVisualPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "video/*"});
        launchPicker(intent, REQUEST_VISUAL, R.string.visual_picker_unavailable);
    }

    private void openOutputPicker() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("video/mp4");
        intent.putExtra(Intent.EXTRA_TITLE, defaultOutputName());
        launchPicker(intent, REQUEST_OUTPUT, R.string.output_picker_unavailable);
    }

    private void renderTo(Uri destinationUri) {
        if (audioUri == null || visualUri == null) {
            status.setText(R.string.select_media_before_export);
            return;
        }

        setRendering(true);
        releasePreviewPlayer();
        status.setText(R.string.creating);
        progress.setProgress(0);

        // Render settings are snapshotted here so a dialog change cannot mutate an in-flight job.
        Uri selectedAudio = audioUri;
        Uri selectedVisual = visualUri;
        String selectedVisualMime = visualMimeType;
        ExportProfile selectedProfile = exportProfile;
        int selectedFrameRate = frameRate;
        long selectedAudioDurationMs = audioDurationMs;

        try {
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
                            (currentMs, totalMs) -> postToUiIfAlive(() -> updateRenderProgress(currentMs, totalMs))
                    );
                    postToUiIfAlive(() -> onRenderSuccess(result));
                } catch (IOException | AvtohitException | RuntimeException error) {
                    postToUiIfAlive(() -> onRenderFailure(error));
                }
            });
        } catch (RejectedExecutionException error) {
            setRendering(false);
            status.setText(R.string.render_unavailable);
        }
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
        String visualSummary = visualDisplayName != null ? visualDisplayName : getString(R.string.visual_not_selected);
        String audioSummary = audioDisplayName != null ? audioDisplayName : getString(R.string.mp3_not_selected);
        String exportSummary = exportProfile.label + " / " + frameRate + "fps";

        visualChip.setText(ellipsize(visualSummary, 20));
        audioChip.setText(ellipsize(audioSummary, 20));
        exportChip.setText(exportSummary);

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
        boolean canOpenMergeMenu = !rendering;
        selectVisualButton.setEnabled(!rendering);
        selectAudioButton.setEnabled(!rendering);
        selectVisualButton.setSelected(visualUri != null);
        selectAudioButton.setSelected(audioUri != null);
        exportButton.setEnabled(canOpenMergeMenu);
        overflowMenuButton.setEnabled(true);
        playButton.setEnabled(!rendering && audioUri != null);
        previewSeek.setEnabled(!rendering && audioUri != null);
        progress.setVisibility(rendering ? View.VISIBLE : View.GONE);
        playButton.setAlpha(playButton.isEnabled() ? 1f : 0.45f);
    }

    private void showOverflowMenu() {
        PopupMenu popupMenu = new PopupMenu(this, overflowMenuButton);
        popupMenu.getMenuInflater().inflate(R.menu.main_overflow_menu, popupMenu.getMenu());
        popupMenu.setOnMenuItemClickListener(item -> {
            int itemId = item.getItemId();
            if (itemId == R.id.menu_help) {
                showHelpDialog();
                return true;
            }
            if (itemId == R.id.menu_about_us) {
                showAboutDialog();
                return true;
            }
            return false;
        });
        popupMenu.show();
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

        TextView dialogTitle = buildDialogTitle(startExportWhenSaved ? R.string.export_dialog_title : R.string.settings_dialog_title);

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
                        if (audioUri == null || visualUri == null) {
                            status.setText(R.string.select_media_before_export);
                        } else {
                            openOutputPicker();
                        }
                    } else {
                        status.setText(getString(R.string.settings_status_saved, currentSkin.label, exportProfile.label, frameRate));
                    }
                })
                .create();
        dialog.setOnShowListener(unused -> styleSettingsDialog(dialog, dialogTitle, dialogView));
        dialog.show();
    }

    private void showHelpDialog() {
        showInfoDialog(R.string.help_title, getString(R.string.help_body), false, R.drawable.help_ui_guide);
    }

    private void showAboutDialog() {
        CharSequence body = getString(
                R.string.about_us_body,
                aboutVersionSummary(),
                getString(R.string.about_email_plain),
                getString(R.string.about_github_plain)
        );
        showInfoDialog(R.string.about_us_title, body, true, 0);
    }

    private void showInfoDialog(int titleResId, CharSequence body, boolean enableLinks, int imageResId) {
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_info, null);
        android.widget.ImageView infoImage = dialogView.findViewById(R.id.infoImage);
        TextView infoBody = dialogView.findViewById(R.id.infoBody);
        if (imageResId != 0) {
            infoImage.setVisibility(View.VISIBLE);
            infoImage.setImageResource(imageResId);
        }
        infoBody.setText(body);
        if (enableLinks) {
            infoBody.setMovementMethod(LinkMovementMethod.getInstance());
        }

        TextView dialogTitle = buildDialogTitle(titleResId);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setCustomTitle(dialogTitle)
                .setView(dialogView)
                .setPositiveButton(R.string.info_dialog_close, null)
                .create();
        dialog.setOnShowListener(unused -> styleInfoDialog(dialog, dialogTitle, dialogView));
        dialog.show();
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
        } catch (IOException | IllegalStateException | SecurityException error) {
            releasePreviewPlayer();
            status.setText(getString(R.string.failed_detail, safeMessage(error)));
        }
    }

    private void pausePreview() {
        try {
            if (previewPlayer != null && previewPlayer.isPlaying()) {
                previewPlayer.pause();
            }
        } catch (RuntimeException ignored) {
            // Ignore best-effort preview cleanup failures.
        }
        previewPlaying = false;
        playButton.setImageResource(android.R.drawable.ic_media_play);
        playButton.setContentDescription(getString(R.string.play_button));
        mainHandler.removeCallbacks(previewTicker);
    }

    private void releasePreviewPlayer() {
        pausePreview();
        if (previewPlayer != null) {
            try {
                previewPlayer.release();
            } catch (RuntimeException ignored) {
                // Ignore preview release failures during teardown.
            }
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
                int targetSize = previewTargetSize();
                if (android.os.Build.VERSION.SDK_INT >= 27) {
                    return retriever.getScaledFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, targetSize, targetSize);
                }
                return retriever.getFrameAtTime(0L);
            } catch (RuntimeException | OutOfMemoryError ignored) {
                return null;
            } finally {
                try {
                    retriever.release();
                } catch (IOException ignored) {
                    // Ignore cleanup failures for preview extraction.
                }
            }
        }

        try {
            return decodeSampledImage(visualUri, previewTargetSize(), previewTargetSize());
        } catch (IOException | OutOfMemoryError ignored) {
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

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private int previewTargetSize() {
        int displayWidth = getResources().getDisplayMetrics().widthPixels;
        return Math.min(MAX_PREVIEW_BITMAP_SIZE, Math.max(displayWidth, dp(320)));
    }

    private Bitmap decodeSampledImage(Uri uri, int requestedWidth, int requestedHeight) throws IOException {
        BitmapFactory.Options boundsOptions = new BitmapFactory.Options();
        boundsOptions.inJustDecodeBounds = true;
        try (InputStream boundsStream = getContentResolver().openInputStream(uri)) {
            if (boundsStream == null) {
                throw new IOException("Could not open selected image preview.");
            }
            BitmapFactory.decodeStream(boundsStream, null, boundsOptions);
        }

        BitmapFactory.Options decodeOptions = new BitmapFactory.Options();
        decodeOptions.inSampleSize = calculateInSampleSize(boundsOptions, requestedWidth, requestedHeight);
        decodeOptions.inPreferredConfig = Bitmap.Config.ARGB_8888;
        try (InputStream decodeStream = getContentResolver().openInputStream(uri)) {
            if (decodeStream == null) {
                throw new IOException("Could not open selected image preview.");
            }
            return BitmapFactory.decodeStream(decodeStream, null, decodeOptions);
        }
    }

    private static int calculateInSampleSize(BitmapFactory.Options options, int requestedWidth, int requestedHeight) {
        int sampleSize = 1;
        int width = Math.max(1, options.outWidth);
        int height = Math.max(1, options.outHeight);
        while ((width / sampleSize) > requestedWidth * 2 || (height / sampleSize) > requestedHeight * 2) {
            sampleSize *= 2;
        }
        return Math.max(1, sampleSize);
    }

    private static Uri parseUri(String rawUri) {
        if (rawUri == null || rawUri.trim().isEmpty()) {
            return null;
        }
        try {
            return Uri.parse(rawUri);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static ExportProfile exportProfileFromLabel(String label) {
        if (ExportProfile.P720.label.equals(label)) {
            return ExportProfile.P720;
        }
        if (ExportProfile.P4K.label.equals(label)) {
            return ExportProfile.P4K;
        }
        return ExportProfile.P1080;
    }

    private boolean isSupportedMp3(String mimeType, String displayName, Uri uri) {
        String source = firstNonBlank(mimeType, displayName, uri.toString());
        if (source == null) {
            return false;
        }
        String normalized = source.toLowerCase(Locale.US);
        return normalized.contains("audio/mpeg")
                || normalized.contains("audio/mp3")
                || normalized.contains("audio/x-mpeg")
                || normalized.endsWith(".mp3");
    }

    private boolean isSupportedVisual(String mimeType, Uri uri) {
        String source = firstNonBlank(mimeType, uri.toString());
        if (source == null) {
            return false;
        }
        String normalized = source.toLowerCase(Locale.US);
        return normalized.startsWith("image/")
                || normalized.startsWith("video/")
                || normalized.endsWith(".jpg")
                || normalized.endsWith(".jpeg")
                || normalized.endsWith(".png")
                || normalized.endsWith(".webp")
                || normalized.endsWith(".heic")
                || normalized.endsWith(".heif")
                || normalized.endsWith(".mp4")
                || normalized.endsWith(".mov")
                || normalized.endsWith(".m4v")
                || normalized.endsWith(".webm")
                || normalized.endsWith(".3gp");
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value;
            }
        }
        return null;
    }

    private String defaultOutputName() {
        if (audioDisplayName != null && !audioDisplayName.trim().isEmpty()) {
            return stripExtension(audioDisplayName) + ".mp4";
        }
        String timestamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
        return "AVTOHIT-" + timestamp + ".mp4";
    }

    private String aboutVersionSummary() {
        try {
            PackageInfo packageInfo;
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                packageInfo = getPackageManager().getPackageInfo(
                        getPackageName(),
                        android.content.pm.PackageManager.PackageInfoFlags.of(0)
                );
            } else {
                packageInfo = getPackageManager().getPackageInfo(getPackageName(), 0);
            }

            String versionName = firstNonBlank(packageInfo.versionName, "0.0.0");
            long versionCode = android.os.Build.VERSION.SDK_INT >= 28
                    ? packageInfo.getLongVersionCode()
                    : packageInfo.versionCode;
            String buildLabel = isDebugBuild() ? "debug" : "release";
            return getString(R.string.about_version_value, versionName, versionCode, buildLabel);
        } catch (Exception ignored) {
            return getString(R.string.about_version_value, "unknown", 0, "unknown");
        }
    }

    private boolean isDebugBuild() {
        return (getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0;
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

        styleCard(projectSummaryCard, currentSkin.surfaceColor);
        styleCard(previewCard, currentSkin.surfaceColor);
        styleIconButton(overflowMenuButton);

        projectMode.setTextColor(currentSkin.textColor);
        previewTitle.setTextColor(currentSkin.textColor);

        updateSystemBars();
    }

    private void styleCard(View view, int fillColor) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fillColor);
        drawable.setCornerRadius(dp(22));
        drawable.setStroke(dp(1), currentSkin.borderColor);
        view.setBackground(drawable);
    }

    private void styleIconButton(ImageButton button) {
        if (button == null) {
            return;
        }
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(currentSkin.surfaceColor);
        drawable.setCornerRadius(dp(14));
        drawable.setStroke(dp(1), currentSkin.borderColor);
        button.setBackground(drawable);
        button.setImageTintList(ColorStateList.valueOf(currentSkin.textColor));
        button.setAlpha(button.isEnabled() ? 1f : 0.55f);
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

    private void styleSettingsDialog(AlertDialog dialog, TextView dialogTitle, View dialogView) {
        int[] palette = dialogPalette();
        int surfaceColor = palette[0];
        int summaryColor = palette[1];
        int textColor = palette[2];
        int borderColor = palette[3];

        styleDialogShell(dialog, dialogTitle, dialogView.findViewById(R.id.exportDialogContent), textColor, surfaceColor);
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

    private void styleInfoDialog(AlertDialog dialog, TextView dialogTitle, View dialogView) {
        int[] palette = dialogPalette();
        int surfaceColor = palette[0];
        int textColor = palette[2];
        styleDialogShell(dialog, dialogTitle, dialogView.findViewById(R.id.infoDialogContent), textColor, surfaceColor);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(0xFF2E7D32);
    }

    private int[] dialogPalette() {
        boolean darkDialog = currentSkin == AppSkin.NIGHT;
        int surfaceColor = darkDialog ? currentSkin.surfaceColor : Color.WHITE;
        int summaryColor = darkDialog ? currentSkin.surfaceAltColor : 0xFFF4F6F4;
        int textColor = darkDialog ? currentSkin.textColor : 0xFF151817;
        int borderColor = darkDialog ? currentSkin.borderColor : 0xFFD5DDD8;
        return new int[]{surfaceColor, summaryColor, textColor, borderColor};
    }

    private void styleDialogShell(AlertDialog dialog, TextView dialogTitle, View content, int textColor, int surfaceColor) {
        if (dialog.getWindow() != null) {
            GradientDrawable windowDrawable = new GradientDrawable();
            windowDrawable.setColor(surfaceColor);
            windowDrawable.setCornerRadius(dp(24));
            dialog.getWindow().setBackgroundDrawable(windowDrawable);
        }

        dialogTitle.setBackgroundColor(surfaceColor);
        dialogTitle.setTextColor(textColor);
        content.setBackgroundColor(surfaceColor);
        styleTextInputs(content, textColor);
    }

    private TextView buildDialogTitle(int titleResId) {
        TextView dialogTitle = new TextView(this);
        dialogTitle.setPadding(dp(24), dp(20), dp(24), dp(8));
        dialogTitle.setText(titleResId);
        dialogTitle.setTextSize(20f);
        dialogTitle.setTypeface(dialogTitle.getTypeface(), android.graphics.Typeface.BOLD);
        return dialogTitle;
    }

    private void styleTextInputs(View root, int textColor) {
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
                styleTextInputs(group.getChildAt(i), textColor);
            }
        }
    }
}

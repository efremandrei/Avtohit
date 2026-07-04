package com.avtohit.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.content.pm.PackageInfo;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.text.method.LinkMovementMethod;
import android.view.WindowInsets;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
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
    private static final String STATE_VISUAL_IMAGE_URIS = "visual_image_uris";
    private static final String STATE_VISUAL_IMAGE_NAMES = "visual_image_names";
    private static final String STATE_VISUAL_VIDEO_URIS = "visual_video_uris";
    private static final String STATE_VISUAL_VIDEO_NAMES = "visual_video_names";
    private static final String STATE_VISUAL_VIDEO_EPOCHS = "visual_video_epochs";
    private static final String STATE_VISUAL_VIDEO_DURATIONS = "visual_video_durations";
    private static final String STATE_EXPORT_PROFILE = "export_profile";
    private static final String STATE_FRAME_RATE = "frame_rate";
    private static final String STATE_SLIDE_SECONDS = "slide_seconds";
    private static final int MAX_PREVIEW_BITMAP_SIZE = 1440;
    private static final int POSITIVE_READY_LIGHT = 0xFF12664F;
    private static final int POSITIVE_READY_DARK = 0xFF74D7B5;
    private static final int NEGATIVE_NEEDED_LIGHT = 0xFFA63C36;
    private static final int NEGATIVE_NEEDED_DARK = 0xFFFF9A90;

    private enum AppSkin {
        LIGHT("light", "Light", 0xFFF7F8F5, 0xFFFFFFFF, 0xFFEEF3EF, 0xFF151817, 0xFF5D6662, 0xFFD5DDD8, true),
        DARK("dark", "Dark", 0xFF111615, 0xFF1B2421, 0xFF24302B, 0xFFF3F7F5, 0xFFB8C5BE, 0xFF31403A, false);

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
            if ("night".equals(value)) {
                return DARK;
            }
            if ("forest".equals(value)) {
                return LIGHT;
            }
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
    private AvtohitDebugLogger debugLogger;

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

    private static final class ImageSelection {
        final Uri uri;
        final String displayName;

        ImageSelection(Uri uri, String displayName) {
            this.uri = uri;
            this.displayName = displayName;
        }
    }

    private static final class VideoSelection {
        final Uri uri;
        final String displayName;
        final long modifiedEpochMs;
        final long durationMs;
        final int originalIndex;

        VideoSelection(Uri uri, String displayName, long modifiedEpochMs, long durationMs, int originalIndex) {
            this.uri = uri;
            this.displayName = displayName;
            this.modifiedEpochMs = modifiedEpochMs;
            this.durationMs = durationMs;
            this.originalIndex = originalIndex;
        }
    }

    private static final Comparator<ImageSelection> IMAGE_SELECTION_COMPARATOR = (left, right) -> {
        Long leftNumber = numericBaseName(left.displayName);
        Long rightNumber = numericBaseName(right.displayName);
        if (leftNumber != null && rightNumber != null) {
            return leftNumber.compareTo(rightNumber);
        }
        if (leftNumber != null) {
            return -1;
        }
        if (rightNumber != null) {
            return 1;
        }
        String leftName = left.displayName != null ? left.displayName : "";
        String rightName = right.displayName != null ? right.displayName : "";
        return leftName.compareToIgnoreCase(rightName);
    };

    private static final Comparator<VideoSelection> VIDEO_SELECTION_COMPARATOR = (left, right) -> {
        Long leftNumber = numericBaseName(left.displayName);
        Long rightNumber = numericBaseName(right.displayName);
        if (leftNumber != null && rightNumber != null) {
            int numberCompare = leftNumber.compareTo(rightNumber);
            if (numberCompare != 0) {
                return numberCompare;
            }
        } else if (leftNumber != null) {
            return -1;
        } else if (rightNumber != null) {
            return 1;
        } else {
            String leftName = left.displayName != null ? left.displayName : "";
            String rightName = right.displayName != null ? right.displayName : "";
            int nameCompare = leftName.compareToIgnoreCase(rightName);
            if (nameCompare != 0) {
                return nameCompare;
            }
        }

        int epochCompare = Long.compare(left.modifiedEpochMs, right.modifiedEpochMs);
        if (epochCompare != 0) {
            return epochCompare;
        }
        return Integer.compare(left.originalIndex, right.originalIndex);
    };

    private TextView visualChip;
    private TextView audioChip;
    private TextView exportChip;
    private TextView visualDurationLine;
    private TextView audioDurationLine;
    private TextView exportOutputLine;
    private TextView visualReadiness;
    private TextView audioReadiness;
    private TextView exportReadiness;
    private TextView previewEmptyState;
    private TextView previewModeLabel;
    private TextView previewTime;
    private TextView status;
    private TextView previewTitle;
    private ImageView headerBanner;
    private ImageView previewArtwork;
    private ImageButton playButton;
    private SeekBar previewSeek;
    private View exportButton;
    private Button selectVisualButton;
    private Button selectAudioButton;
    private Button helpButton;
    private Button skinButton;
    private Button aboutButton;
    private ProgressBar progress;
    private LinearLayout rootContainer;
    private LinearLayout bottomActionsBar;
    private View statusBarSpacer;
    private View projectSummaryCard;
    private View previewCard;
    private View summaryDividerOne;
    private View summaryDividerTwo;

    private Uri audioUri;
    private Uri visualUri;
    private String visualMimeType;
    private String audioDisplayName;
    private String visualDisplayName;
    private final ArrayList<Uri> visualImageUris = new ArrayList<>();
    private final ArrayList<String> visualImageNames = new ArrayList<>();
    private final ArrayList<Uri> visualVideoUris = new ArrayList<>();
    private final ArrayList<String> visualVideoNames = new ArrayList<>();
    private final ArrayList<Long> visualVideoEpochs = new ArrayList<>();
    private final ArrayList<Long> visualVideoDurations = new ArrayList<>();
    private boolean visualIsVideo;
    private boolean rendering;
    private boolean previewPlaying;
    private long audioDurationMs;
    private long visualDurationMs;
    private ExportProfile exportProfile = ExportProfile.P1080;
    private int frameRate = 30;
    private int slideSeconds;
    private AppSkin currentSkin = AppSkin.LIGHT;
    private MediaPlayer previewPlayer;
    private volatile boolean activityActive = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        debugLogger = new AvtohitDebugLogger(this);
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
        outState.putStringArrayList(STATE_VISUAL_IMAGE_URIS, visualImageUriStrings());
        outState.putStringArrayList(STATE_VISUAL_IMAGE_NAMES, new ArrayList<>(visualImageNames));
        outState.putStringArrayList(STATE_VISUAL_VIDEO_URIS, visualVideoUriStrings());
        outState.putStringArrayList(STATE_VISUAL_VIDEO_NAMES, new ArrayList<>(visualVideoNames));
        outState.putLongArray(STATE_VISUAL_VIDEO_EPOCHS, longArrayFromList(visualVideoEpochs));
        outState.putLongArray(STATE_VISUAL_VIDEO_DURATIONS, longArrayFromList(visualVideoDurations));
        outState.putString(STATE_EXPORT_PROFILE, exportProfile.label);
        outState.putInt(STATE_FRAME_RATE, frameRate);
        outState.putInt(STATE_SLIDE_SECONDS, slideSeconds);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) {
            return;
        }

        Uri uri = data.getData();
        if (requestCode == REQUEST_AUDIO) {
            if (uri == null) {
                return;
            }
            takeReadPermission(data, uri);
            handleAudioSelection(uri);
        } else if (requestCode == REQUEST_VISUAL) {
            List<Uri> selectedVisuals = visualUrisFromResult(data);
            for (Uri selectedUri : selectedVisuals) {
                takeReadPermission(data, selectedUri);
            }
            handleVisualSelection(selectedVisuals);
        } else if (requestCode == REQUEST_OUTPUT) {
            if (uri == null) {
                return;
            }
            takeWritePermission(data, uri);
            renderTo(uri);
            return;
        }

        refreshUi();
    }

    private void bindViews() {
        visualChip = findViewById(R.id.visualChip);
        audioChip = findViewById(R.id.audioChip);
        exportChip = findViewById(R.id.exportChip);
        visualDurationLine = findViewById(R.id.visualDurationLine);
        audioDurationLine = findViewById(R.id.audioDurationLine);
        exportOutputLine = findViewById(R.id.exportOutputLine);
        visualReadiness = findViewById(R.id.visualReadiness);
        audioReadiness = findViewById(R.id.audioReadiness);
        exportReadiness = findViewById(R.id.exportReadiness);
        previewArtwork = findViewById(R.id.previewArtwork);
        previewEmptyState = findViewById(R.id.previewEmptyState);
        previewModeLabel = findViewById(R.id.previewModeLabel);
        previewTime = findViewById(R.id.previewTime);
        status = findViewById(R.id.status);
        previewTitle = findViewById(R.id.previewTitle);
        headerBanner = findViewById(R.id.headerBanner);
        playButton = findViewById(R.id.playButton);
        previewSeek = findViewById(R.id.previewSeek);
        exportButton = findViewById(R.id.exportButton);
        selectVisualButton = findViewById(R.id.selectVisualButton);
        selectAudioButton = findViewById(R.id.selectAudioButton);
        helpButton = findViewById(R.id.helpButton);
        skinButton = findViewById(R.id.skinButton);
        aboutButton = findViewById(R.id.aboutButton);
        progress = findViewById(R.id.progress);
        rootContainer = findViewById(R.id.rootContainer);
        bottomActionsBar = findViewById(R.id.bottomActionsBar);
        statusBarSpacer = findViewById(R.id.statusBarSpacer);
        projectSummaryCard = findViewById(R.id.projectSummaryCard);
        previewCard = findViewById(R.id.previewCard);
        summaryDividerOne = findViewById(R.id.summaryDividerOne);
        summaryDividerTwo = findViewById(R.id.summaryDividerTwo);
    }

    private void bindActions() {
        selectVisualButton.setOnClickListener(view -> openVisualPicker());
        selectAudioButton.setOnClickListener(view -> openAudioPicker());
        exportButton.setOnClickListener(view -> showExportDialog(true));
        playButton.setOnClickListener(view -> togglePreviewPlayback());
        helpButton.setOnClickListener(view -> showHelpDialog());
        skinButton.setOnClickListener(view -> showSkinDialog());
        aboutButton.setOnClickListener(view -> showAboutDialog());

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
        restoreVisualImageState(savedInstanceState);
        restoreVisualVideoState(savedInstanceState);
        exportProfile = exportProfileFromLabel(savedInstanceState.getString(STATE_EXPORT_PROFILE));
        frameRate = savedInstanceState.getInt(STATE_FRAME_RATE, frameRate);
        slideSeconds = clampSlideSeconds(savedInstanceState.getInt(STATE_SLIDE_SECONDS, 0));
    }

    private void restoreVisualImageState(Bundle savedInstanceState) {
        visualImageUris.clear();
        visualImageNames.clear();
        ArrayList<String> savedUriStrings = savedInstanceState.getStringArrayList(STATE_VISUAL_IMAGE_URIS);
        ArrayList<String> savedNames = savedInstanceState.getStringArrayList(STATE_VISUAL_IMAGE_NAMES);
        if (savedUriStrings != null) {
            for (String rawUri : savedUriStrings) {
                Uri parsedUri = parseUri(rawUri);
                if (parsedUri != null) {
                    visualImageUris.add(parsedUri);
                }
            }
        }
        if (savedNames != null) {
            visualImageNames.addAll(savedNames);
        }
        while (visualImageNames.size() < visualImageUris.size()) {
            visualImageNames.add(getString(R.string.visual_track_default));
        }
        if (visualImageUris.isEmpty() && visualUri != null && !visualIsVideo) {
            visualImageUris.add(visualUri);
            visualImageNames.add(visualDisplayName != null ? visualDisplayName : getString(R.string.visual_track_default));
        }
    }

    private void restoreVisualVideoState(Bundle savedInstanceState) {
        visualVideoUris.clear();
        visualVideoNames.clear();
        visualVideoEpochs.clear();
        visualVideoDurations.clear();

        ArrayList<String> savedUriStrings = savedInstanceState.getStringArrayList(STATE_VISUAL_VIDEO_URIS);
        ArrayList<String> savedNames = savedInstanceState.getStringArrayList(STATE_VISUAL_VIDEO_NAMES);
        long[] savedEpochs = savedInstanceState.getLongArray(STATE_VISUAL_VIDEO_EPOCHS);
        long[] savedDurations = savedInstanceState.getLongArray(STATE_VISUAL_VIDEO_DURATIONS);
        if (savedUriStrings != null) {
            for (String rawUri : savedUriStrings) {
                Uri parsedUri = parseUri(rawUri);
                if (parsedUri != null) {
                    visualVideoUris.add(parsedUri);
                }
            }
        }
        if (savedNames != null) {
            visualVideoNames.addAll(savedNames);
        }
        while (visualVideoNames.size() < visualVideoUris.size()) {
            visualVideoNames.add(getString(R.string.visual_track_default));
        }
        for (int i = 0; i < visualVideoUris.size(); i++) {
            visualVideoEpochs.add(savedEpochs != null && i < savedEpochs.length ? savedEpochs[i] : 0L);
            visualVideoDurations.add(savedDurations != null && i < savedDurations.length ? savedDurations[i] : 0L);
        }
        if (visualVideoUris.isEmpty() && visualUri != null && visualIsVideo) {
            visualVideoUris.add(visualUri);
            visualVideoNames.add(visualDisplayName != null ? visualDisplayName : getString(R.string.visual_track_default));
            visualVideoEpochs.add(readModifiedEpochMs(visualUri));
            visualVideoDurations.add(visualDurationMs);
        }
    }

    private ArrayList<String> visualImageUriStrings() {
        ArrayList<String> values = new ArrayList<>();
        for (Uri uri : visualImageUris) {
            if (uri != null) {
                values.add(uri.toString());
            }
        }
        return values;
    }

    private ArrayList<String> visualVideoUriStrings() {
        ArrayList<String> values = new ArrayList<>();
        for (Uri uri : visualVideoUris) {
            if (uri != null) {
                values.add(uri.toString());
            }
        }
        return values;
    }

    private List<Uri> visualUrisFromResult(Intent data) {
        ArrayList<Uri> uris = new ArrayList<>();
        ClipData clipData = data.getClipData();
        if (clipData != null) {
            for (int i = 0; i < clipData.getItemCount(); i++) {
                Uri uri = clipData.getItemAt(i).getUri();
                if (uri != null) {
                    uris.add(uri);
                }
            }
        }
        Uri directUri = data.getData();
        if (uris.isEmpty() && directUri != null) {
            uris.add(directUri);
        }
        return uris;
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
        if (isVideoSequenceSelected()) {
            clearVisualSelection();
            status.setText(R.string.video_sequence_cleared_for_audio);
        } else {
            status.setText(R.string.ready);
        }
        releasePreviewPlayer();
    }

    private void handleVisualSelection(List<Uri> selectedUris) {
        if (selectedUris == null || selectedUris.isEmpty()) {
            return;
        }
        if (selectedUris.size() == 1) {
            handleSingleVisualSelection(selectedUris.get(0));
            return;
        }

        ArrayList<ImageSelection> imageSelections = new ArrayList<>();
        ArrayList<VideoSelection> videoSelections = new ArrayList<>();
        for (int i = 0; i < selectedUris.size(); i++) {
            Uri uri = selectedUris.get(i);
            String mimeType = getContentResolver().getType(uri);
            if (isSupportedImage(mimeType, uri)) {
                imageSelections.add(new ImageSelection(uri, AvtohitProcessor.displayName(this, uri)));
            } else if (isSupportedVideo(mimeType, uri)) {
                String displayName = AvtohitProcessor.displayName(this, uri);
                videoSelections.add(new VideoSelection(uri, displayName, readModifiedEpochMs(uri), readDuration(uri), i));
            } else {
                clearVisualSelection();
                status.setText(R.string.visual_type_unsupported);
                return;
            }
        }

        if (!imageSelections.isEmpty() && !videoSelections.isEmpty()) {
            clearVisualSelection();
            status.setText(R.string.visual_multi_media_type_mix);
            return;
        }

        if (!videoSelections.isEmpty()) {
            handleVideoSequenceSelection(videoSelections);
            return;
        }

        Collections.sort(imageSelections, IMAGE_SELECTION_COMPARATOR);
        clearVisualSelection();
        for (ImageSelection selection : imageSelections) {
            visualImageUris.add(selection.uri);
            visualImageNames.add(selection.displayName);
        }

        ImageSelection firstImage = imageSelections.get(0);
        visualUri = firstImage.uri;
        visualMimeType = getContentResolver().getType(firstImage.uri);
        visualDisplayName = getString(R.string.visual_image_count, imageSelections.size());
        visualIsVideo = false;
        visualDurationMs = 0L;
        status.setText(R.string.ready);
    }

    private void handleVideoSequenceSelection(List<VideoSelection> videoSelections) {
        Collections.sort(videoSelections, VIDEO_SELECTION_COMPARATOR);
        clearAudioSelection();
        clearVisualSelection();
        long totalDurationMs = 0L;
        for (VideoSelection selection : videoSelections) {
            visualVideoUris.add(selection.uri);
            visualVideoNames.add(selection.displayName);
            visualVideoEpochs.add(selection.modifiedEpochMs);
            visualVideoDurations.add(selection.durationMs);
            totalDurationMs += Math.max(0L, selection.durationMs);
        }

        VideoSelection firstVideo = videoSelections.get(0);
        visualUri = firstVideo.uri;
        visualMimeType = getContentResolver().getType(firstVideo.uri);
        visualDisplayName = getString(R.string.visual_video_count, videoSelections.size());
        visualIsVideo = true;
        visualDurationMs = totalDurationMs;
        status.setText(R.string.ready);
    }

    private void handleSingleVisualSelection(Uri uri) {
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
        visualImageUris.clear();
        visualImageNames.clear();
        visualVideoUris.clear();
        visualVideoNames.clear();
        visualVideoEpochs.clear();
        visualVideoDurations.clear();
        if (!visualIsVideo) {
            visualImageUris.add(uri);
            visualImageNames.add(visualDisplayName);
        } else {
            visualVideoUris.add(uri);
            visualVideoNames.add(visualDisplayName);
            visualVideoEpochs.add(readModifiedEpochMs(uri));
            visualVideoDurations.add(visualDurationMs);
        }
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
        visualImageUris.clear();
        visualImageNames.clear();
        visualVideoUris.clear();
        visualVideoNames.clear();
        visualVideoEpochs.clear();
        visualVideoDurations.clear();
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
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
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
        if (!canStartMerge()) {
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
        ArrayList<Uri> selectedImageUris = new ArrayList<>(visualImageUris);
        ArrayList<Uri> selectedVideoUris = new ArrayList<>(visualVideoUris);
        boolean selectedVisualIsVideo = visualIsVideo;
        ExportProfile selectedProfile = exportProfile;
        int selectedFrameRate = frameRate;
        long selectedAudioDurationMs = audioDurationMs;
        long selectedVisualDurationMs = visualDurationMs;
        int selectedSlideSeconds = slideSeconds;
        String selectedAudioName = audioDisplayName;
        String selectedVisualName = visualDisplayName;
        ArrayList<String> selectedImageNames = new ArrayList<>(visualImageNames);
        ArrayList<String> selectedVideoNames = new ArrayList<>(visualVideoNames);
        ArrayList<Long> selectedVideoEpochs = new ArrayList<>(visualVideoEpochs);
        ArrayList<Long> selectedVideoDurations = new ArrayList<>(visualVideoDurations);
        AvtohitDebugLogger selectedLogger = debugLogger != null ? debugLogger : new AvtohitDebugLogger(this);

        try {
            executor.submit(() -> {
                try {
                    selectedLogger.startRun("AVTOHIT merge");
                    logRenderInputs(
                            selectedLogger,
                            selectedAudio,
                            selectedAudioName,
                            selectedVisual,
                            selectedVisualMime,
                            selectedVisualName,
                            selectedVisualIsVideo,
                            selectedImageUris,
                            selectedImageNames,
                            selectedVideoUris,
                            selectedVideoNames,
                            selectedVideoEpochs,
                            selectedVideoDurations,
                            destinationUri,
                            selectedProfile,
                            selectedFrameRate,
                            selectedAudioDurationMs,
                            selectedVisualDurationMs,
                            selectedSlideSeconds
                    );
                    AvtohitProcessor.Result result;
                    if (selectedAudio == null && selectedVideoUris.size() > 1) {
                        result = processor.renderVideos(
                                getApplicationContext(),
                                selectedVideoUris,
                                destinationUri,
                                selectedProfile,
                                selectedFrameRate,
                                selectedVisualDurationMs,
                                (currentMs, totalMs) -> postToUiIfAlive(() -> updateRenderProgress(currentMs, totalMs)),
                                selectedLogger
                        );
                    } else if (!selectedVisualIsVideo && !selectedImageUris.isEmpty()) {
                        result = processor.renderImages(
                                getApplicationContext(),
                                selectedAudio,
                                selectedImageUris,
                                destinationUri,
                                selectedProfile,
                                selectedFrameRate,
                                selectedAudioDurationMs,
                                selectedSlideSeconds,
                                (currentMs, totalMs) -> postToUiIfAlive(() -> updateRenderProgress(currentMs, totalMs)),
                                selectedLogger
                        );
                    } else {
                        result = processor.render(
                                getApplicationContext(),
                                selectedAudio,
                                selectedVisual,
                                selectedVisualMime,
                                destinationUri,
                                selectedProfile,
                                selectedFrameRate,
                                selectedAudioDurationMs,
                                (currentMs, totalMs) -> postToUiIfAlive(() -> updateRenderProgress(currentMs, totalMs)),
                                selectedLogger
                        );
                    }
                    selectedLogger.append("render_success outputBytes=" + result.outputBytes
                            + " visualKind=" + result.visualKind
                            + " videoReencoded=" + result.videoReencoded
                            + " usesImportedMp3=" + result.usesImportedMp3);
                    postToUiIfAlive(() -> onRenderSuccess(result));
                } catch (IOException | AvtohitException | RuntimeException error) {
                    selectedLogger.append(error);
                    postToUiIfAlive(() -> onRenderFailure(error));
                }
            });
        } catch (RejectedExecutionException error) {
            selectedLogger.append(error);
            setRendering(false);
            status.setText(R.string.render_unavailable);
        }
    }

    private void logRenderInputs(
            AvtohitDebugLogger logger,
            Uri selectedAudio,
            String selectedAudioName,
            Uri selectedVisual,
            String selectedVisualMime,
            String selectedVisualName,
            boolean selectedVisualIsVideo,
            List<Uri> selectedImageUris,
            List<String> selectedImageNames,
            List<Uri> selectedVideoUris,
            List<String> selectedVideoNames,
            List<Long> selectedVideoEpochs,
            List<Long> selectedVideoDurations,
            Uri destinationUri,
            ExportProfile selectedProfile,
            int selectedFrameRate,
            long selectedAudioDurationMs,
            long selectedVisualDurationMs,
            int selectedSlideSeconds
    ) {
        logger.append("audio_name=" + safeLogValue(selectedAudioName));
        logger.append("audio_uri=" + uriSummary(selectedAudio));
        logger.append("audio_duration_ms=" + selectedAudioDurationMs + " formatted=" + formatDuration(selectedAudioDurationMs));
        logger.append("visual_name=" + safeLogValue(selectedVisualName));
        logger.append("visual_uri=" + uriSummary(selectedVisual));
        logger.append("visual_mime=" + safeLogValue(selectedVisualMime));
        logger.append("visual_is_video=" + selectedVisualIsVideo);
        logger.append("visual_duration_ms=" + selectedVisualDurationMs + " formatted=" + formatDuration(selectedVisualDurationMs));
        logger.append("image_count=" + selectedImageUris.size());
        logger.append("slide_seconds=" + selectedSlideSeconds);
        for (int i = 0; i < selectedImageUris.size(); i++) {
            String imageName = i < selectedImageNames.size() ? selectedImageNames.get(i) : "";
            logger.append("image_order[" + i + "] name=" + safeLogValue(imageName) + " uri=" + uriSummary(selectedImageUris.get(i)));
        }
        logger.append("video_count=" + selectedVideoUris.size());
        for (int i = 0; i < selectedVideoUris.size(); i++) {
            String videoName = i < selectedVideoNames.size() ? selectedVideoNames.get(i) : "";
            long epochMs = i < selectedVideoEpochs.size() ? selectedVideoEpochs.get(i) : 0L;
            long durationMs = i < selectedVideoDurations.size() ? selectedVideoDurations.get(i) : 0L;
            logger.append("video_order[" + i + "] name=" + safeLogValue(videoName)
                    + " modifiedEpochMs=" + epochMs
                    + " durationMs=" + durationMs
                    + " uri=" + uriSummary(selectedVideoUris.get(i)));
        }
        logger.append("destination_uri=" + uriSummary(destinationUri));
        logger.append("export_profile=" + selectedProfile.label + " " + selectedProfile.width + "x" + selectedProfile.height);
        logger.append("frame_rate=" + selectedFrameRate);
        logger.append("log_location=" + logger.displayLocation());
    }

    private void onRenderSuccess(AvtohitProcessor.Result result) {
        setRendering(false);
        String mode;
        if (result.visualKind == AvtohitProcessor.VisualKind.VIDEO_SEQUENCE) {
            mode = getString(R.string.mode_video_sequence);
        } else if (result.visualKind == AvtohitProcessor.VisualKind.IMAGE) {
            mode = getString(R.string.mode_picture);
        } else {
            mode = getString(R.string.mode_video);
        }
        String audioMode = !result.usesImportedMp3
                ? getString(R.string.video_audio_merged)
                : result.videoReencoded
                ? getString(R.string.video_reencoded_mp3_copied)
                : getString(R.string.mp3_copied);
        status.setText(getString(R.string.done_detail, mode, audioMode));
    }

    private void onRenderFailure(Throwable error) {
        setRendering(false);
        status.setText(getString(R.string.failed_detail_with_log, safeMessage(error), debugLogLocation()));
    }

    private String debugLogLocation() {
        return debugLogger != null ? debugLogger.displayLocation() : "Downloads/AVTOHIT/AVTOHIT-debug-log.txt";
    }

    private void refreshUi() {
        refreshProjectHeader();
        refreshPreview();
        updateActions();
    }

    private void refreshProjectHeader() {
        String visualSummary = visualSummaryText();
        String audioSummary = audioDisplayName != null
                ? audioDisplayName
                : isVideoSequenceSelected() ? getString(R.string.mp3_not_needed) : getString(R.string.mp3_not_selected);
        String exportSummary = exportProfile.label + " - " + frameRate + "fps";

        visualChip.setText(ellipsize(visualSummary, 40));
        visualDurationLine.setText(visualDetailLine());
        audioChip.setText(ellipsize(audioSummary, 40));
        audioDurationLine.setText(isVideoSequenceSelected()
                ? getString(R.string.video_sequence_audio_line)
                : audioDurationMs > 0L
                ? getString(R.string.duration_value, formatDuration(audioDurationMs))
                : getString(R.string.audio_choose_line));
        exportChip.setText(exportSummary);
        exportOutputLine.setText(isVideoSequenceSelected()
                ? getString(R.string.export_video_sequence_line)
                : isImageVisualSelected()
                ? getString(R.string.export_image_time_line, imageChangeSummary())
                : getString(R.string.export_output_line));
        styleReadinessLabels();

        if (audioUri == null && visualUri == null) {
            status.setText(R.string.empty_project_status);
        }
    }

    private String visualSummaryText() {
        if (visualUri == null) {
            return getString(R.string.visual_not_selected);
        }
        if (!visualIsVideo && visualImageUris.size() > 1) {
            return getString(R.string.visual_image_count, visualImageUris.size());
        }
        if (isVideoSequenceSelected()) {
            return getString(R.string.visual_video_count, visualVideoUris.size());
        }
        return visualDisplayName != null ? visualDisplayName : getString(R.string.visual_not_selected);
    }

    private boolean isImageVisualSelected() {
        return visualUri != null && !visualIsVideo;
    }

    private boolean isVideoSequenceSelected() {
        return visualIsVideo && visualVideoUris.size() > 1;
    }

    private boolean canStartMerge() {
        return visualUri != null && (audioUri != null || isVideoSequenceSelected());
    }

    private String visualDetailLine() {
        if (visualUri == null) {
            return getString(R.string.visual_choose_line);
        }
        if (!visualIsVideo && visualImageUris.size() > 1) {
            return getString(R.string.visual_slideshow_detail, imageChangeSummary());
        }
        if (isVideoSequenceSelected()) {
            return visualDurationMs > 0L
                    ? getString(R.string.visual_video_sequence_detail, formatDuration(visualDurationMs))
                    : getString(R.string.visual_video_sequence_no_duration);
        }
        if (visualIsVideo && visualDurationMs > 0L) {
            return getString(R.string.duration_value, formatDuration(visualDurationMs));
        }
        if (!visualIsVideo && audioDurationMs > 0L) {
            return getString(R.string.duration_value, formatDuration(audioDurationMs));
        }
        return getString(R.string.visual_image_duration_line);
    }

    private String imageChangeSummary() {
        return imageChangeSummary(slideSeconds);
    }

    private String imageChangeSummary(int seconds) {
        if (seconds <= 0) {
            return getString(R.string.image_time_first_only);
        }
        return getString(R.string.image_time_seconds, seconds);
    }

    private void styleReadinessLabels() {
        styleReadinessLabel(visualReadiness, visualUri != null);
        styleReadinessLabel(audioReadiness, audioUri != null || isVideoSequenceSelected());
        styleReadinessLabel(exportReadiness, true);
    }

    private void styleReadinessLabel(TextView label, boolean ready) {
        if (label == null) {
            return;
        }
        label.setText(ready ? R.string.ready : R.string.status_needed);
        label.setTextColor(ready ? readyColor() : neededColor());
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
            previewModeLabel.setText(!visualIsVideo && visualImageUris.size() > 1 ? R.string.mode_slideshow : (isVideoSequenceSelected() ? R.string.mode_video_sequence : (visualIsVideo ? R.string.mode_video : R.string.mode_picture)));
        }

        long previewDurationMs = audioDurationMs > 0L ? audioDurationMs : (isVideoSequenceSelected() ? visualDurationMs : 0L);
        int totalMs = (int) Math.min(Integer.MAX_VALUE, previewDurationMs);
        previewSeek.setMax(Math.max(totalMs, 1));
        if (!previewPlaying) {
            previewSeek.setProgress(0);
            updatePreviewTime(0, previewDurationMs);
        }

    }

    private void updateActions() {
        boolean canOpenMergeMenu = !rendering;
        selectVisualButton.setEnabled(!rendering);
        selectAudioButton.setEnabled(!rendering);
        selectVisualButton.setSelected(visualUri != null);
        selectAudioButton.setSelected(audioUri != null);
        exportButton.setEnabled(canOpenMergeMenu);
        helpButton.setEnabled(true);
        skinButton.setEnabled(true);
        aboutButton.setEnabled(true);
        playButton.setEnabled(!rendering && audioUri != null);
        previewSeek.setEnabled(!rendering && audioUri != null);
        progress.setVisibility(rendering ? View.VISIBLE : View.GONE);
        playButton.setAlpha(playButton.isEnabled() ? 1f : 0.45f);
    }

    private void showExportDialog(boolean startExportWhenSaved) {
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_export, null);
        RadioGroup resolutionGroup = dialogView.findViewById(R.id.resolutionGroup);
        RadioGroup fpsGroup = dialogView.findViewById(R.id.fpsGroup);
        TextView imageTimeTitle = dialogView.findViewById(R.id.imageTimeTitle);
        SeekBar imageTimeSeek = dialogView.findViewById(R.id.imageTimeSeek);
        TextView imageTimeValue = dialogView.findViewById(R.id.imageTimeValue);
        TextView exportSummary = dialogView.findViewById(R.id.exportSummary);

        if (exportProfile == ExportProfile.P720) {
            resolutionGroup.check(R.id.resolution720);
        } else {
            resolutionGroup.check(R.id.resolution1080);
        }
        fpsGroup.check(frameRate == 60 ? R.id.fps60 : R.id.fps30);
        int imageTimeVisibility = isImageVisualSelected() ? View.VISIBLE : View.GONE;
        imageTimeTitle.setVisibility(imageTimeVisibility);
        imageTimeValue.setVisibility(imageTimeVisibility);
        imageTimeSeek.setVisibility(imageTimeVisibility);
        imageTimeSeek.setProgress(slideSeconds);
        updateImageTimeValue(imageTimeSeek, imageTimeValue);
        updateExportSummary(dialogView, exportSummary);

        RadioGroup.OnCheckedChangeListener listener = (group, checkedId) -> updateExportSummary(dialogView, exportSummary);
        resolutionGroup.setOnCheckedChangeListener(listener);
        fpsGroup.setOnCheckedChangeListener(listener);
        imageTimeSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                updateImageTimeValue(seekBar, imageTimeValue);
                updateExportSummary(dialogView, exportSummary);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                // No-op.
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                // No-op.
            }
        });

        TextView dialogTitle = buildDialogTitle(startExportWhenSaved ? R.string.export_dialog_title : R.string.settings_dialog_title);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setCustomTitle(dialogTitle)
                .setView(dialogView)
                .setNegativeButton(R.string.export_cancel, null)
                .setNeutralButton(R.string.export_apply, (dialogInterface, which) -> {
                    applyExportSelection(dialogView);
                    status.setText(getString(R.string.export_status_saved, exportProfile.label, frameRate));
                    refreshUi();
                })
                .setPositiveButton(startExportWhenSaved ? R.string.export_start : R.string.export_apply, (dialogInterface, which) -> {
                    applyExportSelection(dialogView);
                    refreshUi();
                    if (startExportWhenSaved) {
                        if (!canStartMerge()) {
                            status.setText(R.string.select_media_before_export);
                        } else {
                            openOutputPicker();
                        }
                    } else {
                        status.setText(getString(R.string.export_status_saved, exportProfile.label, frameRate));
                    }
                })
                .create();
        dialog.setOnShowListener(unused -> styleSettingsDialog(dialog, dialogTitle, dialogView));
        dialog.show();
    }

    private void showSkinDialog() {
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_skin, null);
        RadioGroup skinGroup = dialogView.findViewById(R.id.skinGroup);
        TextView skinSummary = dialogView.findViewById(R.id.exportSummary);

        skinGroup.check(currentSkin == AppSkin.DARK ? R.id.skinDark : R.id.skinLight);
        updateSkinSummary(skinGroup, skinSummary);
        skinGroup.setOnCheckedChangeListener((group, checkedId) -> updateSkinSummary(group, skinSummary));

        TextView dialogTitle = buildDialogTitle(R.string.app_skin_title);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setCustomTitle(dialogTitle)
                .setView(dialogView)
                .setNegativeButton(R.string.export_cancel, null)
                .setPositiveButton(R.string.export_apply, (dialogInterface, which) -> {
                    applySkinSelection(dialogView);
                    status.setText(getString(R.string.skin_status_saved, currentSkin.label));
                    refreshUi();
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
        SeekBar imageTimeSeek = dialogView.findViewById(R.id.imageTimeSeek);
        int resolutionId = resolutionGroup.getCheckedRadioButtonId();
        int fpsId = fpsGroup.getCheckedRadioButtonId();

        if (resolutionId == R.id.resolution720) {
            exportProfile = ExportProfile.P720;
        } else {
            exportProfile = ExportProfile.P1080;
        }

        frameRate = fpsId == R.id.fps60 ? 60 : 30;
        slideSeconds = clampSlideSeconds(imageTimeSeek.getProgress());
    }

    private void applySkinSelection(View dialogView) {
        RadioGroup skinGroup = dialogView.findViewById(R.id.skinGroup);
        int skinId = skinGroup.getCheckedRadioButtonId();
        if (skinId == R.id.skinDark) {
            currentSkin = AppSkin.DARK;
        } else {
            currentSkin = AppSkin.LIGHT;
        }
        saveSkin(currentSkin);
        applySkin();
    }

    private void updateExportSummary(View dialogView, TextView exportSummary) {
        RadioGroup resolutionGroup = dialogView.findViewById(R.id.resolutionGroup);
        RadioGroup fpsGroup = dialogView.findViewById(R.id.fpsGroup);
        SeekBar imageTimeSeek = dialogView.findViewById(R.id.imageTimeSeek);

        String resolutionLabel;
        int resolutionId = resolutionGroup.getCheckedRadioButtonId();
        if (resolutionId == R.id.resolution720) {
            resolutionLabel = getString(R.string.export_resolution_720);
        } else {
            resolutionLabel = getString(R.string.export_resolution_1080);
        }

        int selectedFrameRate = fpsGroup.getCheckedRadioButtonId() == R.id.fps60 ? 60 : 30;
        if (isImageVisualSelected()) {
            exportSummary.setText(getString(R.string.export_summary_with_images, resolutionLabel, selectedFrameRate, imageChangeSummary(clampSlideSeconds(imageTimeSeek.getProgress()))));
        } else {
            exportSummary.setText(getString(R.string.export_summary, resolutionLabel, selectedFrameRate));
        }
    }

    private void updateImageTimeValue(SeekBar seekBar, TextView imageTimeValue) {
        imageTimeValue.setText(imageChangeSummary(clampSlideSeconds(seekBar.getProgress())));
    }

    private void updateSkinSummary(RadioGroup skinGroup, TextView skinSummary) {
        String skinLabel = skinGroup.getCheckedRadioButtonId() == R.id.skinDark
                ? AppSkin.DARK.label
                : AppSkin.LIGHT.label;
        skinSummary.setText(getString(R.string.skin_summary, skinLabel));
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

    private long readModifiedEpochMs(Uri uri) {
        Cursor cursor = null;
        try {
            cursor = getContentResolver().query(uri, null, null, null, null);
            if (cursor == null || !cursor.moveToFirst()) {
                return 0L;
            }
            long value = firstPositiveLongColumn(
                    cursor,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                    MediaStore.MediaColumns.DATE_MODIFIED,
                    MediaStore.MediaColumns.DATE_ADDED
            );
            return normalizeEpochMs(value);
        } catch (RuntimeException ignored) {
            return 0L;
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }

    private boolean isVideoVisual(Uri uri, String mimeType) {
        String source = mimeType != null ? mimeType.toLowerCase(Locale.US) : uri.toString().toLowerCase(Locale.US);
        return isSupportedVideo(source, uri);
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
        return isSupportedImage(mimeType, uri) || isSupportedVideo(mimeType, uri);
    }

    private boolean isSupportedImage(String mimeType, Uri uri) {
        String source = firstNonBlank(mimeType, uri.toString());
        if (source == null) {
            return false;
        }
        String normalized = source.toLowerCase(Locale.US);
        return normalized.startsWith("image/")
                || normalized.endsWith(".jpg")
                || normalized.endsWith(".jpeg")
                || normalized.endsWith(".png")
                || normalized.endsWith(".webp")
                || normalized.endsWith(".heic")
                || normalized.endsWith(".heif");
    }

    private boolean isSupportedVideo(String mimeType, Uri uri) {
        String source = firstNonBlank(mimeType, uri != null ? uri.toString() : null);
        if (source == null) {
            return false;
        }
        String normalized = source.toLowerCase(Locale.US);
        return normalized.startsWith("video/")
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

    private static Long numericBaseName(String displayName) {
        if (displayName == null) {
            return null;
        }
        String name = displayName.trim();
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            name = name.substring(0, dot);
        }
        if (!name.matches("\\d+")) {
            return null;
        }
        try {
            return Long.parseLong(name);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static int clampSlideSeconds(int value) {
        return Math.max(0, Math.min(60, value));
    }

    private static long firstPositiveLongColumn(Cursor cursor, String... columnNames) {
        if (columnNames == null) {
            return 0L;
        }
        for (String columnName : columnNames) {
            int index = cursor.getColumnIndex(columnName);
            if (index >= 0) {
                try {
                    long value = cursor.getLong(index);
                    if (value > 0L) {
                        return value;
                    }
                } catch (RuntimeException ignored) {
                    // Try the next metadata column.
                }
            }
        }
        return 0L;
    }

    private static long normalizeEpochMs(long value) {
        if (value <= 0L) {
            return 0L;
        }
        return value < 100_000_000_000L ? value * 1000L : value;
    }

    private static long[] longArrayFromList(List<Long> values) {
        if (values == null || values.isEmpty()) {
            return new long[0];
        }
        long[] output = new long[values.size()];
        for (int i = 0; i < values.size(); i++) {
            Long value = values.get(i);
            output[i] = value != null ? value : 0L;
        }
        return output;
    }

    private String defaultOutputName() {
        if (audioDisplayName != null && !audioDisplayName.trim().isEmpty()) {
            return stripExtension(audioDisplayName) + ".mp4";
        }
        if (isVideoSequenceSelected() && !visualVideoNames.isEmpty()) {
            return stripExtension(visualVideoNames.get(0)) + "-merged.mp4";
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
        bottomActionsBar.setBackgroundColor(currentSkin.backgroundColor);
        styleHeaderBanner();

        styleCard(projectSummaryCard, currentSkin.surfaceColor);
        styleCard(previewCard, currentSkin.surfaceColor);
        styleBottomActionButton(helpButton);
        styleBottomActionButton(skinButton);
        styleBottomActionButton(aboutButton);

        styleTextInputs(projectSummaryCard, currentSkin.textColor);
        styleSecondaryText(visualChip);
        styleSecondaryText(visualDurationLine);
        styleSecondaryText(audioChip);
        styleSecondaryText(audioDurationLine);
        styleSecondaryText(exportChip);
        styleSecondaryText(exportOutputLine);
        styleReadinessLabels();
        summaryDividerOne.setBackgroundColor(currentSkin.borderColor);
        summaryDividerTwo.setBackgroundColor(currentSkin.borderColor);
        previewTitle.setTextColor(currentSkin.textColor);

        updateSystemBars();
    }

    private void styleHeaderBanner() {
        if (headerBanner == null) {
            return;
        }
        if (currentSkin != AppSkin.DARK) {
            headerBanner.clearColorFilter();
            return;
        }

        ColorMatrix invertColors = new ColorMatrix(new float[]{
                -1f, 0f, 0f, 0f, 255f,
                0f, -1f, 0f, 0f, 255f,
                0f, 0f, -1f, 0f, 255f,
                0f, 0f, 0f, 1f, 0f
        });
        headerBanner.setColorFilter(new ColorMatrixColorFilter(invertColors));
    }

    private void styleCard(View view, int fillColor) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fillColor);
        drawable.setCornerRadius(dp(22));
        drawable.setStroke(dp(1), currentSkin.borderColor);
        view.setBackground(drawable);
    }

    private void styleBottomActionButton(Button button) {
        if (button == null) {
            return;
        }
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(currentSkin.surfaceColor);
        drawable.setCornerRadius(dp(16));
        drawable.setStroke(dp(1), currentSkin.borderColor);
        button.setBackground(drawable);
        button.setTextColor(currentSkin.textColor);
        button.setAlpha(button.isEnabled() ? 1f : 0.55f);
    }

    private void styleSecondaryText(TextView textView) {
        if (textView != null) {
            textView.setTextColor(currentSkin.mutedColor);
        }
    }

    private int readyColor() {
        return currentSkin == AppSkin.DARK ? POSITIVE_READY_DARK : POSITIVE_READY_LIGHT;
    }

    private int neededColor() {
        return currentSkin == AppSkin.DARK ? NEGATIVE_NEEDED_DARK : NEGATIVE_NEEDED_LIGHT;
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
        boolean darkDialog = currentSkin == AppSkin.DARK;
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

package com.avtohit.app;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.avtohit.app.media.AvtohitException;
import com.avtohit.app.media.AvtohitProcessor;

import java.io.IOException;
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

    private TextView audioName;
    private TextView visualName;
    private TextView status;
    private Button selectAudioButton;
    private Button selectVisualButton;
    private Button createButton;
    private ProgressBar progress;

    private Uri audioUri;
    private Uri visualUri;
    private String visualMimeType;
    private boolean rendering;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        audioName = findViewById(R.id.audioName);
        visualName = findViewById(R.id.visualName);
        status = findViewById(R.id.status);
        selectAudioButton = findViewById(R.id.selectAudioButton);
        selectVisualButton = findViewById(R.id.selectVisualButton);
        createButton = findViewById(R.id.createButton);
        progress = findViewById(R.id.progress);

        selectAudioButton.setOnClickListener(view -> openAudioPicker());
        selectVisualButton.setOnClickListener(view -> openVisualPicker());
        createButton.setOnClickListener(view -> openOutputPicker());

        updateActions();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
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
            audioName.setText(AvtohitProcessor.displayName(this, uri));
            status.setText(R.string.ready);
        } else if (requestCode == REQUEST_VISUAL) {
            takeReadPermission(data, uri);
            visualUri = uri;
            visualMimeType = getContentResolver().getType(uri);
            visualName.setText(AvtohitProcessor.displayName(this, uri));
            status.setText(R.string.ready);
        } else if (requestCode == REQUEST_OUTPUT) {
            takeWritePermission(data, uri);
            renderTo(uri);
        }

        updateActions();
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
        status.setText(R.string.creating);

        Uri selectedAudio = audioUri;
        Uri selectedVisual = visualUri;
        String selectedVisualMime = visualMimeType;

        executor.submit(() -> {
            try {
                AvtohitProcessor.Result result = processor.render(
                        getApplicationContext(),
                        selectedAudio,
                        selectedVisual,
                        selectedVisualMime,
                        destinationUri
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
        String audioMode = result.usedVideoFallback
                ? getString(R.string.video_reencoded_mp3_copied)
                : getString(R.string.mp3_copied);
        status.setText(getString(R.string.done_detail, mode, audioMode));
    }

    private void onRenderFailure(Throwable error) {
        setRendering(false);
        status.setText(getString(R.string.failed_detail, safeMessage(error)));
    }

    private void setRendering(boolean rendering) {
        this.rendering = rendering;
        progress.setVisibility(rendering ? View.VISIBLE : View.GONE);
        updateActions();
    }

    private void updateActions() {
        selectAudioButton.setEnabled(!rendering);
        selectVisualButton.setEnabled(!rendering);
        createButton.setEnabled(!rendering && audioUri != null && visualUri != null);
    }

    private void takeReadPermission(Intent data, Uri uri) {
        if ((data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) == 0) {
            return;
        }
        try {
            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException ignored) {
            // Some document providers grant transient access only.
        }
    }

    private void takeWritePermission(Intent data, Uri uri) {
        if ((data.getFlags() & Intent.FLAG_GRANT_WRITE_URI_PERMISSION) == 0) {
            return;
        }
        try {
            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (SecurityException ignored) {
            // Some document providers grant transient access only.
        }
    }

    private static String defaultOutputName() {
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
}

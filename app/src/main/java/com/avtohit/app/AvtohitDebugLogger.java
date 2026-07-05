package com.avtohit.app;

import android.annotation.TargetApi;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class AvtohitDebugLogger {
    private static final String LOG_FILE_NAME = "AVTOHIT-debug-log.txt";
    private static final String LOG_DIR_NAME = "AVTOHIT";
    private static final String MIME_TYPE = "text/plain";

    private final Context appContext;
    private volatile String lastKnownLocation;

    public AvtohitDebugLogger(Context context) {
        this.appContext = context.getApplicationContext();
    }

    public synchronized void startRun(String title) {
        StringBuilder builder = new StringBuilder();
        builder.append("==== ")
                .append(title)
                .append(" ====\n")
                .append("started_at=")
                .append(timestamp())
                .append('\n')
                .append("android_sdk=")
                .append(Build.VERSION.SDK_INT)
                .append('\n')
                .append("device=")
                .append(Build.MANUFACTURER)
                .append(' ')
                .append(Build.MODEL)
                .append('\n')
                .append("log_location=")
                .append(displayLocation())
                .append("\n\n");
        write(builder.toString());
    }

    public synchronized void append(String message) {
        write(timestamp() + "  " + safe(message) + "\n");
    }

    public synchronized void append(Throwable error) {
        append("exception=" + error.getClass().getName() + ": " + safe(error.getMessage()));
        StackTraceElement[] stackTrace = error.getStackTrace();
        int maxLines = Math.min(stackTrace.length, 12);
        for (int i = 0; i < maxLines; i++) {
            append("  at " + stackTrace[i]);
        }
    }

    public String displayLocation() {
        String known = lastKnownLocation;
        if (known != null && !known.trim().isEmpty()) {
            return known;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return "Downloads/" + LOG_DIR_NAME + "/" + LOG_FILE_NAME;
        }
        return new File(fallbackLogDirectory(), LOG_FILE_NAME).getAbsolutePath();
    }

    private void write(String text) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                writeToDownloads(text);
                return;
            }
        } catch (IOException | RuntimeException ignored) {
            // Fall through to the app-private file if public Downloads cannot be used.
        }

        try {
            writeToFallbackFile(text);
        } catch (IOException | RuntimeException ignored) {
            // Logging must never make media rendering fail.
        }
    }

    @TargetApi(Build.VERSION_CODES.Q)
    private void writeToDownloads(String text) throws IOException {
        Uri uri = findOrCreateDownloadsLog();
        try (OutputStream output = appContext.getContentResolver().openOutputStream(uri, "wa")) {
            if (output == null) {
                throw new IOException("Could not open debug log output stream.");
            }
            output.write(text.getBytes(StandardCharsets.UTF_8));
        }
        lastKnownLocation = "Downloads/" + LOG_DIR_NAME + "/" + LOG_FILE_NAME;
    }

    @TargetApi(Build.VERSION_CODES.Q)
    private Uri findOrCreateDownloadsLog() throws IOException {
        ContentResolver resolver = appContext.getContentResolver();
        Uri collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        String relativePath = Environment.DIRECTORY_DOWNLOADS + "/" + LOG_DIR_NAME + "/";
        String[] projection = new String[]{MediaStore.Downloads._ID, MediaStore.Downloads.RELATIVE_PATH};
        String selection = MediaStore.Downloads.DISPLAY_NAME + "=? AND " + MediaStore.Downloads.RELATIVE_PATH + "=?";
        String[] args = new String[]{LOG_FILE_NAME, relativePath};

        try (Cursor cursor = resolver.query(collection, projection, selection, args, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                long id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID));
                return ContentUris.withAppendedId(collection, id);
            }
        }

        Uri existingWithEquivalentPath = findExistingDownloadsLogByName(resolver, collection, relativePath);
        if (existingWithEquivalentPath != null) {
            return existingWithEquivalentPath;
        }

        ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.DISPLAY_NAME, LOG_FILE_NAME);
        values.put(MediaStore.Downloads.MIME_TYPE, MIME_TYPE);
        values.put(MediaStore.Downloads.RELATIVE_PATH, relativePath);
        Uri created = resolver.insert(collection, values);
        if (created == null) {
            throw new IOException("Could not create debug log in Downloads.");
        }
        return created;
    }

    @TargetApi(Build.VERSION_CODES.Q)
    private Uri findExistingDownloadsLogByName(ContentResolver resolver, Uri collection, String expectedRelativePath) {
        String[] projection = new String[]{MediaStore.Downloads._ID, MediaStore.Downloads.RELATIVE_PATH};
        String selection = MediaStore.Downloads.DISPLAY_NAME + "=?";
        String[] args = new String[]{LOG_FILE_NAME};
        String sortOrder = MediaStore.Downloads.DATE_MODIFIED + " DESC";

        try (Cursor cursor = resolver.query(collection, projection, selection, args, sortOrder)) {
            if (cursor == null) {
                return null;
            }
            while (cursor.moveToNext()) {
                String relativePath = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Downloads.RELATIVE_PATH));
                if (isAvtohitLogPath(relativePath, expectedRelativePath)) {
                    long id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID));
                    return ContentUris.withAppendedId(collection, id);
                }
            }
        }
        return null;
    }

    private void writeToFallbackFile(String text) throws IOException {
        File file = new File(fallbackLogDirectory(), LOG_FILE_NAME);
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Could not create debug log directory.");
        }
        try (OutputStream output = new FileOutputStream(file, true)) {
            output.write(text.getBytes(StandardCharsets.UTF_8));
        }
        lastKnownLocation = file.getAbsolutePath();
    }

    private File fallbackLogDirectory() {
        File external = appContext.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);
        if (external != null) {
            return new File(external, LOG_DIR_NAME);
        }
        return new File(appContext.getFilesDir(), LOG_DIR_NAME);
    }

    private static String timestamp() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
    }

    private static String safe(String value) {
        return value == null ? "" : value.replace('\r', ' ').replace('\n', ' ').trim();
    }

    private static boolean isAvtohitLogPath(String candidatePath, String expectedRelativePath) {
        if (candidatePath == null || candidatePath.trim().isEmpty()) {
            return false;
        }
        String candidate = normalizePath(candidatePath);
        String expected = normalizePath(expectedRelativePath);
        return candidate.equals(expected)
                || candidate.equals(trimTrailingSlash(expected))
                || candidate.endsWith("/" + LOG_DIR_NAME + "/")
                || candidate.endsWith("/" + LOG_DIR_NAME);
    }

    private static String normalizePath(String value) {
        return value.replace('\\', '/').trim();
    }

    private static String trimTrailingSlash(String value) {
        if (value.endsWith("/")) {
            return value.substring(0, value.length() - 1);
        }
        return value;
    }
}

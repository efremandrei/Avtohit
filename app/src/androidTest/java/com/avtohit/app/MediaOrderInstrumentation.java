package com.avtohit.app;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import com.avtohit.app.media.AvtohitProcessor;
import com.avtohit.app.media.ExportProfile;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Device regression checks: real finger gestures, lifecycle restoration, and encoded output. */
public final class MediaOrderInstrumentation extends Instrumentation {
    private MainActivity activity;
    private final int[] colors = {Color.RED, Color.GREEN, Color.BLUE};

    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        start();
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            activity = (MainActivity) startActivitySync(new Intent(getTargetContext(), MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            File fixtures = new File(getTargetContext().getFilesDir(), "order-qa");
            if (!fixtures.exists() && !fixtures.mkdirs()) throw new AssertionError("Fixture folder");
            List<Uri> images = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                File file = new File(fixtures, (i + 1) + ".png");
                Bitmap image = Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888);
                image.eraseColor(colors[i]);
                try (FileOutputStream out = new FileOutputStream(file)) {
                    image.compress(Bitmap.CompressFormat.PNG, 100, out);
                }
                image.recycle();
                images.add(Uri.fromFile(file));
            }
            select(images);
            dragLastToFirst();
            assertOrder("visualImageUris", "3.png", "1.png", "2.png");

            ActivityMonitor monitor = addMonitor(MainActivity.class.getName(), null, false);
            runOnMainSync(() -> activity.recreate());
            Activity recreated = waitForMonitorWithTimeout(monitor, 8000L);
            removeMonitor(monitor);
            if (recreated == null) throw new AssertionError("Activity did not recreate");
            activity = (MainActivity) recreated;
            waitForIdleSync();
            assertOrder("visualImageUris", "3.png", "1.png", "2.png");
            File imageOutput = new File(fixtures, "reordered-images.mp4");
            new AvtohitProcessor().renderImagesSilent(getTargetContext(), uris("visualImageUris"),
                    Uri.fromFile(imageOutput), ExportProfile.P720_HORIZONTAL, 30, 1, null, null);
            assertFrames(imageOutput);
            sendStatus(0, message("PASS: photo drag order survives recreation and exported frames are blue/red/green"));

            List<Uri> videos = new ArrayList<>();
            for (int i = 1; i <= 3; i++) {
                File file = new File(fixtures, i + ".mp4");
                try (InputStream in = getContext().getAssets().open("order-qa/" + i + ".mp4");
                        FileOutputStream out = new FileOutputStream(file)) {
                    byte[] buffer = new byte[8192];
                    for (int count; (count = in.read(buffer)) >= 0;) out.write(buffer, 0, count);
                }
                videos.add(Uri.fromFile(file));
            }
            select(videos);
            dragLastToFirst();
            assertOrder("visualVideoUris", "3.mp4", "1.mp4", "2.mp4");
            File videoOutput = new File(fixtures, "reordered-videos.mp4");
            new AvtohitProcessor().renderVideos(getTargetContext(), uris("visualVideoUris"),
                    Uri.fromFile(videoOutput), ExportProfile.P720_HORIZONTAL, 30,
                    value("visualVideoDurations"), value("visualVideoSoundEffects"),
                    3000L, null, null);
            assertFrames(videoOutput);
            sendStatus(0, message("PASS: video drag order exports blue/red/green"));

            List<Uri> longList = new ArrayList<>();
            for (int i = 0; i < 9; i++) longList.add(images.get(i % 3));
            select(longList);
            View list = activity.findViewById(R.id.mediaInlineList);
            ScrollView scroll = null;
            for (android.view.ViewParent parent = list.getParent(); parent != null; parent = parent.getParent()) {
                if (parent instanceof ScrollView) { scroll = (ScrollView) parent; break; }
            }
            if (scroll == null) throw new AssertionError("Missing scrolling container");
            LinearLayout rows = (LinearLayout) list;
            Rect visible = new Rect();
            scroll.getGlobalVisibleRect(visible);
            int[] start = center(rows.getChildAt(0).findViewById(R.id.visualOrderDragHandle));
            long down = SystemClock.uptimeMillis();
            pointer(down, MotionEvent.ACTION_DOWN, start[0], start[1]);
            SystemClock.sleep(700L);
            int edgeY = visible.bottom - 12;
            pointer(down, MotionEvent.ACTION_MOVE, start[0], edgeY);
            SystemClock.sleep(900L);
            if (scroll.getScrollY() <= 0) throw new AssertionError("Drag did not auto-scroll");
            pointer(down, MotionEvent.ACTION_UP, start[0], edgeY);
            waitForIdleSync();
            sendStatus(0, message("PASS: long-list drag auto-scroll"));
            result.putString("stream", "3 device checks passed; photo/video frame order verified.\n");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            result.putString("stream", android.util.Log.getStackTraceString(error));
            finish(Activity.RESULT_CANCELED, result);
        }
    }

    private void select(List<Uri> files) throws Exception {
        Method select = MainActivity.class.getDeclaredMethod("handleVisualSelection", List.class);
        Method refresh = MainActivity.class.getDeclaredMethod("refreshUi");
        select.setAccessible(true);
        refresh.setAccessible(true);
        final Exception[] failure = new Exception[1];
        runOnMainSync(() -> {
            try { select.invoke(activity, files); refresh.invoke(activity); }
            catch (Exception error) { failure[0] = error; }
        });
        if (failure[0] != null) throw failure[0];
        waitForIdleSync();
        SystemClock.sleep(250L);
    }

    private void dragLastToFirst() {
        LinearLayout rows = activity.findViewById(R.id.mediaInlineList);
        if (rows.getChildCount() != 3) throw new AssertionError("Expected three rows");
        int[] start = center(rows.getChildAt(2).findViewById(R.id.visualOrderDragHandle));
        int[] end = center(rows.getChildAt(0).findViewById(R.id.visualOrderDragHandle));
        end[1] -= 24;
        sendStatus(0, message("Gesture: " + Arrays.toString(start) + " -> " + Arrays.toString(end)));
        long down = SystemClock.uptimeMillis();
        pointer(down, MotionEvent.ACTION_DOWN, start[0], start[1]);
        SystemClock.sleep(700L);
        for (int i = 1; i <= 12; i++) {
            pointer(down, MotionEvent.ACTION_MOVE, start[0], start[1] + (end[1] - start[1]) * i / 12f);
            SystemClock.sleep(35L);
        }
        Bitmap shot = getUiAutomation().takeScreenshot();
        try (FileOutputStream out = new FileOutputStream(new File(getTargetContext().getFilesDir(), "order-drag.png"))) {
            shot.compress(Bitmap.CompressFormat.PNG, 100, out);
        } catch (Exception error) { throw new AssertionError(error); }
        shot.recycle();
        pointer(down, MotionEvent.ACTION_UP, end[0], end[1]);
        waitForIdleSync();
    }

    private int[] center(View view) {
        int[] xy = new int[2];
        view.getLocationOnScreen(xy);
        xy[0] += view.getWidth() / 2;
        xy[1] += view.getHeight() / 2;
        return xy;
    }

    private void pointer(long down, int action, float x, float y) {
        MotionEvent event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0);
        event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        // Android's system drag window owns input after a long press.
        if (!getUiAutomation().injectInputEvent(event, true)) {
            throw new AssertionError("Gesture injection failed");
        }
        event.recycle();
    }

    @SuppressWarnings("unchecked") private <T> T value(String name) throws Exception {
        Field field = MainActivity.class.getDeclaredField(name);
        field.setAccessible(true);
        return (T) field.get(activity);
    }

    private ArrayList<Uri> uris(String name) throws Exception {
        return new ArrayList<>(this.<List<Uri>>value(name));
    }

    private void assertOrder(String name, String... expected) throws Exception {
        List<String> actual = new ArrayList<>();
        for (Uri uri : uris(name)) actual.add(uri.getLastPathSegment());
        if (!actual.equals(Arrays.asList(expected))) throw new AssertionError("Order: " + actual);
    }

    private void assertFrames(File video) throws Exception {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(video.getAbsolutePath());
            int[] expected = {Color.BLUE, Color.RED, Color.GREEN};
            for (int i = 0; i < expected.length; i++) {
                Bitmap frame = retriever.getFrameAtTime(i * 1_000_000L + 500_000L,
                        MediaMetadataRetriever.OPTION_CLOSEST);
                if (frame == null) throw new AssertionError("Missing frame " + i);
                int pixel = frame.getPixel(frame.getWidth() / 2, frame.getHeight() / 2);
                frame.recycle();
                if (Math.abs(Color.red(pixel) - Color.red(expected[i])) > 30
                        || Math.abs(Color.green(pixel) - Color.green(expected[i])) > 30
                        || Math.abs(Color.blue(pixel) - Color.blue(expected[i])) > 30) {
                    throw new AssertionError("Unexpected frame color " + i + ": " + Integer.toHexString(pixel));
                }
            }
        } finally { retriever.release(); }
    }

    private Bundle message(String text) {
        Bundle bundle = new Bundle();
        bundle.putString("stream", text + "\n");
        return bundle;
    }
}

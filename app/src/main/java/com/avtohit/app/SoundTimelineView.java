package com.avtohit.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewParent;

import com.avtohit.app.media.VideoSoundEffect;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class SoundTimelineView extends View {
    public interface OnEffectsChangedListener {
        void onEffectsChanged();
    }

    private static final int MODE_NONE = 0;
    private static final int MODE_DRAG = 1;
    private static final int MODE_RESIZE_START = 2;
    private static final int MODE_RESIZE_END = 3;
    private static final float MIN_ZOOM = 1f;
    private static final float MAX_ZOOM = 16f;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF scratch = new RectF();
    private final ArrayList<VideoSoundEffect> effects = new ArrayList<>();
    private long durationMs = 1L;
    private long playheadMs = 0L;
    private long visibleStartMs = 0L;
    private float zoomLevel = 1f;
    private int selectedIndex = -1;
    private int activeMode = MODE_NONE;
    private float lastTouchY;
    private int backgroundColor = 0xFFF7F8F5;
    private int laneColor = 0xFFEEF3EF;
    private int borderColor = 0xFFD5DDD8;
    private int textColor = 0xFF151817;
    private int mutedColor = 0xFF5D6662;
    private int accentColor = 0xFFA63C36;
    private boolean playbackActive;
    private OnEffectsChangedListener listener;

    public SoundTimelineView(Context context) {
        super(context);
        init();
    }

    public SoundTimelineView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        setMinimumHeight(dp(420));
        setFocusable(true);
    }

    public void setPalette(int backgroundColor, int laneColor, int borderColor, int textColor, int mutedColor, int accentColor) {
        this.backgroundColor = backgroundColor;
        this.laneColor = laneColor;
        this.borderColor = borderColor;
        this.textColor = textColor;
        this.mutedColor = mutedColor;
        this.accentColor = accentColor;
        invalidate();
    }

    public void setOnEffectsChangedListener(OnEffectsChangedListener listener) {
        this.listener = listener;
    }

    public void setDurationMs(long durationMs) {
        this.durationMs = Math.max(1L, durationMs);
        playheadMs = clamp(playheadMs, 0L, this.durationMs);
        clampEffects();
        clampVisibleWindow();
        invalidate();
    }

    public void setEffects(List<VideoSoundEffect> newEffects) {
        effects.clear();
        if (newEffects != null) {
            for (VideoSoundEffect effect : newEffects) {
                if (effect != null) {
                    effects.add(clamped(effect));
                }
            }
        }
        selectedIndex = effects.isEmpty() ? -1 : Math.min(Math.max(0, selectedIndex), effects.size() - 1);
        invalidate();
    }

    public ArrayList<VideoSoundEffect> effectsCopy() {
        ArrayList<VideoSoundEffect> copy = new ArrayList<>();
        for (VideoSoundEffect effect : effects) {
            copy.add(effect.copy());
        }
        return copy;
    }

    public long playheadMs() {
        return playheadMs;
    }

    public void setPlayheadMs(long playheadMs) {
        this.playheadMs = clamp(playheadMs, 0L, durationMs);
        keepPlayheadVisible();
        invalidate();
    }

    public void setPlaybackActive(boolean playbackActive) {
        this.playbackActive = playbackActive;
        invalidate();
    }

    public void zoomIn() {
        setZoomLevel(Math.min(MAX_ZOOM, zoomLevel * 2f));
    }

    public void zoomOut() {
        setZoomLevel(Math.max(MIN_ZOOM, zoomLevel / 2f));
    }

    public String zoomLabel() {
        return String.format(Locale.US, "%.0fx", zoomLevel);
    }

    public void addCensorBeep() {
        addSoundEffect(VideoSoundEffect.TYPE_CENSOR_BEEP);
    }

    public void addSoundEffect(String type) {
        long startMs = playheadMs;
        if (!effects.isEmpty() && startMs <= 0L) {
            startMs = Math.min(durationMs - VideoSoundEffect.MIN_DURATION_MS, effects.get(effects.size() - 1).endMs() + 250L);
        }
        startMs = clamp(startMs, 0L, Math.max(0L, durationMs - VideoSoundEffect.MIN_DURATION_MS));
        long duration = Math.min(VideoSoundEffect.DEFAULT_DURATION_MS, Math.max(VideoSoundEffect.MIN_DURATION_MS, durationMs - startMs));
        effects.add(new VideoSoundEffect(type, Math.max(0L, startMs), duration));
        selectedIndex = effects.size() - 1;
        notifyChanged();
    }

    public void deleteSelected() {
        if (selectedIndex < 0 || selectedIndex >= effects.size()) {
            return;
        }
        effects.remove(selectedIndex);
        selectedIndex = effects.isEmpty() ? -1 : Math.min(selectedIndex, effects.size() - 1);
        notifyChanged();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        Geometry geometry = geometry();

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(backgroundColor);
        canvas.drawRoundRect(0, 0, getWidth(), getHeight(), dp(18), dp(18), paint);

        drawLabel(canvas, "Video", geometry.videoLeft, dp(24), textColor, true, dp(15));
        drawLabel(canvas, "Audio", geometry.audioLeft, dp(24), textColor, true, dp(15));
        drawLane(canvas, geometry.videoLeft, geometry.top, geometry.videoRight, geometry.bottom);
        drawLane(canvas, geometry.audioLeft, geometry.top, geometry.audioRight, geometry.bottom);
        drawTimeTicks(canvas, geometry);
        drawVideoMarks(canvas, geometry);
        drawAudioWave(canvas, geometry);
        drawEffects(canvas, geometry);
        drawPlayhead(canvas, geometry);
    }

    private void drawLane(Canvas canvas, float left, float top, float right, float bottom) {
        scratch.set(left, top, right, bottom);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(laneColor);
        canvas.drawRoundRect(scratch, dp(12), dp(12), paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1));
        paint.setColor(borderColor);
        canvas.drawRoundRect(scratch, dp(12), dp(12), paint);
    }

    private void drawTimeTicks(Canvas canvas, Geometry geometry) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1f);
        paint.setColor(borderColor);
        int ticks = 6;
        long windowDurationMs = visibleDurationMs();
        for (int i = 0; i <= ticks; i++) {
            long tickTimeMs = visibleStartMs + Math.round(windowDurationMs * (i / (double) ticks));
            float y = timeToY(tickTimeMs, geometry);
            canvas.drawLine(geometry.videoLeft, y, geometry.audioRight, y, paint);
            drawLabel(canvas, formatTimelineTime(tickTimeMs), geometry.timeLabelLeft, y + dp(4), mutedColor, false, dp(10));
        }
    }

    private void drawVideoMarks(Canvas canvas, Geometry geometry) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(mutedColor);
        paint.setAlpha(35);
        int frameCount = 7;
        float frameHeight = Math.max(dp(18), (geometry.bottom - geometry.top - dp(42)) / 11f);
        float left = geometry.videoLeft + dp(10);
        float right = geometry.videoRight - dp(10);
        for (int i = 0; i < frameCount; i++) {
            float top = geometry.top + dp(14) + i * (frameHeight + dp(12));
            if (top + frameHeight > geometry.bottom - dp(14)) {
                break;
            }
            scratch.set(left, top, right, top + frameHeight);
            canvas.drawRoundRect(scratch, dp(6), dp(6), paint);
        }
        paint.setAlpha(255);
    }

    private void drawAudioWave(Canvas canvas, Geometry geometry) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.25f);
        paint.setColor(mutedColor);
        float centerX = (geometry.audioLeft + geometry.audioRight) / 2f;
        int steps = 64;
        for (int i = 0; i < steps; i++) {
            float y = geometry.top + ((geometry.bottom - geometry.top) * i / Math.max(1, steps - 1));
            float width = dp(5 + (i % 6) * 4);
            canvas.drawLine(centerX - width, y, centerX + width, y, paint);
        }
    }

    private void drawEffects(Canvas canvas, Geometry geometry) {
        long visibleEndMs = visibleStartMs + visibleDurationMs();
        for (int i = 0; i < effects.size(); i++) {
            VideoSoundEffect effect = effects.get(i);
            if (effect.endMs() < visibleStartMs || effect.startMs > visibleEndMs) {
                continue;
            }

            float effectTop = timeToY(Math.max(effect.startMs, visibleStartMs), geometry);
            float effectBottom = timeToY(Math.min(effect.endMs(), visibleEndMs), geometry);
            scratch.set(
                    geometry.audioLeft + dp(10),
                    effectTop,
                    geometry.audioRight - dp(10),
                    Math.max(effectTop + dp(54), effectBottom)
            );
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(i == selectedIndex ? accentColor : 0xFFC75D2C);
            canvas.drawRoundRect(scratch, dp(14), dp(14), paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(2));
            paint.setColor(0xFFFFFFFF);
            canvas.drawRoundRect(scratch, dp(14), dp(14), paint);

            drawEffectHandle(canvas, scratch.centerX(), scratch.top + dp(11));
            drawEffectHandle(canvas, scratch.centerX(), scratch.bottom - dp(11));
            drawLabel(canvas, effectLabel(effect.type), scratch.left + dp(14), scratch.centerY() + dp(6), 0xFFFFFFFF, true, dp(17));
        }
    }

    private void drawEffectHandle(Canvas canvas, float centerX, float centerY) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFFFFFFFF);
        canvas.drawCircle(centerX, centerY, dp(10), paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(2));
        paint.setColor(accentColor);
        canvas.drawCircle(centerX, centerY, dp(10), paint);
    }

    private void drawPlayhead(Canvas canvas, Geometry geometry) {
        float y = timeToY(playheadMs, geometry);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(playbackActive ? 4 : 3));
        paint.setColor(playbackActive ? 0xFF23C7D9 : textColor);
        canvas.drawLine(geometry.videoLeft, y, geometry.audioRight, y, paint);

        paint.setStyle(Paint.Style.FILL);
        scratch.set(geometry.videoLeft - dp(4), y - dp(8), geometry.videoLeft + dp(12), y + dp(8));
        canvas.drawRoundRect(scratch, dp(4), dp(4), paint);
        drawLabel(canvas, formatTimelineTime(playheadMs), geometry.audioRight - dp(44), y - dp(8), textColor, true, dp(10));
    }

    private void drawLabel(Canvas canvas, String text, float x, float y, int color, boolean bold, int sizePx) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(color);
        paint.setTextSize(sizePx);
        paint.setFakeBoldText(bold);
        canvas.drawText(text, x, y, paint);
        paint.setFakeBoldText(false);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        Geometry geometry = geometry();
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                setParentIntercept(false);
                selectedIndex = hitEffect(event.getX(), event.getY(), geometry);
                activeMode = selectedIndex >= 0 ? hitMode(event.getY(), geometry, effects.get(selectedIndex)) : MODE_NONE;
                lastTouchY = event.getY();
                if (selectedIndex < 0 && insideTimeline(event.getX(), event.getY(), geometry)) {
                    setPlayheadMs(yToTime(event.getY(), geometry));
                }
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (selectedIndex >= 0 && activeMode != MODE_NONE) {
                    moveSelected(yDeltaToMs(event.getY() - lastTouchY, geometry));
                    lastTouchY = event.getY();
                    notifyChanged();
                } else if (insideTimeline(event.getX(), event.getY(), geometry)) {
                    setPlayheadMs(yToTime(event.getY(), geometry));
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                setParentIntercept(true);
                activeMode = MODE_NONE;
                invalidate();
                return true;
            default:
                return true;
        }
    }

    private int hitEffect(float x, float y, Geometry geometry) {
        if (x < geometry.audioLeft || x > geometry.audioRight || y < geometry.top || y > geometry.bottom) {
            return -1;
        }
        long visibleEndMs = visibleStartMs + visibleDurationMs();
        for (int i = effects.size() - 1; i >= 0; i--) {
            VideoSoundEffect effect = effects.get(i);
            if (effect.endMs() < visibleStartMs || effect.startMs > visibleEndMs) {
                continue;
            }
            float effectTop = timeToY(Math.max(effect.startMs, visibleStartMs), geometry);
            float effectBottom = Math.max(effectTop + dp(54), timeToY(Math.min(effect.endMs(), visibleEndMs), geometry));
            if (y >= effectTop && y <= effectBottom) {
                return i;
            }
        }
        return -1;
    }

    private int hitMode(float y, Geometry geometry, VideoSoundEffect effect) {
        long visibleEndMs = visibleStartMs + visibleDurationMs();
        float effectTop = timeToY(Math.max(effect.startMs, visibleStartMs), geometry);
        float effectBottom = Math.max(effectTop + dp(54), timeToY(Math.min(effect.endMs(), visibleEndMs), geometry));
        float edge = dp(30);
        if (Math.abs(y - effectTop) <= edge) {
            return MODE_RESIZE_START;
        }
        if (Math.abs(y - effectBottom) <= edge) {
            return MODE_RESIZE_END;
        }
        return MODE_DRAG;
    }

    private void moveSelected(long deltaMs) {
        VideoSoundEffect effect = effects.get(selectedIndex);
        long start = effect.startMs;
        long duration = effect.durationMs;
        if (activeMode == MODE_DRAG) {
            start = clamp(start + deltaMs, 0L, Math.max(0L, durationMs - duration));
        } else if (activeMode == MODE_RESIZE_START) {
            long end = effect.endMs();
            start = clamp(start + deltaMs, 0L, Math.max(0L, end - VideoSoundEffect.MIN_DURATION_MS));
            duration = end - start;
        } else if (activeMode == MODE_RESIZE_END) {
            long end = clamp(effect.endMs() + deltaMs, start + VideoSoundEffect.MIN_DURATION_MS, durationMs);
            duration = end - start;
        }
        effects.set(selectedIndex, new VideoSoundEffect(effect.type, start, duration));
        setPlayheadMs(start);
    }

    private boolean insideTimeline(float x, float y, Geometry geometry) {
        return x >= geometry.videoLeft && x <= geometry.audioRight && y >= geometry.top && y <= geometry.bottom;
    }

    private void clampEffects() {
        for (int i = 0; i < effects.size(); i++) {
            effects.set(i, clamped(effects.get(i)));
        }
    }

    private VideoSoundEffect clamped(VideoSoundEffect effect) {
        long duration = Math.min(effect.durationMs, Math.max(VideoSoundEffect.MIN_DURATION_MS, durationMs));
        long start = clamp(effect.startMs, 0L, Math.max(0L, durationMs - duration));
        return new VideoSoundEffect(effect.type, start, duration);
    }

    private void setZoomLevel(float newZoomLevel) {
        if (Math.abs(newZoomLevel - zoomLevel) < 0.01f) {
            return;
        }
        zoomLevel = newZoomLevel;
        centerVisibleWindowOn(playheadMs);
        invalidate();
    }

    private void keepPlayheadVisible() {
        long windowDurationMs = visibleDurationMs();
        long maxStartMs = Math.max(0L, durationMs - windowDurationMs);
        if (playheadMs < visibleStartMs) {
            visibleStartMs = playheadMs;
        } else if (playheadMs > visibleStartMs + windowDurationMs) {
            visibleStartMs = playheadMs - windowDurationMs;
        }
        visibleStartMs = clamp(visibleStartMs, 0L, maxStartMs);
    }

    private void centerVisibleWindowOn(long anchorMs) {
        long windowDurationMs = visibleDurationMs();
        long maxStartMs = Math.max(0L, durationMs - windowDurationMs);
        visibleStartMs = clamp(anchorMs - (windowDurationMs / 2L), 0L, maxStartMs);
    }

    private void clampVisibleWindow() {
        visibleStartMs = clamp(visibleStartMs, 0L, Math.max(0L, durationMs - visibleDurationMs()));
    }

    private long visibleDurationMs() {
        if (zoomLevel <= MIN_ZOOM) {
            return durationMs;
        }
        return Math.max(1000L, Math.round(durationMs / zoomLevel));
    }

    private Geometry geometry() {
        float left = dp(10);
        float right = getWidth() - dp(10);
        float top = dp(42);
        float bottom = Math.max(top + dp(240), getHeight() - dp(18));
        float labelWidth = dp(42);
        float gap = dp(8);
        float lanesLeft = left + labelWidth;
        float available = Math.max(dp(240), right - lanesLeft - gap);
        float laneWidth = available / 2f;
        float videoLeft = lanesLeft;
        float videoRight = videoLeft + laneWidth;
        float audioLeft = videoRight + gap;
        float audioRight = Math.min(right, audioLeft + laneWidth);
        return new Geometry(top, bottom, left, videoLeft, videoRight, audioLeft, audioRight);
    }

    private float timeToY(long timeMs, Geometry geometry) {
        long visibleDurationMs = visibleDurationMs();
        long relativeMs = Math.min(Math.max(0L, timeMs - visibleStartMs), visibleDurationMs);
        return geometry.top + ((geometry.bottom - geometry.top) * relativeMs / (float) visibleDurationMs);
    }

    private long yToTime(float y, Geometry geometry) {
        float height = Math.max(1f, geometry.bottom - geometry.top);
        long visibleDurationMs = visibleDurationMs();
        return clamp(visibleStartMs + Math.round(((y - geometry.top) / height) * visibleDurationMs), 0L, durationMs);
    }

    private long yDeltaToMs(float deltaY, Geometry geometry) {
        float height = Math.max(1f, geometry.bottom - geometry.top);
        return Math.round((deltaY / height) * visibleDurationMs());
    }

    private static String effectLabel(String type) {
        if (VideoSoundEffect.TYPE_HIGH_BEEP.equals(type)) {
            return "HIGH";
        }
        if (VideoSoundEffect.TYPE_LOW_BEEP.equals(type)) {
            return "LOW";
        }
        return "BIP";
    }

    private static String formatTimelineTime(long timeMs) {
        long totalSeconds = Math.max(0L, timeMs / 1000L);
        long minutes = totalSeconds / 60L;
        long seconds = totalSeconds % 60L;
        return String.format(Locale.US, "%d:%02d", minutes, seconds);
    }

    private long clamp(long value, long min, long max) {
        return Math.min(max, Math.max(min, value));
    }

    private void setParentIntercept(boolean allowIntercept) {
        ViewParent parent = getParent();
        if (parent != null) {
            parent.requestDisallowInterceptTouchEvent(!allowIntercept);
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void notifyChanged() {
        invalidate();
        if (listener != null) {
            listener.onEffectsChanged();
        }
    }

    private static final class Geometry {
        final float top;
        final float bottom;
        final float timeLabelLeft;
        final float videoLeft;
        final float videoRight;
        final float audioLeft;
        final float audioRight;

        Geometry(float top, float bottom, float timeLabelLeft, float videoLeft, float videoRight, float audioLeft, float audioRight) {
            this.top = top;
            this.bottom = bottom;
            this.timeLabelLeft = timeLabelLeft;
            this.videoLeft = videoLeft;
            this.videoRight = videoRight;
            this.audioLeft = audioLeft;
            this.audioRight = audioRight;
        }
    }
}

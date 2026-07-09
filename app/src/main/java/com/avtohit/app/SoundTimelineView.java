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

public final class SoundTimelineView extends View {
    public interface OnEffectsChangedListener {
        void onEffectsChanged();
    }

    private static final int MODE_NONE = 0;
    private static final int MODE_DRAG = 1;
    private static final int MODE_RESIZE_START = 2;
    private static final int MODE_RESIZE_END = 3;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF scratch = new RectF();
    private final ArrayList<VideoSoundEffect> effects = new ArrayList<>();
    private long durationMs = 1L;
    private long playheadMs = 0L;
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
        invalidate();
    }

    public void setPlaybackActive(boolean playbackActive) {
        this.playbackActive = playbackActive;
        invalidate();
    }

    public void addCensorBeep() {
        long startMs = playheadMs;
        if (!effects.isEmpty() && startMs <= 0L) {
            startMs = Math.min(durationMs - VideoSoundEffect.MIN_DURATION_MS, effects.get(effects.size() - 1).endMs() + 250L);
        }
        long duration = Math.min(VideoSoundEffect.DEFAULT_DURATION_MS, Math.max(VideoSoundEffect.MIN_DURATION_MS, durationMs - startMs));
        effects.add(new VideoSoundEffect(VideoSoundEffect.TYPE_CENSOR_BEEP, Math.max(0L, startMs), duration));
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
        drawVideoTicks(canvas, geometry);
        drawAudioWave(canvas, geometry);
        drawEffects(canvas, geometry);
        drawPlayhead(canvas, geometry);
    }

    private void drawLane(Canvas canvas, float left, float top, float right, float bottom) {
        scratch.set(left, top, right, bottom);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(laneColor);
        canvas.drawRoundRect(scratch, dp(18), dp(18), paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1));
        paint.setColor(borderColor);
        canvas.drawRoundRect(scratch, dp(18), dp(18), paint);
    }

    private void drawVideoTicks(Canvas canvas, Geometry geometry) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(2));
        paint.setColor(mutedColor);
        int ticks = 10;
        for (int i = 0; i <= ticks; i++) {
            float y = geometry.top + ((geometry.bottom - geometry.top) * i / ticks);
            canvas.drawLine(geometry.videoLeft + dp(14), y, geometry.videoRight - dp(14), y, paint);
        }

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(mutedColor);
        float frameHeight = Math.max(dp(34), (geometry.bottom - geometry.top - dp(54)) / 7f);
        float left = geometry.videoLeft + dp(18);
        float right = geometry.videoRight - dp(18);
        for (int i = 0; i < 6; i++) {
            float top = geometry.top + dp(18) + i * (frameHeight + dp(8));
            if (top + frameHeight > geometry.bottom - dp(18)) {
                break;
            }
            scratch.set(left, top, right, top + frameHeight);
            paint.setAlpha(42);
            canvas.drawRoundRect(scratch, dp(10), dp(10), paint);
            paint.setAlpha(255);
        }
    }

    private void drawAudioWave(Canvas canvas, Geometry geometry) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(2));
        paint.setColor(mutedColor);
        float centerX = (geometry.audioLeft + geometry.audioRight) / 2f;
        int steps = 54;
        for (int i = 0; i < steps; i++) {
            float y = geometry.top + ((geometry.bottom - geometry.top) * i / Math.max(1, steps - 1));
            float width = dp(12 + (i % 6) * 7);
            canvas.drawLine(centerX - width, y, centerX + width, y, paint);
        }
    }

    private void drawEffects(Canvas canvas, Geometry geometry) {
        for (int i = 0; i < effects.size(); i++) {
            VideoSoundEffect effect = effects.get(i);
            float effectTop = timeToY(effect.startMs, geometry);
            float effectBottom = timeToY(effect.endMs(), geometry);
            scratch.set(
                    geometry.audioLeft + dp(8),
                    effectTop,
                    geometry.audioRight - dp(8),
                    Math.max(effectTop + dp(58), effectBottom)
            );
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(i == selectedIndex ? accentColor : 0xFFC75D2C);
            canvas.drawRoundRect(scratch, dp(16), dp(16), paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(2));
            paint.setColor(0xFFFFFFFF);
            canvas.drawRoundRect(scratch, dp(16), dp(16), paint);

            drawEffectHandle(canvas, scratch.centerX(), scratch.top + dp(11));
            drawEffectHandle(canvas, scratch.centerX(), scratch.bottom - dp(11));
            drawLabel(canvas, "BIP", scratch.left + dp(16), scratch.centerY() + dp(6), 0xFFFFFFFF, true, dp(18));
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
        for (int i = effects.size() - 1; i >= 0; i--) {
            VideoSoundEffect effect = effects.get(i);
            float effectTop = timeToY(effect.startMs, geometry);
            float effectBottom = Math.max(effectTop + dp(58), timeToY(effect.endMs(), geometry));
            if (y >= effectTop && y <= effectBottom) {
                return i;
            }
        }
        return -1;
    }

    private int hitMode(float y, Geometry geometry, VideoSoundEffect effect) {
        float effectTop = timeToY(effect.startMs, geometry);
        float effectBottom = Math.max(effectTop + dp(58), timeToY(effect.endMs(), geometry));
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
        playheadMs = clamp(start, 0L, durationMs);
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

    private Geometry geometry() {
        float left = dp(12);
        float right = getWidth() - dp(12);
        float top = dp(42);
        float bottom = Math.max(top + dp(240), getHeight() - dp(18));
        float gap = dp(12);
        float available = Math.max(dp(240), right - left - gap);
        float videoWidth = Math.max(dp(98), available * 0.34f);
        float audioWidth = Math.max(dp(140), available - videoWidth);
        if (videoWidth + audioWidth + gap > right - left) {
            audioWidth = Math.max(dp(120), right - left - gap - videoWidth);
        }
        float videoLeft = left;
        float videoRight = videoLeft + videoWidth;
        float audioLeft = videoRight + gap;
        float audioRight = Math.min(right, audioLeft + audioWidth);
        return new Geometry(top, bottom, videoLeft, videoRight, audioLeft, audioRight);
    }

    private float timeToY(long timeMs, Geometry geometry) {
        return geometry.top + ((geometry.bottom - geometry.top) * Math.min(Math.max(0L, timeMs), durationMs) / (float) durationMs);
    }

    private long yToTime(float y, Geometry geometry) {
        float height = Math.max(1f, geometry.bottom - geometry.top);
        return clamp(Math.round(((y - geometry.top) / height) * durationMs), 0L, durationMs);
    }

    private long yDeltaToMs(float deltaY, Geometry geometry) {
        float height = Math.max(1f, geometry.bottom - geometry.top);
        return Math.round((deltaY / height) * durationMs);
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
        final float videoLeft;
        final float videoRight;
        final float audioLeft;
        final float audioRight;

        Geometry(float top, float bottom, float videoLeft, float videoRight, float audioLeft, float audioRight) {
            this.top = top;
            this.bottom = bottom;
            this.videoLeft = videoLeft;
            this.videoRight = videoRight;
            this.audioLeft = audioLeft;
            this.audioRight = audioRight;
        }
    }
}

package com.avtohit.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import com.avtohit.app.media.VideoSoundEffect;

import java.util.ArrayList;
import java.util.List;

public final class SoundTimelineView extends View {
    public interface OnEffectsChangedListener {
        void onEffectsChanged();
    }

    private static final int MODE_NONE = 0;
    private static final int MODE_DRAG = 1;
    private static final int MODE_RESIZE_LEFT = 2;
    private static final int MODE_RESIZE_RIGHT = 3;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF scratch = new RectF();
    private final ArrayList<VideoSoundEffect> effects = new ArrayList<>();
    private long durationMs = 1L;
    private int selectedIndex = -1;
    private int activeMode = MODE_NONE;
    private float lastTouchX;
    private int backgroundColor = 0xFFF7F8F5;
    private int laneColor = 0xFFEEF3EF;
    private int borderColor = 0xFFD5DDD8;
    private int textColor = 0xFF151817;
    private int mutedColor = 0xFF5D6662;
    private int accentColor = 0xFFA63C36;
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
        setMinimumHeight(dp(220));
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

    public int selectedIndex() {
        return selectedIndex;
    }

    public void addCensorBeep() {
        long startMs = 0L;
        if (!effects.isEmpty()) {
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
        float width = getWidth();
        float left = dp(14);
        float right = width - dp(14);
        float videoTop = dp(38);
        float laneHeight = dp(48);
        float audioTop = dp(116);
        float bottom = audioTop + laneHeight;

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(backgroundColor);
        canvas.drawRoundRect(0, 0, width, getHeight(), dp(18), dp(18), paint);

        drawLabel(canvas, "Video timeline", left, dp(24), textColor, true);
        drawLane(canvas, left, videoTop, right, videoTop + laneHeight);
        drawVideoTicks(canvas, left, videoTop, right, videoTop + laneHeight);

        drawLabel(canvas, "Audio timeline", left, dp(102), textColor, true);
        drawLane(canvas, left, audioTop, right, bottom);
        drawAudioWave(canvas, left, audioTop, right, bottom);
        drawEffects(canvas, left, audioTop, right, bottom);

        drawLabel(canvas, "Drag blocks to move. Drag block edges to stretch.", left, bottom + dp(28), mutedColor, false);
    }

    private void drawLane(Canvas canvas, float left, float top, float right, float bottom) {
        scratch.set(left, top, right, bottom);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(laneColor);
        canvas.drawRoundRect(scratch, dp(13), dp(13), paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1));
        paint.setColor(borderColor);
        canvas.drawRoundRect(scratch, dp(13), dp(13), paint);
    }

    private void drawVideoTicks(Canvas canvas, float left, float top, float right, float bottom) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(2));
        paint.setColor(mutedColor);
        int ticks = 8;
        for (int i = 0; i <= ticks; i++) {
            float x = left + ((right - left) * i / ticks);
            canvas.drawLine(x, top + dp(11), x, bottom - dp(11), paint);
        }
    }

    private void drawAudioWave(Canvas canvas, float left, float top, float right, float bottom) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(2));
        paint.setColor(mutedColor);
        float centerY = (top + bottom) / 2f;
        int steps = 48;
        for (int i = 0; i < steps; i++) {
            float x = left + ((right - left) * i / steps);
            float height = dp(5 + (i % 5) * 2);
            canvas.drawLine(x, centerY - height, x, centerY + height, paint);
        }
    }

    private void drawEffects(Canvas canvas, float left, float top, float right, float bottom) {
        for (int i = 0; i < effects.size(); i++) {
            VideoSoundEffect effect = effects.get(i);
            float effectLeft = timeToX(effect.startMs, left, right);
            float effectRight = timeToX(effect.endMs(), left, right);
            scratch.set(effectLeft, top + dp(5), Math.max(effectLeft + dp(22), effectRight), bottom - dp(5));
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(i == selectedIndex ? accentColor : 0xFFC75D2C);
            canvas.drawRoundRect(scratch, dp(10), dp(10), paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(2));
            paint.setColor(0xFFFFFFFF);
            canvas.drawRoundRect(scratch, dp(10), dp(10), paint);
            paint.setStyle(Paint.Style.FILL);
            drawLabel(canvas, "BIP", scratch.left + dp(8), scratch.centerY() + dp(5), 0xFFFFFFFF, true);
        }
    }

    private void drawLabel(Canvas canvas, String text, float x, float y, int color, boolean bold) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(color);
        paint.setTextSize(dp(bold ? 14 : 12));
        paint.setFakeBoldText(bold);
        canvas.drawText(text, x, y, paint);
        paint.setFakeBoldText(false);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float left = dp(14);
        float right = getWidth() - dp(14);
        float audioTop = dp(116);
        float audioBottom = audioTop + dp(48);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                getParent().requestDisallowInterceptTouchEvent(true);
                selectedIndex = hitEffect(event.getX(), event.getY(), left, audioTop, right, audioBottom);
                activeMode = selectedIndex >= 0 ? hitMode(event.getX(), left, right, effects.get(selectedIndex)) : MODE_NONE;
                lastTouchX = event.getX();
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (selectedIndex >= 0 && activeMode != MODE_NONE) {
                    moveSelected(xDeltaToMs(event.getX() - lastTouchX, left, right));
                    lastTouchX = event.getX();
                    notifyChanged();
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                getParent().requestDisallowInterceptTouchEvent(false);
                activeMode = MODE_NONE;
                invalidate();
                return true;
            default:
                return true;
        }
    }

    private int hitEffect(float x, float y, float left, float top, float right, float bottom) {
        if (y < top || y > bottom) {
            return -1;
        }
        for (int i = effects.size() - 1; i >= 0; i--) {
            VideoSoundEffect effect = effects.get(i);
            float effectLeft = timeToX(effect.startMs, left, right);
            float effectRight = Math.max(effectLeft + dp(22), timeToX(effect.endMs(), left, right));
            if (x >= effectLeft && x <= effectRight) {
                return i;
            }
        }
        return -1;
    }

    private int hitMode(float x, float left, float right, VideoSoundEffect effect) {
        float effectLeft = timeToX(effect.startMs, left, right);
        float effectRight = Math.max(effectLeft + dp(22), timeToX(effect.endMs(), left, right));
        float edge = dp(14);
        if (Math.abs(x - effectLeft) <= edge) {
            return MODE_RESIZE_LEFT;
        }
        if (Math.abs(x - effectRight) <= edge) {
            return MODE_RESIZE_RIGHT;
        }
        return MODE_DRAG;
    }

    private void moveSelected(long deltaMs) {
        VideoSoundEffect effect = effects.get(selectedIndex);
        long start = effect.startMs;
        long duration = effect.durationMs;
        if (activeMode == MODE_DRAG) {
            start = clamp(start + deltaMs, 0L, Math.max(0L, durationMs - duration));
        } else if (activeMode == MODE_RESIZE_LEFT) {
            long end = effect.endMs();
            start = clamp(start + deltaMs, 0L, Math.max(0L, end - VideoSoundEffect.MIN_DURATION_MS));
            duration = end - start;
        } else if (activeMode == MODE_RESIZE_RIGHT) {
            long end = clamp(effect.endMs() + deltaMs, start + VideoSoundEffect.MIN_DURATION_MS, durationMs);
            duration = end - start;
        }
        effects.set(selectedIndex, new VideoSoundEffect(effect.type, start, duration));
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

    private float timeToX(long timeMs, float left, float right) {
        return left + ((right - left) * Math.min(Math.max(0L, timeMs), durationMs) / (float) durationMs);
    }

    private long xDeltaToMs(float deltaX, float left, float right) {
        float width = Math.max(1f, right - left);
        return Math.round((deltaX / width) * durationMs);
    }

    private long clamp(long value, long min, long max) {
        return Math.min(max, Math.max(min, value));
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
}

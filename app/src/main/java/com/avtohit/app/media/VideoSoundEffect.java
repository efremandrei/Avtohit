package com.avtohit.app.media;

import java.util.Locale;

public final class VideoSoundEffect {
    public static final String TYPE_CENSOR_BEEP = "censor_beep";
    public static final long MIN_DURATION_MS = 120L;
    public static final long DEFAULT_DURATION_MS = 700L;

    public final String type;
    public final long startMs;
    public final long durationMs;

    public VideoSoundEffect(String type, long startMs, long durationMs) {
        this.type = type == null || type.trim().isEmpty() ? TYPE_CENSOR_BEEP : type;
        this.startMs = Math.max(0L, startMs);
        this.durationMs = Math.max(MIN_DURATION_MS, durationMs);
    }

    public long endMs() {
        return startMs + durationMs;
    }

    public VideoSoundEffect withTiming(long newStartMs, long newDurationMs) {
        return new VideoSoundEffect(type, newStartMs, newDurationMs);
    }

    public VideoSoundEffect copy() {
        return new VideoSoundEffect(type, startMs, durationMs);
    }

    public String encode() {
        return type + "," + startMs + "," + durationMs;
    }

    public static VideoSoundEffect decode(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        String[] parts = raw.split(",");
        if (parts.length < 3) {
            return null;
        }
        try {
            String decodedType = parts[0].trim().toLowerCase(Locale.US);
            long decodedStartMs = Long.parseLong(parts[1].trim());
            long decodedDurationMs = Long.parseLong(parts[2].trim());
            return new VideoSoundEffect(decodedType, decodedStartMs, decodedDurationMs);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}

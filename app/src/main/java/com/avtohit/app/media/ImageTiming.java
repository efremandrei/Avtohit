package com.avtohit.app.media;

import java.util.ArrayList;
import java.util.List;

/** Resolves per-image overrides without changing the order of the selected images. */
public final class ImageTiming {
    public static boolean hasOverrides(List<Integer> seconds) {
        if (seconds != null) {
            for (Integer value : seconds) {
                if (value != null && value > 0) return true;
            }
        }
        return false;
    }

    public static List<Long> durations(int imageCount, List<Integer> overrides, int sharedSeconds,
            boolean autoSplit, long audioDurationMs, boolean withAudio) {
        if (imageCount <= 0) throw new IllegalArgumentException("Select at least one image.");
        if (sharedSeconds < 0 || sharedSeconds > 60) {
            throw new IllegalArgumentException("Image time must be between 0 and 60 seconds.");
        }
        if (withAudio && audioDurationMs <= 0) {
            throw new IllegalArgumentException("The MP3 duration could not be read.");
        }
        if (overrides != null) {
            for (Integer seconds : overrides) {
                if (seconds != null && (seconds < 0 || seconds > 60)) {
                    throw new IllegalArgumentException("Image time must be between 0 and 60 seconds.");
                }
            }
        }
        // Keep the original zero-time behavior when no individual time is configured.
        if (withAudio && sharedSeconds == 0 && !autoSplit && !hasOverrides(overrides)) {
            ArrayList<Long> still = new ArrayList<>();
            still.add(audioDurationMs);
            return still;
        }
        ArrayList<Long> result = new ArrayList<>();
        for (int i = 0; i < imageCount; i++) {
            int seconds = overrides != null && i < overrides.size() && overrides.get(i) != null
                    ? overrides.get(i) : 0;
            if (seconds < 0 || seconds > 60) {
                throw new IllegalArgumentException("Image time must be between 0 and 60 seconds.");
            }
            long duration = (long) (seconds > 0 ? seconds : sharedSeconds) * 1000L;
            if (duration == 0 && withAudio && autoSplit) {
                duration = audioDurationMs / imageCount + (i < audioDurationMs % imageCount ? 1L : 0L);
            }
            if (duration <= 0) {
                throw new IllegalArgumentException("Set a time for every image, or choose a shared Image time.");
            }
            result.add(duration);
        }
        return result;
    }

    public static int visibleImageCount(List<Long> durations, long audioDurationMs) {
        long elapsed = 0L;
        for (int i = 0; i < durations.size(); i++) {
            elapsed += durations.get(i);
            if (elapsed >= audioDurationMs) return i + 1;
        }
        return durations.size();
    }

    private ImageTiming() {}
}

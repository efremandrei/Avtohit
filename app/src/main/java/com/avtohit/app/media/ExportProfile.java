package com.avtohit.app.media;

public final class ExportProfile {
    public static final ExportProfile P720 = new ExportProfile("720p vertical", "720p", "Vertical", 720, 1280, false);
    public static final ExportProfile P1080 = new ExportProfile("1080p vertical", "1080p", "Vertical", 1080, 1920, false);
    public static final ExportProfile P720_HORIZONTAL = new ExportProfile("720p horizontal", "720p", "Horizontal", 1280, 720, true);
    public static final ExportProfile P1080_HORIZONTAL = new ExportProfile("1080p horizontal", "1080p", "Horizontal", 1920, 1080, true);

    public final String label;
    public final String resolutionLabel;
    public final String directionLabel;
    public final int width;
    public final int height;
    public final boolean horizontal;

    private ExportProfile(String label, String resolutionLabel, String directionLabel, int width, int height, boolean horizontal) {
        this.label = label;
        this.resolutionLabel = resolutionLabel;
        this.directionLabel = directionLabel;
        this.width = width;
        this.height = height;
        this.horizontal = horizontal;
    }

    public static ExportProfile fromSelection(boolean resolution720, boolean horizontal) {
        if (resolution720) {
            return horizontal ? P720_HORIZONTAL : P720;
        }
        return horizontal ? P1080_HORIZONTAL : P1080;
    }

    public static ExportProfile fromLabel(String label) {
        if (P720_HORIZONTAL.label.equals(label)) {
            return P720_HORIZONTAL;
        }
        if (P1080_HORIZONTAL.label.equals(label)) {
            return P1080_HORIZONTAL;
        }
        if (P720.label.equals(label) || "720p".equals(label)) {
            return P720;
        }
        return P1080;
    }

    public boolean is720() {
        return this == P720 || this == P720_HORIZONTAL;
    }
}

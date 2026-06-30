package com.avtohit.app.media;

public final class ExportProfile {
    public static final ExportProfile P720 = new ExportProfile("720p", 720, 1280);
    public static final ExportProfile P1080 = new ExportProfile("1080p", 1080, 1920);
    public static final ExportProfile P4K = new ExportProfile("4K", 2160, 3840);

    public final String label;
    public final int width;
    public final int height;

    private ExportProfile(String label, int width, int height) {
        this.label = label;
        this.width = width;
        this.height = height;
    }
}

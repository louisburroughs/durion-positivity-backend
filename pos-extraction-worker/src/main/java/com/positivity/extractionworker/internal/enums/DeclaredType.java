package com.positivity.extractionworker.internal.enums;

/** The file type a caller declares for an upload. The worker verifies it against the bytes. */
public enum DeclaredType {
    PDF(true),
    JPEG(true),
    PNG(true),
    HEIC(true),
    TIFF(true),
    CSV(false),
    XLSX(false);

    private final boolean renderable;

    DeclaredType(boolean renderable) {
        this.renderable = renderable;
    }

    /** Whether the type is a document that renders to page images (not a spreadsheet). */
    public boolean isRenderable() {
        return renderable;
    }
}

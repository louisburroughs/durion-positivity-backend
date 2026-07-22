package com.positivity.accounting.internal.dto;

import org.jspecify.annotations.NonNull;

/**
 * Rendered export artifact held for download (issue #999).
 *
 * @param content     rendered bytes (CSV text or PDF binary)
 * @param contentType MIME type of the artifact ({@code text/csv} or {@code application/pdf})
 * @param filename    suggested download filename including extension
 */
public record ReportExportArtifact(
        byte @NonNull [] content,
        @NonNull String contentType,
        @NonNull String filename) {}

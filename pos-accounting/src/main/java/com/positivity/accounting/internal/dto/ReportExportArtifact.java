package com.positivity.accounting.internal.dto;

import java.util.Arrays;
import org.jspecify.annotations.NonNull;

/**
 * Rendered export artifact held for download (issue #999).
 *
 * @param content     rendered bytes (CSV text or PDF binary)
 * @param contentType MIME type of the artifact ({@code text/csv} or
 *                    {@code application/pdf})
 * @param filename    suggested download filename including extension
 */
public record ReportExportArtifact(
        byte @NonNull [] content,
        @NonNull String contentType,
        @NonNull String filename) {

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj
                instanceof ReportExportArtifact(byte[] otherContent, String otherContentType, String otherFilename))) {
            return false;
        }
        return Arrays.equals(content, otherContent)
                && contentType.equals(otherContentType)
                && filename.equals(otherFilename);
    }

    @Override
    public int hashCode() {
        int result = Arrays.hashCode(content);
        result = 31 * result + contentType.hashCode();
        result = 31 * result + filename.hashCode();
        return result;
    }

    @Override
    public String toString() {
        return "ReportExportArtifact[content="
                + Arrays.toString(content)
                + ", contentType="
                + contentType
                + ", filename="
                + filename
                + "]";
    }
}

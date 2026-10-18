package com.positivity.inboundmail.internal.dto;

import java.util.Arrays;
import org.jspecify.annotations.NonNull;

/**
 * One staged attachment as fetched. The bytes stay out of {@link #toString()} (ADR-0072: no
 * attachment byte is ever logged).
 */
public record StagedAttachmentBytes(@NonNull byte[] content, long sizeBytes, @NonNull String sha256) {

    @Override
    public boolean equals(Object other) {
        return other instanceof StagedAttachmentBytes that
                && sizeBytes == that.sizeBytes
                && sha256.equals(that.sha256)
                && Arrays.equals(content, that.content);
    }

    @Override
    public int hashCode() {
        return 31 * sha256.hashCode() + Arrays.hashCode(content);
    }

    @Override
    public String toString() {
        return "StagedAttachmentBytes[sizeBytes=" + sizeBytes + ", sha256=" + sha256 + "]";
    }
}

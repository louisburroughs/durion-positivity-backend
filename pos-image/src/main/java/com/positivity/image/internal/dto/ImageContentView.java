package com.positivity.image.internal.dto;

import java.util.Arrays;
import java.util.Objects;
import org.jspecify.annotations.NonNull;

/**
 * Image bytes held by this service, ready to serve (CAP-324 #1257).
 *
 * <p>Distinct from {@link ImageFileView}, which names a file for the caller to resolve on disk. This
 * one carries the content itself, because for a stored image there is no file to resolve — and the
 * two cases must not be conflated, or a stored image would be looked for on a volume that has never
 * held it.
 *
 * <p>{@code equals}/{@code hashCode}/{@code toString} are overridden because the default record ones
 * compare {@code content} by array identity, not by the bytes it holds (SonarCloud java:S6218): two
 * views of the same image fetched independently would compare unequal.
 */
public record ImageContentView(
        @NonNull String filename, @NonNull String contentType, byte[] content) {

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ImageContentView other)) {
            return false;
        }
        return filename.equals(other.filename)
                && contentType.equals(other.contentType)
                && Arrays.equals(content, other.content);
    }

    @Override
    public int hashCode() {
        return Objects.hash(filename, contentType, Arrays.hashCode(content));
    }

    @Override
    public String toString() {
        return "ImageContentView[filename=" + filename + ", contentType=" + contentType + ", content="
                + (content == null ? "null" : content.length + " bytes") + "]";
    }
}

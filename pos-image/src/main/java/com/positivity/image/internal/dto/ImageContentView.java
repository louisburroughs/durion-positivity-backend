package com.positivity.image.internal.dto;

import org.jspecify.annotations.NonNull;

/**
 * Image bytes held by this service, ready to serve (CAP-324 #1257).
 *
 * <p>Distinct from {@link ImageFileView}, which names a file for the caller to resolve on disk. This
 * one carries the content itself, because for a stored image there is no file to resolve — and the
 * two cases must not be conflated, or a stored image would be looked for on a volume that has never
 * held it.
 */
public record ImageContentView(
        @NonNull String filename, @NonNull String contentType, byte[] content) {}

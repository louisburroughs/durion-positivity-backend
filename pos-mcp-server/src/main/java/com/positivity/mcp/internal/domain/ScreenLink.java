package com.positivity.mcp.internal.domain;

import org.jspecify.annotations.NonNull;

/**
 * A resolved deep link to a UI screen — the rung-3 answer when no tool can satisfy the request.
 * {@code url} has had its template placeholders filled from the request context.
 */
public record ScreenLink(
        @NonNull String screenKey,
        @NonNull String title,
        @NonNull String url,
        double score) {}

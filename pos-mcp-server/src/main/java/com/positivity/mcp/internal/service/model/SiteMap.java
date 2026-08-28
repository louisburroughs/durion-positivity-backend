package com.positivity.mcp.internal.service.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import org.jspecify.annotations.NonNull;

/**
 * Parsed view of the frontend's {@code /sitemap.json} artifact — a read-only, versioned index of the
 * application's navigable sections.
 *
 * <p>{@code sections} preserves the artifact's {@code (group, order)} ordering (main before admin).
 * {@code generatedAt} is a per-build timestamp used only for change detection/telemetry and must not
 * be treated as a stable key. Unknown fields from a newer contract version are ignored so a version
 * bump that only adds fields does not break parsing.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SiteMap(
        @NonNull String application,
        int version,
        @NonNull String generatedAt,
        @NonNull List<SiteMapSection> sections) {}

package com.positivity.openapivalidation.internal.policy;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

public record OpenApiModulePolicy(
        @NonNull Mode mode,
        @Nullable String reason,
        @NonNull DepthMode annotationDepth,
        @Nullable String annotationDepthReason,
        @NonNull ErrorSchemaMode errorSchema,
        @Nullable String errorSchemaReason) {

    /** Spec-shape checks only: presence of summary and description, no depth or error-schema checks. */
    public OpenApiModulePolicy(@NonNull Mode mode, @Nullable String reason) {
        this(mode, reason, DepthMode.EXEMPT, null, ErrorSchemaMode.EXEMPT, null);
    }

    /** Depth dimension only, for callers written before the error-schema dimension existed (#1720). */
    public OpenApiModulePolicy(
            @NonNull Mode mode,
            @Nullable String reason,
            @NonNull DepthMode annotationDepth,
            @Nullable String annotationDepthReason) {
        this(mode, reason, annotationDepth, annotationDepthReason, ErrorSchemaMode.EXEMPT, null);
    }

    public enum Mode {
        STRICT,
        REPORT_ONLY,
        EXCEPTION,
        EXCLUDED
    }

    /**
     * Enforcement level for the ADR-0042 §1 description depth and §3 request body rules described in
     * {@code docs/OPENAPI_DESCRIPTION_STANDARD.md}.
     */
    public enum DepthMode {
        STRICT,
        REPORT_ONLY,
        EXEMPT
    }

    /**
     * Enforcement level for ADR-0017 §3's rule that every 4xx/5xx response body is the
     * {@code ApiError} envelope (issue #1720). A third dimension rather than part of
     * {@code mode}, for the same reason {@code annotationDepth} is: the fleet-wide gap is large,
     * so a module converts one dimension at a time while the others stay enforced.
     */
    public enum ErrorSchemaMode {
        STRICT,
        REPORT_ONLY,
        EXEMPT
    }

    public @NonNull Mode depthIssueMode() {
        return annotationDepth == DepthMode.STRICT ? Mode.STRICT : Mode.REPORT_ONLY;
    }

    public @NonNull Mode errorSchemaIssueMode() {
        return errorSchema == ErrorSchemaMode.STRICT ? Mode.STRICT : Mode.REPORT_ONLY;
    }
}

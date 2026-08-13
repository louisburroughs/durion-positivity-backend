package com.positivity.openapivalidation.internal.policy;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

public record OpenApiModulePolicy(
        @NonNull Mode mode,
        @Nullable String reason,
        @NonNull DepthMode annotationDepth,
        @Nullable String annotationDepthReason) {

    /** Spec-shape checks only: presence of summary and description, no ADR-0042 depth checks. */
    public OpenApiModulePolicy(@NonNull Mode mode, @Nullable String reason) {
        this(mode, reason, DepthMode.EXEMPT, null);
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

    public @NonNull Mode depthIssueMode() {
        return annotationDepth == DepthMode.STRICT ? Mode.STRICT : Mode.REPORT_ONLY;
    }
}

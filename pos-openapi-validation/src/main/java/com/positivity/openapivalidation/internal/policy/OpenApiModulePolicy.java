package com.positivity.openapivalidation.internal.policy;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

public record OpenApiModulePolicy(
        @NonNull Mode mode, @Nullable String reason) {

    public enum Mode {
        STRICT,
        REPORT_ONLY,
        EXCEPTION,
        EXCLUDED
    }
}

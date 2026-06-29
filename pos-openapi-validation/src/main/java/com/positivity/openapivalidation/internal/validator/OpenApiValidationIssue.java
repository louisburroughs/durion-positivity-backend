package com.positivity.openapivalidation.internal.validator;

import com.positivity.openapivalidation.internal.policy.OpenApiModulePolicy.Mode;
import org.jspecify.annotations.NonNull;

public record OpenApiValidationIssue(
        @NonNull String module, @NonNull Mode mode, @NonNull String message) {}

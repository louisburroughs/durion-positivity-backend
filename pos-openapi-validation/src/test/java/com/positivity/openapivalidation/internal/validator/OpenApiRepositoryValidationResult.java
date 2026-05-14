package com.positivity.openapivalidation.internal.validator;

import org.jspecify.annotations.NonNull;

import java.util.List;

public record OpenApiRepositoryValidationResult(
        @NonNull List<OpenApiValidationIssue> blockingIssues,
        @NonNull List<OpenApiValidationIssue> reportOnlyIssues) {
}

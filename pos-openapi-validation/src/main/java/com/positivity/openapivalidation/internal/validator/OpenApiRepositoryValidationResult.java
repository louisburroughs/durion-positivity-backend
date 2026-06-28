package com.positivity.openapivalidation.internal.validator;

import java.util.List;
import org.jspecify.annotations.NonNull;

public record OpenApiRepositoryValidationResult(
        @NonNull List<OpenApiValidationIssue> blockingIssues,
        @NonNull List<OpenApiValidationIssue> reportOnlyIssues) {}

package com.positivity.people.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * Minimal page envelope for people-module list endpoints, copied from pos-customer's
 * {@code internal.dto.PagedResponse} (the same fixed-shape rationale applies here: a stable,
 * flat contract instead of Spring's {@code Page} serialization).
 */
@Schema(description = "A page of results")
public record PagedResponse<T>(
        @Schema(description = "Items on this page", requiredMode = REQUIRED)
        List<T> items,

        @Schema(description = "Zero-based page index", example = "0", requiredMode = REQUIRED)
        int page,

        @Schema(description = "Requested page size", example = "20", requiredMode = REQUIRED)
        int size,

        @Schema(description = "Total matching items across all pages", example = "42", requiredMode = REQUIRED)
        long totalElements,

        @Schema(description = "Total number of pages", example = "3", requiredMode = REQUIRED)
        int totalPages) {}

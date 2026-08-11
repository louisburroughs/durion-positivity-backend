package com.positivity.marketing.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.function.Function;
import org.springframework.data.domain.Page;

/**
 * Minimal page envelope for marketing list endpoints.
 *
 * <p>Spring's {@code Page} serializes an unstable, deeply nested shape that leaks into the
 * generated SDK, so list endpoints return this fixed contract instead.
 */
@Schema(description = "A page of results")
public record PagedResponse<T>(
        @Schema(description = "Items on this page", requiredMode = REQUIRED)
        List<T> items,

        @Schema(description = "Zero-based page index", example = "0", requiredMode = REQUIRED)
        int page,

        @Schema(description = "Requested page size", example = "50", requiredMode = REQUIRED)
        int size,

        @Schema(description = "Total matching items across all pages", example = "412", requiredMode = REQUIRED)
        long totalElements,

        @Schema(description = "Total number of pages", example = "9", requiredMode = REQUIRED)
        int totalPages) {

    public static <S, T> PagedResponse<T> from(Page<S> page, Function<S, T> mapper) {
        return new PagedResponse<>(
                page.getContent().stream().map(mapper).toList(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages());
    }
}

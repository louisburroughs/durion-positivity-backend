package com.positivity.catalog.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * OpenAPI-only description of the {@code Page<ServiceDto>} that {@code GET /v1/products/services}
 * returns (#2246), so the spec types the {@code content} items as {@link ServiceDto} instead of the
 * untyped array springdoc derives from the raw {@code Page}. Never instantiated: the controller still
 * returns Spring Data's {@code Page}, which this app serializes directly (no {@code PagedModel}), so
 * these are the top-level fields on the wire. The response also carries {@code pageable} and {@code
 * sort} objects, which are left out here because the order is fixed and the page coordinates are
 * already given by {@code number} and {@code size}.
 */
@Schema(name = "ServiceDtoPage", description = "A page of catalog services")
public record ServiceDtoPage(
        @Schema(description = "The services on this page", requiredMode = REQUIRED)
        List<ServiceDto> content,

        @Schema(description = "Total services across all pages", example = "42", requiredMode = REQUIRED)
        long totalElements,

        @Schema(description = "Total number of pages", example = "1", requiredMode = REQUIRED)
        int totalPages,

        @Schema(description = "Zero-based index of this page", example = "0", requiredMode = REQUIRED)
        int number,

        @Schema(description = "Requested page size", example = "50", requiredMode = REQUIRED)
        int size,

        @Schema(description = "Number of services on this page", example = "42", requiredMode = REQUIRED)
        int numberOfElements,

        @Schema(description = "Whether this is the first page", requiredMode = REQUIRED)
        boolean first,

        @Schema(description = "Whether this is the last page", requiredMode = REQUIRED)
        boolean last,

        @Schema(description = "Whether this page has no services", requiredMode = REQUIRED)
        boolean empty) {}

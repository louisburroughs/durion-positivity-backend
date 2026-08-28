package com.positivity.supplier.internal.service.model;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.NonNull;

/**
 * A page of results.
 *
 * <p>Spring Data's {@code Page} serialises an unstable, deeply nested shape that leaks straight into
 * the generated OpenAPI document and Angular SDK, so paginated supplier endpoints return this fixed
 * envelope instead — mirroring the same decision already taken in pos-customer and pos-marketing.
 * Deliberately carries no Spring type of its own: this record is part of the module's public contract
 * (ADR-0026), and mapping from {@code Page} belongs on the implementation side of that boundary.
 *
 * @param items the items on this page, in the endpoint's documented order
 * @param page zero-based page index
 * @param size requested page size
 * @param totalElements total matching items across all pages
 * @param totalPages total number of pages
 * @param <T> item type
 */
@Schema(description = "A page of results.")
public record PagedResponse<T>(
        @Schema(description = "Items on this page, in the order documented by the endpoint.") @NonNull
        List<T> items,

        @Schema(description = "Zero-based index of this page.", example = "0")
        int page,

        @Schema(description = "Requested page size.", example = "50")
        int size,

        @Schema(description = "Total number of matching items across all pages.", example = "137")
        long totalElements,

        @Schema(description = "Total number of pages available for this page size.", example = "3")
        int totalPages) {

    public PagedResponse {
        Objects.requireNonNull(items, "items must not be null");
        items = List.copyOf(items);
    }
}

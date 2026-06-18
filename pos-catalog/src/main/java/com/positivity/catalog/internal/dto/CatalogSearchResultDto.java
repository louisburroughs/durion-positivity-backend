package com.positivity.catalog.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Cursor-based search result for the catalog product search endpoint.
 *
 * <p>
 * Pagination is cursor-based (opaque token). The cursor encodes the next page
 * number as a decimal string; treat it as opaque. Clients should pass the
 * returned {@code nextCursor} as the {@code cursor} query parameter in the
 * next request. A {@code null} nextCursor means no more results.
 *
 * Issue: CAP-247 Story #17
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Cursor-based catalog search result")
public class CatalogSearchResultDto {

    @NotNull
    @Schema(
            description = "Page of matching product summaries",
            example = "[]",
            requiredMode = REQUIRED)
    private List<ProductSummary> data;

    @Schema(
            description = "Opaque cursor for the next page; null if no more results",
            example = "2",
            requiredMode = NOT_REQUIRED)
    private String nextCursor;

    @Schema(description = "Number of items requested (limit)", example = "20", requiredMode = REQUIRED)
    private int limit;
}

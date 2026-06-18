package com.positivity.catalog.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
@Schema(description = "Paged product search result")
public class ProductSearchResultDto {

    @Schema(description = "Page of matching products", example = "[]", requiredMode = REQUIRED)
    private List<ProductDto> items;

    @Schema(description = "Zero-based page index", example = "0", requiredMode = REQUIRED)
    private int page;

    @Schema(description = "Page size", example = "20", requiredMode = REQUIRED)
    private int size;

    @Schema(description = "Total number of matching products", example = "135", requiredMode = REQUIRED)
    private long total;
}

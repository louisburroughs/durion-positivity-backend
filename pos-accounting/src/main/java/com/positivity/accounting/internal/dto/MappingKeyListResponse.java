package com.positivity.accounting.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response DTO for paginated list of Mapping Keys.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Paginated list of mapping keys")
public class MappingKeyListResponse {

    @Schema(description = "Mapping keys on the current page", requiredMode = REQUIRED)
    private List<MappingKeyResponse> results;

    @Schema(description = "Total number of mapping keys matching the query", example = "42", requiredMode = NOT_REQUIRED)
    private Long totalCount;

    @Schema(description = "Zero-based index of the current page", example = "0", requiredMode = NOT_REQUIRED)
    private Integer pageNumber;

    @Schema(description = "Number of items per page", example = "20", requiredMode = NOT_REQUIRED)
    private Integer pageSize;

    @Schema(description = "Total number of pages available", example = "3", requiredMode = NOT_REQUIRED)
    private Integer totalPages;
}

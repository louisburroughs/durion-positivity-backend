package com.positivity.accounting.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response DTO for listing Posting Rule Sets.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Paginated list of posting rule sets")
public class PostingRuleSetListResponse {

    @Schema(description = "Posting rule sets on the current page", requiredMode = NOT_REQUIRED)
    private List<PostingRuleSetResponse> ruleSets;

    @Schema(description = "Total number of posting rule sets across all pages", example = "42", requiredMode = NOT_REQUIRED)
    private Integer totalElements;

    @Schema(description = "Total number of pages available", example = "5", requiredMode = NOT_REQUIRED)
    private Integer totalPages;

    @Schema(description = "Zero-based index of the current page", example = "0", requiredMode = NOT_REQUIRED)
    private Integer currentPage;

    @Schema(description = "Number of items per page", example = "10", requiredMode = NOT_REQUIRED)
    private Integer pageSize;
}

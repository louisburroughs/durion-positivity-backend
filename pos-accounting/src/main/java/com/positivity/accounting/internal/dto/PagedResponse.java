package com.positivity.accounting.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Tolerate;

/**
 * Generic paginated list response wrapper.
 *
 * @see <a href=
 *      "domains/accounting/.business-rules/BACKEND_CONTRACT_GUIDE.md">Backend
 *      Contract Guide - Pagination</a>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Generic paginated list response wrapper")
public class PagedResponse<T> {

    @Schema(description = "Items on the current page", requiredMode = REQUIRED)
    private List<T> items;

    @Schema(description = "Zero-based index of the current page", example = "0", requiredMode = NOT_REQUIRED)
    private Integer pageNumber;

    @Schema(description = "Number of items per page", example = "20", requiredMode = NOT_REQUIRED)
    private Integer pageSize;

    @Schema(description = "Total number of items matching the query", example = "42", requiredMode = NOT_REQUIRED)
    private Long totalCount;

    @Schema(description = "Total number of pages available", example = "3", requiredMode = NOT_REQUIRED)
    private Integer totalPages;

    /**
     * Convenience constructor that computes totalPages from totalCount and
     * pageSize.
     */
    @Tolerate
    public PagedResponse(List<T> items, Integer pageNumber, Integer pageSize, Long totalCount) {
        this.items = items;
        this.pageNumber = pageNumber;
        this.pageSize = pageSize;
        this.totalCount = totalCount;
        this.totalPages = (int) Math.ceil((double) totalCount / pageSize);
    }

    @JsonProperty("content")
    public List<T> getContent() {
        return items;
    }

    @JsonProperty("totalElements")
    public Long getTotalElements() {
        return totalCount;
    }
}

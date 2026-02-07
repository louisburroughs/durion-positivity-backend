package com.positivity.accounting.internal.dto;

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
public class PagedResponse<T> {

    private List<T> items;
    private Integer pageNumber;
    private Integer pageSize;
    private Long totalCount;
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
}

package com.positivity.accounting.internal.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Response DTO for listing Posting Rule Sets.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PostingRuleSetListResponse {

    private List<PostingRuleSetResponse> ruleSets;
    private Integer totalElements;
    private Integer totalPages;
    private Integer currentPage;
    private Integer pageSize;
}

package com.positivity.accounting.internal.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Response DTO for paginated list of Posting Categories.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PostingCategoryListResponse {

    private List<PostingCategoryResponse> results;
    private Long totalCount;
    private Integer pageNumber;
    private Integer pageSize;
    private Integer totalPages;
}

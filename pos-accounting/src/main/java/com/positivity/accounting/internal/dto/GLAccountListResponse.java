package com.positivity.accounting.internal.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Response DTO for paginated list of GL Accounts.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GLAccountListResponse {

    private List<GLAccountResponse> results;
    private Long totalCount;
    private Integer pageNumber;
    private Integer pageSize;
    private Integer totalPages;
}

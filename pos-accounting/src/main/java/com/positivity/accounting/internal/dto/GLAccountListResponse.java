package com.positivity.accounting.internal.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
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

    @JsonProperty("glAccounts")
    private List<GLAccountResponse> results;

    @JsonProperty("totalElements")
    private Long totalCount;

    private Integer pageNumber;
    private Integer pageSize;
    private Integer totalPages;
}

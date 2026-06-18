package com.positivity.accounting.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response DTO for paginated list of GL Accounts.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Paginated list of GL accounts")
public class GLAccountListResponse {

    @JsonProperty("glAccounts")
    @Schema(description = "GL accounts on the current page", requiredMode = REQUIRED)
    private List<GLAccountResponse> results;

    @JsonProperty("totalElements")
    @Schema(description = "Total number of matching GL accounts", example = "137", requiredMode = REQUIRED)
    private Long totalCount;

    @Schema(description = "Zero-based page index", example = "0", requiredMode = REQUIRED)
    private Integer pageNumber;

    @Schema(description = "Page size", example = "20", requiredMode = REQUIRED)
    private Integer pageSize;

    @Schema(description = "Total number of pages", example = "7", requiredMode = REQUIRED)
    private Integer totalPages;
}

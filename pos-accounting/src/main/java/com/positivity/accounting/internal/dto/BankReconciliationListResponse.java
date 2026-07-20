package com.positivity.accounting.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Paginated list of bank reconciliations (Story F2, issue #965). */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Paginated list of bank reconciliations")
public class BankReconciliationListResponse {

    @Schema(description = "Reconciliations on the current page", requiredMode = REQUIRED)
    private List<BankReconciliationResponse> reconciliations;

    @Schema(description = "Total number of matching reconciliations", example = "42", requiredMode = REQUIRED)
    private Long totalElements;

    @Schema(description = "Zero-based page index", example = "0", requiredMode = REQUIRED)
    private Integer pageNumber;

    @Schema(description = "Page size", example = "20", requiredMode = REQUIRED)
    private Integer pageSize;

    @Schema(description = "Total number of pages", example = "3", requiredMode = REQUIRED)
    private Integer totalPages;
}

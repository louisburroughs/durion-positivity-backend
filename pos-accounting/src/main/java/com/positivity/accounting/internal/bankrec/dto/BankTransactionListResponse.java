package com.positivity.accounting.internal.bankrec.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Paginated list of bank transactions (SPEC §6.1; story S2, #2301). */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Paginated list of bank transactions, sorted by transactionDate then bankTransactionId")
public class BankTransactionListResponse {

    @Schema(description = "Transactions on the current page", requiredMode = REQUIRED)
    private List<BankTransactionResponse> transactions;

    @Schema(description = "Total number of matching transactions", example = "240", requiredMode = REQUIRED)
    private Long totalElements;

    @Schema(description = "Zero-based page index", example = "0", requiredMode = REQUIRED)
    private Integer pageNumber;

    @Schema(description = "Page size", example = "50", requiredMode = REQUIRED)
    private Integer pageSize;

    @Schema(description = "Total number of pages", example = "5", requiredMode = REQUIRED)
    private Integer totalPages;
}

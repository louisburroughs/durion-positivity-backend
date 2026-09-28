package com.positivity.accounting.internal.bankrec.dto;

import com.positivity.accounting.internal.bankrec.entity.BankTransaction;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * An imported bank statement line in a reconciliation (Story F2, issue #965). From story S1 (#2300)
 * a statement line is a {@code bank_transaction} of the reconciliation's statement.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Imported bank statement line")
public class BankReconciliationLineResponse {

    @Schema(description = "Statement line id")
    private UUID lineId;

    @Schema(description = "1-based line number in the imported statement", example = "1")
    private Integer lineNumber;

    @Schema(description = "Statement line date", example = "2026-06-15")
    private LocalDate lineDate;

    @Schema(description = "Statement line description", example = "ACH DEPOSIT")
    private String description;

    @Schema(description = "Signed statement amount", example = "1500.0000")
    private BigDecimal amount;

    @Schema(description = "Statement line reference", example = "REF-88213")
    private String reference;

    @Schema(description = "Match status", example = "UNMATCHED")
    private StatementLineApiStatus status;

    @Schema(description = "Match group id when MATCHED; null while UNMATCHED")
    private UUID matchId;

    /**
     * Map a statement's bank transaction to the F2 statement-line shape: the line id is the bank
     * transaction id and the line number its row in the source file.
     *
     * @param matchId the transaction's live match, or null while it is unmatched
     */
    public static BankReconciliationLineResponse from(BankTransaction transaction, UUID matchId) {
        return BankReconciliationLineResponse.builder()
                .lineId(transaction.getBankTransactionId())
                .lineNumber(transaction.getSourceRowNumber())
                .lineDate(transaction.getTransactionDate())
                .description(transaction.getDescription())
                .amount(transaction.getSignedAmount())
                .reference(transaction.getReference())
                .status(StatementLineApiStatus.from(transaction.getStatus()))
                .matchId(matchId)
                .build();
    }
}

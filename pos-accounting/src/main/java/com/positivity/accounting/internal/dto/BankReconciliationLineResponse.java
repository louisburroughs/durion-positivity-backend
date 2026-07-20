package com.positivity.accounting.internal.dto;

import com.positivity.accounting.internal.entity.BankReconciliationLine;
import com.positivity.accounting.internal.enums.BankReconciliationLineStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** An imported bank statement line in a reconciliation (Story F2, issue #965). */
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
    private BankReconciliationLineStatus status;

    @Schema(description = "Match group id when MATCHED; null while UNMATCHED")
    private UUID matchId;

    public static BankReconciliationLineResponse from(BankReconciliationLine line) {
        return BankReconciliationLineResponse.builder()
                .lineId(line.getLineId())
                .lineNumber(line.getLineNumber())
                .lineDate(line.getLineDate())
                .description(line.getDescription())
                .amount(line.getAmount())
                .reference(line.getReference())
                .status(line.getStatus())
                .matchId(line.getMatchId())
                .build();
    }
}

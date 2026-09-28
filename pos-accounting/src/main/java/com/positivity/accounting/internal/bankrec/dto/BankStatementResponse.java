package com.positivity.accounting.internal.bankrec.dto;

import com.positivity.accounting.internal.bankrec.enums.BankStatementStatus;
import com.positivity.accounting.internal.bankrec.enums.SourceKind;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** A bank statement header with its counts and reconciliation links (SPEC §3.1, §6.1; story S2, #2301). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "A committed bank statement: header, gap acknowledgement, counts and reconciliation links")
public class BankStatementResponse {

    @Schema(description = "Statement id")
    private UUID statementId;

    @Schema(description = "GL bank account id")
    private UUID glAccountId;

    @Schema(description = "GL account code", example = "1000")
    private String accountCode;

    @Schema(description = "GL account name", example = "Cash")
    private String accountName;

    @Schema(description = "Where the statement came from", example = "MANUAL_ENTRY")
    private SourceKind sourceKind;

    @Schema(description = "Provenance reference: the import id for a file; null for manual entry")
    private UUID sourceRef;

    @Schema(description = "Provenance label of the source", example = "csv-v1")
    private String connectorCode;

    @Schema(description = "The bank's own statement number", example = "2026-09")
    private String statementRef;

    @Schema(description = "First day of the window", example = "2026-09-01")
    private LocalDate startDate;

    @Schema(description = "Last day of the window", example = "2026-09-30")
    private LocalDate endDate;

    @Schema(description = "Opening balance as printed", example = "12345.6700")
    private BigDecimal openingBalance;

    @Schema(description = "Closing balance as printed", example = "12840.1200")
    private BigDecimal closingBalance;

    @Schema(description = "Sum of the statement's signed amounts at commit", example = "494.4500")
    private BigDecimal activityTotal;

    @Schema(description = "ISO 4217 code of the amounts", example = "USD")
    private String currency;

    @Schema(description = "The justification that acknowledged a discontinuity and moved the account baseline")
    private String gapAcknowledgement;

    @Schema(description = "Who acknowledged the gap")
    private String gapAcknowledgedBy;

    @Schema(description = "When the gap was acknowledged")
    private Instant gapAcknowledgedAt;

    @Schema(description = "Statement status", example = "COMMITTED")
    private BankStatementStatus status;

    @Schema(description = "The statement that superseded this one, when SUPERSEDED")
    private UUID supersededByStatementId;

    @Schema(description = "Bank transactions the statement carries", example = "12")
    private Long bankTransactionCount;

    @Schema(description = "Of those, rows still flagged POSSIBLE_DUPLICATE", example = "0")
    private Long possibleDuplicateCount;

    @Schema(description = "Rows the commit updated through their source id rather than created", example = "0")
    private Long modifiedCount;

    @Schema(description = "Reconciliations of this statement")
    private List<ReconciliationLink> reconciliations;

    @Schema(description = "When the statement was committed")
    private Instant createdAt;

    @Schema(description = "Who committed the statement")
    private String createdBy;

    @Schema(description = "True when this response replays an earlier request with the same requestId")
    private boolean replayed;

    /** A reconciliation of the statement. */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(name = "BankStatementReconciliationLink", description = "A reconciliation of the statement")
    public static class ReconciliationLink {

        @Schema(description = "Reconciliation id")
        private UUID reconciliationId;

        @Schema(description = "Reconciliation status", example = "IN_PROGRESS")
        private String status;
    }
}

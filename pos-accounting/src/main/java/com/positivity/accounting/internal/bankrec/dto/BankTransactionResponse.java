package com.positivity.accounting.internal.bankrec.dto;

import com.positivity.accounting.internal.bankrec.enums.BankTransactionStatus;
import com.positivity.accounting.internal.bankrec.enums.FeedChange;
import com.positivity.accounting.internal.bankrec.enums.SettlementState;
import com.positivity.accounting.internal.bankrec.enums.SourceKind;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** One bank transaction with its provenance and links (SPEC §3.2, §6.1; story S2, #2301). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "A bank transaction: what the bank reported, its provenance, dedupe state and links")
public class BankTransactionResponse {

    @Schema(description = "Bank transaction id")
    private UUID bankTransactionId;

    @Schema(description = "GL bank account id")
    private UUID glAccountId;

    @Schema(description = "GL account code", example = "1000")
    private String accountCode;

    @Schema(description = "GL account name", example = "Cash")
    private String accountName;

    @Schema(description = "The statement that carried the row; null for a feed row")
    private UUID statementId;

    @Schema(description = "Where the row came from", example = "MANUAL_ENTRY")
    private SourceKind sourceKind;

    @Schema(description = "Provenance reference: the import id for a file; null for manual entry")
    private UUID sourceRef;

    @Schema(description = "Provenance label of the source", example = "csv-v1")
    private String connectorCode;

    @Schema(description = "The source's id for the transaction, when it has one")
    private String sourceTransactionId;

    @Schema(description = "1-based position in the source", example = "3")
    private Integer sourceRowNumber;

    @Schema(description = "The pending row this posted row replaced")
    private UUID supersedesBankTransactionId;

    @Schema(description = "PENDING rows are visible but never matchable", example = "POSTED")
    private SettlementState settlementState;

    @Schema(description = "The bank's posting date", example = "2026-09-02")
    private LocalDate transactionDate;

    @Schema(description = "Authorization date, when the source knows it")
    private LocalDate authorizedDate;

    @Schema(description = "Signed amount, positive = money into the account", example = "500.0000")
    private BigDecimal signedAmount;

    @Schema(description = "ISO 4217 code", example = "USD")
    private String currency;

    @Schema(description = "Description as delivered", example = "ACH DEPOSIT ACME")
    private String description;

    @Schema(description = "The first description the source delivered, when it later changed")
    private String originalDescription;

    @Schema(description = "Upper-cased, punctuation-stripped copy used only for dedupe", example = "ACH DEPOSIT ACME")
    private String normalizedDescription;

    @Schema(description = "The bank's reference", example = "DEP-1")
    private String reference;

    @Schema(description = "Check number", example = "1042")
    private String checkNumber;

    @Schema(description = "Counterparty, when the source names one")
    private String counterpartyName;

    @Schema(description = "The source's category, informational only")
    private String categoryHint;

    @Schema(description = "SHA-256 dedupe fingerprint")
    private String fingerprint;

    @Schema(description = "Row status", example = "UNMATCHED")
    private BankTransactionStatus status;

    @Schema(description = "The earlier row this one may duplicate, or was confirmed to duplicate")
    private UUID duplicateOfBankTransactionId;

    @Schema(description = "True when a feed delivered the row inside a FINALIZED reconciliation window (phase 2)")
    private boolean arrivedAfterApproval;

    @Schema(description = "Why the row was excluded")
    private String exclusionReason;

    @Schema(description = "Who excluded the row")
    private String excludedBy;

    @Schema(description = "When the row was excluded")
    private Instant excludedAt;

    @Schema(description = "The last change the source reported", example = "ADDED")
    private FeedChange feedChange;

    @Schema(description = "When the source first reported the row")
    private Instant firstObservedAt;

    @Schema(description = "When the source last reported the row")
    private Instant lastObservedAt;

    @Schema(description = "When the source removed the row (phase 2)")
    private Instant removedAt;

    @Schema(description = "The active reconciliation match holding the row; null until matching exists (S4)")
    private UUID matchId;

    @Schema(description = "The open outstanding item holding the row; null until outstanding items exist (S4)")
    private UUID outstandingItemId;

    @Schema(description = "Optimistic-lock version; send it back on a review, exclude or restore", example = "0")
    private Long version;
}

package com.positivity.accounting.internal.bankfeed.file.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.accounting.internal.bankfeed.file.enums.BankImportRowStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** One row of an import (SPEC §3.3, §4.4; story S3, #2302). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "One row of a statement-file import: raw cells, parsed values, status and duplicate hints")
public class BankImportRowResponse {

    @Schema(description = "Row id", requiredMode = REQUIRED)
    private UUID rowId;

    @Schema(description = "The import", requiredMode = REQUIRED)
    private UUID importId;

    @Schema(
            description = "1-based position among the file's data rows; the transaction's sourceRowNumber",
            example = "12",
            requiredMode = REQUIRED)
    private Integer rowNumber;

    @Schema(description = "Column label → cell text as read from the file; never rewritten", requiredMode = REQUIRED)
    private Map<String, Object> rawValues;

    @Schema(description = "Parsed (or corrected) posting date", example = "2026-09-14")
    private LocalDate date;

    @Schema(description = "Parsed (or corrected) amount, positive = cash in", example = "-15.0000")
    private BigDecimal signedAmount;

    @Schema(description = "Parsed (or corrected) description", example = "MONTHLY FEE")
    private String description;

    @Schema(description = "Bank reference", example = "REF-1")
    private String reference;

    @Schema(description = "Check number", example = "1001")
    private String checkNumber;

    @Schema(description = "The bank's transaction id, when the file carries one", example = "TX-88121")
    private String sourceTransactionId;

    @Schema(description = "Row status", requiredMode = REQUIRED)
    private BankImportRowStatus rowStatus;

    @Schema(
            description = "Why the row is REJECTED: DATE_UNPARSEABLE, AMOUNT_UNPARSEABLE, AMOUNT_ZERO,"
                    + " AMOUNT_AND_DEBIT_CREDIT_BOTH, REQUIRED_COLUMN_MISSING, DATE_OUTSIDE_STATEMENT"
                    + " or AMOUNT_PRECISION_EXCEEDS_CURRENCY",
            example = "DATE_UNPARSEABLE")
    private String rejectionCode;

    @Schema(description = "The line and value that failed", example = "Line 13: unparseable date '2026-13-45'")
    private String rejectionDetail;

    @Schema(description = "The §3.2 dedupe fingerprint (SHA-256 hex)")
    private String fingerprint;

    @Schema(description = "A stored bank transaction this row's fingerprint collides with")
    private UUID duplicateOfBankTransactionId;

    @Schema(description = "Another row of the file this row's fingerprint collides with", example = "11")
    private Integer duplicateOfRowNumber;

    @Schema(
            description = "A human's answer to the collision",
            allowableValues = {"DISTINCT", "DUPLICATE"})
    private String duplicateDecision;

    @Schema(description = "What the preparer corrected")
    private Map<String, Object> correctedValues;

    @Schema(description = "Who corrected, skipped or decided the row")
    private String correctedBy;

    @Schema(description = "When the row was corrected, skipped or decided")
    private Instant correctedAt;

    @Schema(description = "Why the row was skipped")
    private String skipReason;

    @Schema(description = "The bank transaction the row became at commit")
    private UUID bankTransactionId;
}

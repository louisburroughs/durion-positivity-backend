package com.positivity.accounting.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.Builder;
import lombok.Value;

/**
 * Response DTO for a vendor bill match candidate.
 * Presents candidate details to the UI for manual selection during ambiguous
 * matching.
 */
@Value
@Builder
@Schema(description = "Vendor bill match candidate presented for manual selection during ambiguous matching")
public class VendorBillMatchCandidateResponse {

    /** Candidate record ID (used for selection). */
    @Schema(
            description = "Candidate record identifier (used for selection)",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    UUID candidateId;

    /** The invoice event that triggered the ambiguous match. */
    @Schema(
            description = "Identifier of the invoice event that triggered the ambiguous match",
            example = "01960003-0000-7000-8000-000000000002",
            requiredMode = REQUIRED)
    UUID invoiceEventId;

    /** The candidate vendor bill ID. */
    @Schema(
            description = "Identifier of the candidate vendor bill",
            example = "01960003-0000-7000-8000-000000000003",
            requiredMode = REQUIRED)
    UUID vendorBillId;

    /** Vendor ID. */
    @Schema(
            description = "Identifier of the vendor",
            example = "01960003-0000-7000-8000-000000000004",
            requiredMode = NOT_REQUIRED)
    UUID vendorId;

    /** Bill number for display. */
    @Schema(description = "Bill number for display", example = "BILL-2026-00456", requiredMode = NOT_REQUIRED)
    String billNumber;

    /** Bill total amount. */
    @Schema(description = "Total amount of the candidate bill", example = "1250.00", requiredMode = NOT_REQUIRED)
    BigDecimal billTotalAmount;

    /** Composite match score (0–100). */
    @Schema(description = "Composite match score (0-100)", example = "85", requiredMode = NOT_REQUIRED)
    int matchScore;

    /** Score breakdown details. */
    @Schema(
            description = "Human-readable breakdown of the match score",
            example = "amount:40, date:25, vendor:20",
            requiredMode = NOT_REQUIRED)
    String scoreBreakdown;

    /** Whether this candidate has been resolved. */
    @Schema(description = "Whether this candidate has been resolved", example = "false", requiredMode = NOT_REQUIRED)
    boolean resolved;

    /** Whether this candidate was the selected one. */
    @Schema(description = "Whether this candidate was the selected one", example = "false", requiredMode = NOT_REQUIRED)
    boolean selected;

    /** When the candidate record was created. */
    @Schema(
            description = "Timestamp when the candidate record was created (ISO 8601)",
            example = "2026-06-18T08:00:00Z",
            requiredMode = NOT_REQUIRED)
    Instant createdAt;
}

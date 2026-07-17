package com.positivity.warranty.internal.dto;

import com.positivity.warranty.internal.enums.ClaimDecision;
import com.positivity.warranty.internal.enums.ClaimStatus;
import com.positivity.warranty.internal.enums.ClaimType;
import com.positivity.warranty.internal.enums.EligibilityResult;
import com.positivity.warranty.internal.enums.PartReturnDisposition;
import com.positivity.warranty.internal.enums.PartReturnStatus;
import com.positivity.warranty.internal.enums.ReimbursementStatus;
import com.positivity.warranty.internal.enums.SettlementStatus;
import com.positivity.warranty.internal.enums.SettlementType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Full claim detail (PRD §8 {@code GET /v1/warranty/claims/{id}}): the aggregate root plus its
 * lines and the child records keyed by {@code claimId} — settlements, vendor reimbursements,
 * part returns, status history, and notes. Child views are nested records so the claim detail
 * contract lives in one file; the settlement/reimbursement/part-return slices own their richer
 * standalone DTOs.
 */
@Schema(description = "Full warranty claim detail")
public record ClaimResponse(
        UUID id,
        String claimCode,
        ClaimType claimType,
        ClaimStatus status,
        UUID locationId,
        UUID customerId,
        UUID vehicleId,

        @Schema(description = "VIN snapshot frozen at intake")
        String vin,

        @Schema(description = "Odometer snapshot frozen at intake")
        Long odometerAtClaim,

        String odometerUnit,
        UUID originWorkorderId,
        UUID originInvoiceId,
        LocalDate originSaleDate,
        Boolean originUnverified,
        UUID providerId,
        UUID policyId,
        UUID registrationId,
        String failureDescription,
        LocalDate failureDate,
        List<String> photoEvidenceUrls,
        EligibilityResult eligibilityResult,
        List<Map<String, Object>> eligibilityReasons,
        Map<String, Object> suggestedOutcome,
        ClaimDecision decision,
        String decisionReason,
        String decidedBy,
        Instant decidedAt,
        Boolean overrodeSuggestion,
        List<ClaimLineResponse> lines,
        List<SettlementView> settlements,
        List<ReimbursementView> reimbursements,
        List<PartReturnView> partReturns,
        List<StatusHistoryView> history,
        List<NoteView> notes,
        Instant createdAt,
        String createdBy,
        Instant updatedAt,
        Long version) {

    /** Settlement child view (PRD §3.6). */
    @Schema(description = "Claim settlement")
    public record SettlementView(
            UUID id,
            SettlementType settlementType,
            SettlementStatus status,
            UUID replacementWorkorderId,
            UUID invoiceId,
            UUID invoiceAdjustmentId,
            UUID refundRecordId,
            BigDecimal coveredAmount,
            BigDecimal customerAmount,
            Instant createdAt) {}

    /** Vendor reimbursement child view (PRD §3.7). */
    @Schema(description = "Vendor reimbursement")
    public record ReimbursementView(
            UUID id,
            UUID providerId,
            ReimbursementStatus status,
            BigDecimal amountRequested,
            BigDecimal amountApproved,
            String vendorClaimReference,
            Instant submittedAt,
            String submittedBy,
            Instant resolvedAt,
            String creditReference,
            Instant creditReceivedAt,
            String notes) {}

    /** Part return (RMA) child view (PRD §3.8). */
    @Schema(description = "Defective-part return (RMA)")
    public record PartReturnView(
            UUID id,
            UUID claimLineId,
            String rmaNumber,
            PartReturnDisposition disposition,
            PartReturnStatus status,
            String carrier,
            String trackingNumber,
            Instant shippedAt,
            String holdLocationNote) {}

    /** Append-only status transition (PRD §3.9). */
    @Schema(description = "Claim status transition")
    public record StatusHistoryView(
            UUID id, ClaimStatus fromStatus, ClaimStatus toStatus, String actor, String reason, Instant createdAt) {}

    /** Append-only staff note (PRD §3.9). */
    @Schema(description = "Staff note")
    public record NoteView(UUID id, String note, String createdBy, Instant createdAt) {}
}

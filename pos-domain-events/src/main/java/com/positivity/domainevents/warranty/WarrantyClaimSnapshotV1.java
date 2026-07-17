package com.positivity.domainevents.warranty;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Fact: the current full state of a warranty claim aggregate (ADR-0044 R3 read-replica path).
 *
 * <p>Published by pos-warranty on {@code warranty.events.v1} with
 * {@code eventType = "warranty.claim.snapshot"} after every claim-visible mutation (intake,
 * edits, submit, decision, cancel, close, each settlement execution, each reimbursement
 * transition, each part-return create/update). Unlike the granular money-lifecycle events, the
 * snapshot carries the whole aggregate — header, lines, settlements, reimbursements, and part
 * returns — so consumers can build event-fed read replicas of warranty claims without any
 * synchronous client into pos-warranty. Enum-typed owner fields are carried as {@code String}
 * names so this contract never depends on pos-warranty internals. Per-aggregate ordering is
 * guaranteed by the envelope's record key ({@code claimId}); consumers should treat the latest
 * snapshot per {@code claimId} (by {@code aggregateVersion}) as authoritative.
 *
 * @param claimId warranty claim identifier (also the envelope aggregateId)
 * @param claimCode human-readable claim code, e.g. {@code WC-2026-000123}
 * @param claimType owner {@code ClaimType} name, e.g. {@code MANUFACTURER_DEFECT}
 * @param status owner {@code ClaimStatus} name, e.g. {@code SETTLED}
 * @param locationId location the claim belongs to (null when the claim carries no location)
 * @param customerId owning customer party
 * @param vehicleId vehicle the claim is about (null for non-vehicle claims)
 * @param vin VIN snapshot frozen at intake (null when unavailable)
 * @param odometerAtClaim odometer snapshot frozen at intake (null when unavailable)
 * @param odometerUnit odometer unit snapshot, e.g. {@code MILES} (null when unavailable)
 * @param originWorkorderId originating workorder (null when the origin is not a workorder)
 * @param originInvoiceId originating invoice (null when the origin is not an invoice)
 * @param originSaleDate original sale date (null when unknown)
 * @param originUnverified true for walk-ins whose original sale could not be located
 * @param providerId covering warranty provider (null until eligibility selects one)
 * @param policyId covering warranty policy (null until eligibility selects one)
 * @param registrationId linked warranty registration (null when none)
 * @param failureDescription reported failure description (null when not captured)
 * @param failureDate reported failure date (null when not captured)
 * @param eligibilityResult owner {@code EligibilityResult} name (null until evaluated)
 * @param decision owner {@code ClaimDecision} name (null until decided or after appeal)
 * @param decisionReason decision/override reason (null when none was required)
 * @param decidedBy adjudicating user (null until decided)
 * @param decidedAt when the claim was decided (null until decided)
 * @param overrodeSuggestion true when the human decision contradicted the computed suggestion
 * @param createdAt when the claim was created
 * @param updatedAt when the claim was last modified
 * @param version claim aggregate version at snapshot time
 * @param lines all claim lines (empty for a lineless draft)
 * @param settlements all customer settlements executed so far (empty until settled)
 * @param reimbursements all vendor reimbursements (empty until submitted)
 * @param partReturns all defective-part RMAs (empty until requested)
 * @param snapshotAt when this snapshot was taken
 */
public record WarrantyClaimSnapshotV1(
        @NonNull UUID claimId,
        @NonNull String claimCode,
        @NonNull String claimType,
        @NonNull String status,
        @Nullable UUID locationId,
        @NonNull UUID customerId,
        @Nullable UUID vehicleId,
        @Nullable String vin,
        @Nullable Long odometerAtClaim,
        @Nullable String odometerUnit,
        @Nullable UUID originWorkorderId,
        @Nullable UUID originInvoiceId,
        @Nullable LocalDate originSaleDate,
        boolean originUnverified,
        @Nullable UUID providerId,
        @Nullable UUID policyId,
        @Nullable UUID registrationId,
        @Nullable String failureDescription,
        @Nullable LocalDate failureDate,
        @Nullable String eligibilityResult,
        @Nullable String decision,
        @Nullable String decisionReason,
        @Nullable String decidedBy,
        @Nullable Instant decidedAt,
        boolean overrodeSuggestion,
        @Nullable Instant createdAt,
        @Nullable Instant updatedAt,
        long version,
        @NonNull List<Line> lines,
        @NonNull List<Settlement> settlements,
        @NonNull List<Reimbursement> reimbursements,
        @NonNull List<PartReturn> partReturns,
        @NonNull Instant snapshotAt) {

    public static final String EVENT_TYPE = "warranty.claim.snapshot";
    public static final int SCHEMA_VERSION = 1;

    /**
     * One failed part or service on the claim.
     *
     * @param claimLineId claim line identifier
     * @param sourceType owner {@code LineSourceType} name, e.g. {@code INVOICE_LINE}
     * @param sourceId originating invoice/workorder id (null for MANUAL lines)
     * @param sourceLineId originating invoice/workorder line id (null for MANUAL lines)
     * @param productEntityId catalog product reference (null when uncataloged)
     * @param sku SKU snapshot frozen at intake (null when unknown)
     * @param description description snapshot frozen at intake (null when unknown)
     * @param serialNumber serial number of the failed unit (null when not serialized)
     * @param quantity claimed quantity
     * @param originalUnitPrice unit price snapshot frozen at intake (null when unknown)
     * @param prorationPct computed credit fraction in [0, 1] (null until computed)
     * @param amountRequested computed/requested line amount (null until computed)
     * @param amountApproved adjudicated line amount (null until approved)
     * @param lineDisposition owner {@code LineDisposition} name (null when unset)
     */
    public record Line(
            @NonNull UUID claimLineId,
            @NonNull String sourceType,
            @Nullable UUID sourceId,
            @Nullable UUID sourceLineId,
            @Nullable UUID productEntityId,
            @Nullable String sku,
            @Nullable String description,
            @Nullable String serialNumber,
            @NonNull BigDecimal quantity,
            @Nullable BigDecimal originalUnitPrice,
            @Nullable BigDecimal prorationPct,
            @Nullable BigDecimal amountRequested,
            @Nullable BigDecimal amountApproved,
            @Nullable String lineDisposition) {}

    /**
     * One customer settlement executed on the claim.
     *
     * @param settlementId claim settlement record identifier
     * @param settlementType owner {@code SettlementType} name, e.g. {@code REFUND}
     * @param status owner {@code SettlementStatus} name, e.g. {@code COMPLETED}
     * @param coveredAmount amount covered by the warranty (null when not applicable)
     * @param customerAmount amount payable by the customer (null when not applicable)
     * @param invoiceId customer-pay invoice back-reference (null when none was created)
     * @param invoiceAdjustmentId pos-invoice adjustment back-reference (null when none)
     * @param refundRecordId refund record back-reference (null when none was created)
     * @param replacementWorkorderId replacement workorder back-reference (null when none)
     */
    public record Settlement(
            @NonNull UUID settlementId,
            @NonNull String settlementType,
            @NonNull String status,
            @Nullable BigDecimal coveredAmount,
            @Nullable BigDecimal customerAmount,
            @Nullable UUID invoiceId,
            @Nullable UUID invoiceAdjustmentId,
            @Nullable UUID refundRecordId,
            @Nullable UUID replacementWorkorderId) {}

    /**
     * One vendor reimbursement on the claim.
     *
     * @param reimbursementId vendor reimbursement record identifier
     * @param providerId warranty provider the request targets (null when unassigned)
     * @param status owner {@code ReimbursementStatus} name, e.g. {@code SUBMITTED}
     * @param amountRequested amount requested from the provider (null until submitted)
     * @param amountApproved amount the provider approved (null until resolved)
     * @param vendorClaimReference provider-side claim reference (null until assigned)
     */
    public record Reimbursement(
            @NonNull UUID reimbursementId,
            @Nullable UUID providerId,
            @NonNull String status,
            @Nullable BigDecimal amountRequested,
            @Nullable BigDecimal amountApproved,
            @Nullable String vendorClaimReference) {}

    /**
     * One defective-part RMA on the claim.
     *
     * @param partReturnId part return record identifier
     * @param claimLineId claim line the returned part belongs to
     * @param status owner {@code PartReturnStatus} name, e.g. {@code SHIPPED}
     * @param disposition owner {@code PartReturnDisposition} name (null when unset)
     * @param rmaNumber vendor-issued RMA number (null until assigned)
     */
    public record PartReturn(
            @NonNull UUID partReturnId,
            @NonNull UUID claimLineId,
            @NonNull String status,
            @Nullable String disposition,
            @Nullable String rmaNumber) {}
}

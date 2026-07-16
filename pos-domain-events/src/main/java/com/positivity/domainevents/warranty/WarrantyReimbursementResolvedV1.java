package com.positivity.domainevents.warranty;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Fact: a vendor reimbursement request reached a terminal provider decision (PRD
 * warranty-claims §9.3).
 *
 * <p>Published by pos-warranty on {@code warranty.events.v1} with
 * {@code eventType = "warranty.reimbursement.resolved"} when the provider approves (fully or
 * partially), denies, or credits a previously submitted reimbursement, or when the back office
 * writes it off (so consumers stop expecting the credit). {@code status} is the owner
 * {@code ReimbursementStatus} name; {@code amountApproved} and {@code creditReference}
 * are null when the resolution carries no approved amount / credit (e.g. a denial or write-off).
 *
 * @param claimId warranty claim identifier (also the envelope aggregateId)
 * @param claimCode human-readable claim code, e.g. {@code WC-2026-000123}
 * @param reimbursementId vendor reimbursement record identifier
 * @param providerId warranty provider that resolved the request
 * @param apVendorId accounts-payable vendor linked to the provider (null when unlinked)
 * @param status owner {@code ReimbursementStatus} name, e.g. {@code APPROVED}
 * @param amountApproved amount the provider approved (null when none)
 * @param creditReference provider credit/payment reference (null when none)
 * @param resolvedAt when the provider decision was recorded
 */
public record WarrantyReimbursementResolvedV1(
        @NonNull UUID claimId,
        @NonNull String claimCode,
        @NonNull UUID reimbursementId,
        @NonNull UUID providerId,
        @Nullable UUID apVendorId,
        @NonNull String status,
        @Nullable BigDecimal amountApproved,
        @Nullable String creditReference,
        @NonNull Instant resolvedAt) {

    public static final String EVENT_TYPE = "warranty.reimbursement.resolved";
    public static final int SCHEMA_VERSION = 1;
}

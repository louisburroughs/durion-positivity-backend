package com.positivity.domainevents.warranty;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Fact: a vendor reimbursement request was submitted to a warranty provider (PRD
 * warranty-claims §9.3).
 *
 * <p>Published by pos-warranty on {@code warranty.events.v1} with
 * {@code eventType = "warranty.reimbursement.submitted"} when a reimbursement request for a
 * provider-backed claim is sent to the provider. {@code apVendorId} is the accounts-payable
 * vendor linked to the provider (null when the provider has no AP vendor link);
 * {@code vendorClaimReference} is the provider's own claim reference (null until the provider
 * assigns one).
 *
 * @param claimId warranty claim identifier (also the envelope aggregateId)
 * @param claimCode human-readable claim code, e.g. {@code WC-2026-000123}
 * @param reimbursementId vendor reimbursement record identifier
 * @param providerId warranty provider the request was submitted to
 * @param apVendorId accounts-payable vendor linked to the provider (null when unlinked)
 * @param amountRequested reimbursement amount requested from the provider
 * @param vendorClaimReference provider-side claim reference (null until assigned)
 * @param submittedAt when the request was submitted
 */
public record WarrantyReimbursementSubmittedV1(
        @NonNull UUID claimId,
        @NonNull String claimCode,
        @NonNull UUID reimbursementId,
        @NonNull UUID providerId,
        @Nullable UUID apVendorId,
        @NonNull BigDecimal amountRequested,
        @Nullable String vendorClaimReference,
        @NonNull Instant submittedAt) {

    public static final String EVENT_TYPE = "warranty.reimbursement.submitted";
    public static final int SCHEMA_VERSION = 1;
}

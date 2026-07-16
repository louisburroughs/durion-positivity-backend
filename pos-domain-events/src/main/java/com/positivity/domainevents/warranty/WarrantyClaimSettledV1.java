package com.positivity.domainevents.warranty;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Fact: a warranty claim settlement was executed (PRD warranty-claims §9.3).
 *
 * <p>Published by pos-warranty on {@code warranty.events.v1} with
 * {@code eventType = "warranty.claim.settled"} once per executed settlement. Carries the
 * split between the warranty-covered amount and the customer-pay remainder, plus the
 * downstream document references the settlement produced ({@code invoiceId} for the
 * customer-pay invoice, {@code refundRecordId} for refunds, {@code replacementWorkorderId}
 * for replacement work) — each null when that outcome does not apply to the settlement type.
 *
 * @param claimId warranty claim identifier (also the envelope aggregateId)
 * @param claimCode human-readable claim code, e.g. {@code WC-2026-000123}
 * @param customerId owning customer party
 * @param locationId location the claim was settled at (null when the claim carries no location)
 * @param settlementId claim settlement record identifier
 * @param settlementType owner {@code SettlementType} name, e.g. {@code REPAIR}
 * @param coveredAmount amount covered by the warranty
 * @param customerAmount amount payable by the customer
 * @param invoiceId customer-pay invoice back-reference (null when none was created)
 * @param refundRecordId refund record back-reference (null when none was created)
 * @param replacementWorkorderId replacement workorder back-reference (null when none was created)
 * @param settledAt when the settlement was executed
 */
public record WarrantyClaimSettledV1(
        @NonNull UUID claimId,
        @NonNull String claimCode,
        @NonNull UUID customerId,
        @Nullable UUID locationId,
        @NonNull UUID settlementId,
        @NonNull String settlementType,
        @NonNull BigDecimal coveredAmount,
        @NonNull BigDecimal customerAmount,
        @Nullable UUID invoiceId,
        @Nullable UUID refundRecordId,
        @Nullable UUID replacementWorkorderId,
        @NonNull Instant settledAt) {

    public static final String EVENT_TYPE = "warranty.claim.settled";
    public static final int SCHEMA_VERSION = 1;
}

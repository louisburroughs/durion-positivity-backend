package com.positivity.domainevents.warranty;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Fact: a requested defective-part return was shipped back to the provider (PRD
 * warranty-claims §9.3).
 *
 * <p>Published by pos-warranty on {@code warranty.events.v1} with
 * {@code eventType = "warranty.part-return.shipped"} when the part return is marked shipped.
 * Carrier and tracking number are recorded when available (null otherwise, e.g. courier
 * pickup without tracking).
 *
 * @param claimId warranty claim identifier (also the envelope aggregateId)
 * @param claimLineId claim line the returned part belongs to
 * @param partReturnId part return record identifier
 * @param carrier shipping carrier name (null when not recorded)
 * @param trackingNumber carrier tracking number (null when not recorded)
 * @param shippedAt when the return shipment was sent
 */
public record WarrantyPartReturnShippedV1(
        @NonNull UUID claimId,
        @NonNull UUID claimLineId,
        @NonNull UUID partReturnId,
        @Nullable String carrier,
        @Nullable String trackingNumber,
        @NonNull Instant shippedAt) {

    public static final String EVENT_TYPE = "warranty.part-return.shipped";
    public static final int SCHEMA_VERSION = 1;
}

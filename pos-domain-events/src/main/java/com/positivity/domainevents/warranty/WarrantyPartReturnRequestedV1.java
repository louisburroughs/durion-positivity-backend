package com.positivity.domainevents.warranty;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Fact: a defective-part return was requested for a warranty claim line (PRD warranty-claims
 * §9.3).
 *
 * <p>Published by pos-warranty on {@code warranty.events.v1} with
 * {@code eventType = "warranty.part-return.requested"} when a provider requires the failed
 * part back (or its scrap/hold handling is recorded). {@code disposition} is the owner
 * {@code PartReturnDisposition} name; {@code productEntityId}/{@code serialNumber} identify
 * the part when known.
 *
 * @param claimId warranty claim identifier (also the envelope aggregateId)
 * @param claimLineId claim line the returned part belongs to
 * @param partReturnId part return record identifier
 * @param productEntityId pos-catalog product reference (null when unknown)
 * @param serialNumber serial number of the failed part (null when not serialized)
 * @param disposition owner {@code PartReturnDisposition} name, e.g. {@code RETURN_TO_VENDOR}
 * @param requestedAt when the return was requested
 */
public record WarrantyPartReturnRequestedV1(
        @NonNull UUID claimId,
        @NonNull UUID claimLineId,
        @NonNull UUID partReturnId,
        @Nullable UUID productEntityId,
        @Nullable String serialNumber,
        @NonNull String disposition,
        @NonNull Instant requestedAt) {

    public static final String EVENT_TYPE = "warranty.part-return.requested";
    public static final int SCHEMA_VERSION = 1;
}

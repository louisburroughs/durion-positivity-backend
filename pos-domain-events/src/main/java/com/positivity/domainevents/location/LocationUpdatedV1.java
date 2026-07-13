package com.positivity.domainevents.location;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Fact: a location (site/shop) was created or changed (ADR-0044 §6, issue #890 Phase 4.2).
 *
 * <p>Published by pos-location on {@code location.events.v1} with
 * {@code eventType = "location.location.updated"}. Consumers maintain read-only
 * {@code ext_location} replicas serving rosters (pos-inventory, pos-people), site defaults
 * (pos-inventory) and the tax destination address (pos-invoice, pos-workorder).
 *
 * <p>The address fields are the tax-jurisdiction inputs previously served by
 * {@code GET /v1/locations/{id}} — ADR-0044 explicitly reversed the older "never replicate
 * address data" rule for this feed; consumers must still run ADR-0021 address validation on
 * replica data before tax calculation.
 *
 * @param locationId location identifier (also the envelope aggregateId)
 * @param name display name
 * @param code unique short code
 * @param status owner status string, e.g. {@code ACTIVE}
 * @param active convenience flag mirroring the owner's {@code is_active} column
 * @param locationType location type name (null when untyped)
 * @param hrLocationId external HR system location reference
 * @param timezone IANA timezone id
 * @param addressLine1 street address line 1
 * @param addressLine2 street address line 2
 * @param city city
 * @param region state/province code
 * @param postalCode postal code (required for tax jurisdiction determination)
 * @param country country code (required for tax jurisdiction determination)
 * @param defaultStagingLocationId site default staging storage location
 * @param defaultQuarantineLocationId site default quarantine storage location
 * @param createdAt owner row creation timestamp
 * @param updatedAt owner row last-update timestamp
 */
public record LocationUpdatedV1(
        @NonNull UUID locationId,
        @Nullable String name,
        @Nullable String code,
        @Nullable String status,
        boolean active,
        @Nullable String locationType,
        @Nullable String hrLocationId,
        @Nullable String timezone,
        @Nullable String addressLine1,
        @Nullable String addressLine2,
        @Nullable String city,
        @Nullable String region,
        @Nullable String postalCode,
        @Nullable String country,
        @Nullable UUID defaultStagingLocationId,
        @Nullable UUID defaultQuarantineLocationId,
        @Nullable Instant createdAt,
        @Nullable Instant updatedAt) {

    public static final String EVENT_TYPE = "location.location.updated";

    public LocationUpdatedV1 {
        if (locationId == null) {
            throw new IllegalArgumentException("locationId must not be null");
        }
    }
}

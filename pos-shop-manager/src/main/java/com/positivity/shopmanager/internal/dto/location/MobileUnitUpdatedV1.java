package com.positivity.shopmanager.internal.dto.location;

import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Fact: a mobile service unit was created or changed (ADR-0044 §6, #1658).
 *
 * <p>Expected on {@code location.events.v1} with
 * {@code eventType = "location.mobile-unit.updated"}. pos-shop-manager maintains a read-only
 * {@code ext_mobile_unit} replica from it so the shop dashboard can list idle units and name the
 * unit a mobile workorder occupies.
 *
 * <p>The owner's {@code MobileUnitEntity} has no boolean active flag either — see
 * {@link BayUpdatedV1}. Its {@code status} is a free-text column whose in-use values are
 * {@code ACTIVE} and {@code INACTIVE}, which is why the consumer allow-lists {@code ACTIVE} rather
 * than deny-listing the statuses it happens to know about today.
 *
 * @param mobileUnitId mobile-unit identifier (also the envelope aggregateId)
 * @param baseLocationId the site the unit is based at and dispatched from
 * @param name display name, e.g. {@code Van 3}
 * @param status the owner's status string, e.g. {@code ACTIVE} or {@code INACTIVE}
 */
public record MobileUnitUpdatedV1(
        @NonNull UUID mobileUnitId,
        @Nullable UUID baseLocationId,
        @Nullable String name,
        @Nullable String status) {

    public static final String EVENT_TYPE = "location.mobile-unit.updated";
    public static final int SCHEMA_VERSION = 1;

    public MobileUnitUpdatedV1 {
        if (mobileUnitId == null) {
            throw new IllegalArgumentException("mobileUnitId must not be null");
        }
    }
}

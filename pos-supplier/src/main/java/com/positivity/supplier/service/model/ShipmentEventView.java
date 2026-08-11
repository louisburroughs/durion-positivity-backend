package com.positivity.supplier.service.model;

import java.time.Instant;
import java.util.Objects;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * One shipment tracking milestone for a vendor order (LEX shipment tracking v1).
 * Placeholder-level for the CAP-317 foundation slice; grows with CAP-322.
 *
 * @param eventCode canonical event code of the tracking milestone; never blank
 * @param carrierEventCode carrier-native event code, verbatim, when stated
 * @param occurredAt when the milestone happened per the carrier/vendor
 * @param reportedAt when the vendor reported the milestone, when stated separately
 * @param orderReference vendor order reference the event belongs to; never blank
 */
public record ShipmentEventView(
        @NonNull String eventCode,
        @Nullable String carrierEventCode,
        @NonNull Instant occurredAt,
        @Nullable Instant reportedAt,
        @NonNull String orderReference) {

    public ShipmentEventView {
        Objects.requireNonNull(eventCode, "eventCode must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        Objects.requireNonNull(orderReference, "orderReference must not be null");
        if (eventCode.isBlank()) {
            throw new IllegalArgumentException("eventCode must not be blank");
        }
        if (orderReference.isBlank()) {
            throw new IllegalArgumentException("orderReference must not be blank");
        }
    }
}

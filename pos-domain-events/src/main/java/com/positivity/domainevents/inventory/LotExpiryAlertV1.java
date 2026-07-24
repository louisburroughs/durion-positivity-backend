package com.positivity.domainevents.inventory;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Fact: a lot entered its alert window or crossed its expiration date (odoo-parity E3, issue
 * #1047; spec §6 E3).
 *
 * <p>Published by pos-inventory on {@code inventory.events.v1} with
 * {@code eventType = "inventory.lot.expiry-alert"} — one fact per lot per transition (this is an
 * occurrence, not a snapshot: the daily {@code LotExpiryScheduler} emits it exactly once per
 * transition and records the emission on the lot so a re-run never re-emits). {@code alertKind}
 * distinguishes the transition:
 * <ul>
 *   <li>{@code EXPIRING} — the lot reached its {@code alertDate} (still on-hand, not yet expired)</li>
 *   <li>{@code EXPIRED} — the lot's {@code expirationDate} passed (dropped from ATP, still on-hand)</li>
 * </ul>
 *
 * @param lotId lot master identifier (aggregate id of the fact)
 * @param sku stock-item identifier of the lot (ledger stock-item string / catalog product id)
 * @param lotNumber vendor/batch lot number
 * @param expirationDate the lot's expiration date
 * @param alertKind {@code EXPIRING} or {@code EXPIRED}
 * @param occurredAt when the alert was raised
 */
public record LotExpiryAlertV1(
        @NonNull UUID lotId,
        @NonNull String sku,
        @NonNull String lotNumber,
        @Nullable LocalDate expirationDate,
        @NonNull String alertKind,
        @NonNull Instant occurredAt) {

    public static final String EVENT_TYPE = "inventory.lot.expiry-alert";
    public static final int SCHEMA_VERSION = 1;

    public LotExpiryAlertV1 {
        if (lotId == null) {
            throw new IllegalArgumentException("lotId must not be null");
        }
        if (sku == null || sku.isBlank()) {
            throw new IllegalArgumentException("sku must not be blank");
        }
        if (lotNumber == null || lotNumber.isBlank()) {
            throw new IllegalArgumentException("lotNumber must not be blank");
        }
        if (alertKind == null || alertKind.isBlank()) {
            throw new IllegalArgumentException("alertKind must not be blank");
        }
        if (occurredAt == null) {
            throw new IllegalArgumentException("occurredAt must not be null");
        }
    }
}

package com.positivity.domainevents.inventory;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Fact: the normalized lead-time estimate for a product changed (ADR-0044 §6, issue #899
 * Phase 5.3).
 *
 * <p>Published by pos-inventory on {@code inventory.events.v1} with
 * {@code eventType = "inventory.lead-time.updated"} whenever a distributor or manufacturer feed
 * ingest updates the normalized supply rows for the product. Consumers (pos-catalog) keep an
 * {@code ext_product_lead_time} replica for product-detail display. Lead time is
 * location-independent — it is derived from supplier feeds, not shop stock.
 *
 * @param productId catalog product identifier
 * @param minDays estimated minimum days to availability
 * @param maxDays estimated maximum days to availability
 * @param displayText preformatted display string, e.g. {@code "3-5 days"}
 * @param source estimate source, e.g. {@code INVENTORY} (distributor) or {@code SUPPLY_CHAIN}
 * @param confidence estimate confidence, e.g. {@code HIGH}/{@code MEDIUM}
 * @param asOf feed timestamp the estimate derives from
 */
public record LeadTimeUpdatedV1(
        @NonNull UUID productId,
        @Nullable Integer minDays,
        @Nullable Integer maxDays,
        @Nullable String displayText,
        @Nullable String source,
        @Nullable String confidence,
        @Nullable Instant asOf) {

    public static final String EVENT_TYPE = "inventory.lead-time.updated";
    public static final int SCHEMA_VERSION = 1;

    public LeadTimeUpdatedV1 {
        if (productId == null) {
            throw new IllegalArgumentException("productId must not be null");
        }
    }
}

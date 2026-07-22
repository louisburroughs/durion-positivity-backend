package com.positivity.domainevents.catalog;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Payload for {@code catalog.product.updated} v1 on {@code catalog.events.v1} (ADR-0044 §6, #924).
 *
 * <p>Published by pos-catalog after every product master-data or lifecycle mutation. Carries the
 * product facts consumers replicate — pos-warranty maintains an {@code ext_catalog} replica for
 * candidate-line product lookup and eligibility (manufacturer / warranty terms), replacing the
 * retired synchronous {@code CatalogClient}.
 *
 * <p>pos-catalog's {@code ProductEntity} has no JPA optimistic-lock {@code @Version}; the envelope's
 * {@code aggregateVersion} therefore carries {@code updatedAt} as epoch millis, which increases per
 * mutation and gives consumers a monotonic stale-event guard.
 *
 * @param productId product identifier (also the envelope aggregateId)
 * @param sku stock-keeping unit (null until assigned)
 * @param name product display name
 * @param manufacturerId manufacturer reference
 * @param manufacturerName manufacturer name snapshot
 * @param manufacturerBrand manufacturer brand snapshot
 * @param categoryId category reference
 * @param category category name snapshot
 * @param warranty product warranty terms (free text)
 * @param manufacturerWarranty manufacturer warranty terms (free text)
 * @param active whether the product is active (soft-delete flag)
 * @param createdAt owner row creation timestamp
 * @param updatedAt owner row last-update timestamp
 */
public record ProductUpdatedV1(
        @NonNull UUID productId,
        @Nullable String sku,
        @Nullable String name,
        @Nullable UUID manufacturerId,
        @Nullable String manufacturerName,
        @Nullable String manufacturerBrand,
        @Nullable UUID categoryId,
        @Nullable String category,
        @Nullable String warranty,
        @Nullable String manufacturerWarranty,
        boolean active,
        @Nullable Instant createdAt,
        @Nullable Instant updatedAt) {

    public static final String EVENT_TYPE = "catalog.product.updated";
    public static final int SCHEMA_VERSION = 1;

    public ProductUpdatedV1 {
        if (productId == null) {
            throw new IllegalArgumentException("productId must not be null");
        }
    }
}

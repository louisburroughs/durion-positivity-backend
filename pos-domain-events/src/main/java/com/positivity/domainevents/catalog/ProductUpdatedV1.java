package com.positivity.domainevents.catalog;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Payload for {@code catalog.product.updated} on {@code catalog.events.v1} (ADR-0044 §6, #924).
 *
 * <p>Published by pos-catalog after every product master-data, lifecycle, UoM, tracking-level, or
 * substitution-group mutation. Carries the product facts consumers replicate — pos-warranty
 * maintains an {@code ext_catalog} replica for candidate-line product lookup and eligibility
 * (manufacturer / warranty terms); pos-inventory replicates the UoM conversion set
 * ({@code ext_product_uom}), tracking level, and substitution-group membership for the Odoo-parity
 * program (#1023, spec workstreams B1/E1/G2).
 *
 * <p>Schema version 2 (#1023) added {@code baseUom}, {@code trackingLevel}, {@code uomConversions},
 * {@code substitutionGroupId}, and {@code substitutionProductIds} — additive only; version-1
 * consumers are unaffected and version-2 consumers must treat the new fields as absent on old
 * events ({@code trackingLevel} null ⇒ {@code NONE}).
 *
 * <p>pos-catalog's {@code ProductEntity} has no JPA optimistic-lock {@code @Version}; the envelope's
 * {@code aggregateVersion} therefore carries {@code updatedAt} as epoch millis, which increases per
 * mutation and gives consumers a monotonic stale-event guard. Attribute mutations that do not touch
 * the product row itself (UoM rows, substitution membership) bump {@code updatedAt} on every
 * affected product before publishing so the guard keeps working.
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
 * @param baseUom the product's base (stocking) unit of measure code
 * @param trackingLevel stock tracking level: {@code NONE}, {@code LOT}, or {@code SERIAL}; null on
 *     pre-v2 events means {@code NONE}
 * @param uomConversions per-product UoM conversion set (purchase/pack UoMs with factor to base UoM
 *     and precision scale); null or empty when the product has none
 * @param substitutionGroupId substitution group this product belongs to, if any
 * @param substitutionProductIds product ids of all members of the substitution group (including
 *     this product); null when the product is not in a group
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
        @Nullable Instant updatedAt,
        @Nullable String baseUom,
        @Nullable String trackingLevel,
        @Nullable List<UomConversion> uomConversions,
        @Nullable UUID substitutionGroupId,
        @Nullable List<UUID> substitutionProductIds) {

    public static final String EVENT_TYPE = "catalog.product.updated";
    public static final int SCHEMA_VERSION = 2;

    public ProductUpdatedV1 {
        if (productId == null) {
            throw new IllegalArgumentException("productId must not be null");
        }
    }

    /**
     * One entry of a product's UoM conversion set (#1023).
     *
     * @param uomCode unit-of-measure code, e.g. {@code CASE}
     * @param uomType role of this UoM for the product: {@code BASE}, {@code PURCHASE}, {@code PACK},
     *     or {@code OTHER}
     * @param factorToBase multiplier converting one unit of this UoM into the product's base UoM
     *     ({@code 1 CASE = factorToBase × baseUom}); {@code 1} for {@code BASE} rows
     * @param precisionScale decimal precision scale for quantities expressed in this UoM
     */
    public record UomConversion(
            @NonNull String uomCode,
            @Nullable String uomType,
            @NonNull BigDecimal factorToBase,
            int precisionScale) {

        public UomConversion {
            if (uomCode == null) {
                throw new IllegalArgumentException("uomCode must not be null");
            }
            if (factorToBase == null) {
                throw new IllegalArgumentException("factorToBase must not be null");
            }
        }
    }
}

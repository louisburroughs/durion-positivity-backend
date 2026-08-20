package com.positivity.inventory.internal.service;

import com.positivity.inventory.internal.exception.UomConversionUndefinedException;
import java.math.BigDecimal;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Converts document-UoM quantities into a product's base (stocking) UoM using the catalog-fed
 * {@code ext_product} / {@code ext_product_uom} replica factors (odoo-parity B1/B3, #1033).
 *
 * <p>Rounding policy per spec B3, adopted verbatim from Odoo: general conversions round
 * {@code HALF_UP} at the base UoM's precision scale; reservations/allocations round {@code DOWN}
 * (never promise more than exists). The ledger stays base-UoM only — B2 wires these conversions
 * into the posting-path document boundaries.
 *
 * <p>No conversion path (unknown product, or a UoM with no replica row that is not the product's
 * base UoM) throws {@link UomConversionUndefinedException} — a deterministic 422
 * ({@code UOM_CONVERSION_UNDEFINED}), never a silent 1:1 assumption (spec B4).
 */
public interface UomConversionService {

    /**
     * Converts {@code quantity} expressed in {@code uomCode} to the product's base UoM, rounding
     * {@code HALF_UP} at the base UoM's precision scale. The base UoM itself is an identity path
     * (factor 1, still rounded to the base precision scale).
     *
     * @throws UomConversionUndefinedException when the product is unknown or the UoM has no
     *     conversion path to base
     */
    @NonNull
    BigDecimal toBaseQuantity(@NonNull UUID productId, @NonNull String uomCode, @NonNull BigDecimal quantity);

    /**
     * Converts {@code quantity} expressed in {@code uomCode} to the product's base UoM, rounding
     * {@code DOWN} at the base UoM's precision scale — the reservation/allocation variant that
     * never promises more than exists (spec B3).
     *
     * @throws UomConversionUndefinedException when the product is unknown or the UoM has no
     *     conversion path to base
     */
    @NonNull
    BigDecimal toBaseQuantityForReservation(
            @NonNull UUID productId, @NonNull String uomCode, @NonNull BigDecimal quantity);

    /**
     * Decimal precision scale for quantities expressed in {@code uomCode} for this product, from
     * the replica's conversion row.
     *
     * @throws UomConversionUndefinedException when the product is unknown or the replica has no
     *     row for the UoM
     */
    int precisionScale(@NonNull UUID productId, @NonNull String uomCode);

    /**
     * The product's declared stock divisibility (ADR-0055, #1414): the {@code precision_scale} of
     * its base UoM's own conversion row, or {@code 0} when the product declares none.
     *
     * <p>Unlike {@link #precisionScale}, this never throws. Undeclared means integral, and that
     * default is load-bearing rather than a fallback: {@code product_uom} is unpopulated
     * platform-wide, so the absent-row path is the common path and it must answer "whole units"
     * rather than refuse to answer. It is also what preserves today's behaviour exactly for every
     * product while the declaration is installed ahead of any seeding.
     *
     * @param productId the catalog product; {@code null} for a stock reference that resolves to no
     *     catalog product, which likewise declares nothing and so is integral
     */
    int declaredBaseScale(@Nullable UUID productId);
}

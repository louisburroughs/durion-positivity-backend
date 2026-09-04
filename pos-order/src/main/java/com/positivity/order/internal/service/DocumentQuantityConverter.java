package com.positivity.order.internal.service;

import com.positivity.order.internal.entity.ExtProduct;
import com.positivity.order.internal.entity.ExtProductUomReplica;
import com.positivity.order.internal.exception.PurchaseOrderRequestValidationException;
import com.positivity.order.internal.exception.UomConversionUndefinedException;
import com.positivity.order.internal.repository.ExtProductRepository;
import com.positivity.order.internal.repository.ExtProductUomReplicaRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Converts a purchase-order line keyed in a document UoM ("1 CASE") into the product's base
 * quantity (CAP-320 #1334, odoo-parity B2).
 *
 * <h2>Why the order does this rather than asking inventory</h2>
 *
 * What a buyer keys is a commercial decision and the order is what records it, so the conversion
 * happens where the order is written. The factors come from catalog's own facts through the
 * {@code ext_product_uom} replica — not from a call to pos-inventory, which holds the same replica
 * for its own purposes and which ADR-0044 R1 forbids calling anyway.
 *
 * <h2>Never a silent 1:1</h2>
 *
 * A document UoM with no conversion path — unknown product, unknown UoM, or a line whose stock
 * reference resolves to no catalog product at all — raises {@link UomConversionUndefinedException}
 * (422 {@code UOM_CONVERSION_UNDEFINED}). Assuming 1:1 would turn an order for one case into an
 * order for one unit, and nothing downstream could tell the difference until the delivery arrived
 * short.
 *
 * <p>This is deliberately narrower than the pos-inventory component of the same name: there is no
 * reservation-rounding variant here, because pos-order does not reserve stock — quantities are
 * rounded {@code HALF_UP} at the base UoM's precision scale, the general policy.
 */
@Component
@RequiredArgsConstructor
public class DocumentQuantityConverter {

    /** Scale of the persisted effective conversion factor; matches {@code numeric(20,6)}. */
    private static final int FACTOR_SCALE = 6;

    private final ExtProductRepository extProductRepository;
    private final ExtProductUomReplicaRepository extProductUomReplicaRepository;

    /**
     * Base quantity plus the audit metadata persisted on the line: {@code baseQuantity} is what
     * flows into open-quantity maths, {@code documentUom}/{@code documentQuantity} are the values
     * as keyed, and {@code conversionFactor} is the effective base-per-document-unit factor
     * actually applied, rounding included.
     */
    public record DocumentConversion(
            @NonNull BigDecimal baseQuantity,
            @NonNull String documentUom,
            @NonNull BigDecimal documentQuantity,
            @NonNull BigDecimal conversionFactor) {}

    /**
     * Converts the optional document UoM pair to base, or returns empty when the line is keyed
     * directly in base UoM (both fields absent).
     *
     * @param productId catalog product id, or {@code null} when the line's stock reference does
     *     not resolve to one (raises {@code UOM_CONVERSION_UNDEFINED} if a document UoM is given)
     * @param stockReference the line's stock reference as keyed, for error messages
     * @throws PurchaseOrderRequestValidationException when only one of the pair is supplied, or
     *     the document quantity is not positive
     * @throws UomConversionUndefinedException when no conversion path to base exists
     */
    @Transactional(readOnly = true)
    public Optional<DocumentConversion> convertIfPresent(
            @Nullable UUID productId,
            @Nullable String stockReference,
            @Nullable String documentUom,
            @Nullable BigDecimal documentQuantity) {
        if (documentUom == null && documentQuantity == null) {
            return Optional.empty();
        }
        if (documentUom == null || documentUom.isBlank() || documentQuantity == null) {
            throw new PurchaseOrderRequestValidationException(
                    "documentUom and documentQuantity must be provided together");
        }
        if (documentQuantity.signum() <= 0) {
            throw new PurchaseOrderRequestValidationException("documentQuantity must be positive");
        }
        if (productId == null) {
            throw UomConversionUndefinedException.unresolvableProduct(stockReference, documentUom);
        }
        BigDecimal baseQuantity = toBaseQuantity(productId, documentUom, documentQuantity);
        BigDecimal conversionFactor = baseQuantity.divide(documentQuantity, FACTOR_SCALE, RoundingMode.HALF_UP);
        return Optional.of(new DocumentConversion(baseQuantity, documentUom, documentQuantity, conversionFactor));
    }

    private BigDecimal toBaseQuantity(UUID productId, String uomCode, BigDecimal quantity) {
        ExtProduct product = extProductRepository
                .findById(productId)
                .orElseThrow(() -> UomConversionUndefinedException.unknownProduct(productId, uomCode));

        BigDecimal raw;
        if (uomCode.equals(product.getBaseUom())) {
            // Identity path: the base UoM converts to itself with factor 1. Catalog emits a BASE
            // row too, but a line keyed in the base UoM must work even where it has not arrived.
            raw = quantity;
        } else {
            ExtProductUomReplica row = extProductUomReplicaRepository
                    .findByProductIdAndUomCode(productId, uomCode)
                    .orElseThrow(() -> UomConversionUndefinedException.unknownUom(productId, uomCode));
            raw = quantity.multiply(row.getFactorToBase());
        }

        Integer baseScale = baseScale(product);
        return baseScale == null ? raw : raw.setScale(baseScale, RoundingMode.HALF_UP);
    }

    /**
     * Base UoM precision scale from the base UoM's own conversion row; null when absent.
     *
     * <p>Absent means unrounded rather than a default scale: inventing one would quietly change
     * ordered quantities on products whose replica has not caught up.
     */
    private Integer baseScale(ExtProduct product) {
        if (product.getBaseUom() == null) {
            return null;
        }
        return extProductUomReplicaRepository
                .findByProductIdAndUomCode(product.getProductId(), product.getBaseUom())
                .map(ExtProductUomReplica::getPrecisionScale)
                .orElse(null);
    }
}

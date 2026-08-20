package com.positivity.inventory.internal.exception;

import java.math.BigDecimal;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A posted quantity carries more decimal places than the product's catalog declaration allows
 * (ADR-0055, #1414).
 *
 * <p>This is the supply side of the invariant pos-workorder's part gate enforces on the demand
 * side (#1413), and it deliberately raises the same error code so a counter receiving stock and an
 * advisor issuing it are told the same thing about the same product. It replaces the
 * {@code intValueExact()} guards in receiving, ASN and returns: those refused every fraction for
 * every product, which was right for the whole catalog as seeded and wrong the moment one product
 * declares itself divisible. What changed is that the rule is read from the declaration rather
 * than hardcoded — the guards keep their fail-closed character, and a product declaring nothing
 * still resolves to whole units.
 *
 * <p>Maps to a deterministic 422 with code {@code FRACTIONAL_QUANTITY_NOT_ALLOWED}, matching the
 * other catalog-declaration violations in this module ({@code UOM_CONVERSION_UNDEFINED} and the
 * lot codes): the request is well-formed and passes its bean-validation bounds; what it violates
 * can only be known after looking the product up.
 */
public class FractionalQuantityNotAllowedException extends RuntimeException {

    public static final String ERROR_CODE = "FRACTIONAL_QUANTITY_NOT_ALLOWED";

    private final transient @Nullable UUID productId;

    public FractionalQuantityNotAllowedException(String message, @Nullable UUID productId) {
        super(message);
        this.productId = productId;
    }

    /** The product declares no divisibility (scale 0, or no {@code product_uom} rows at all). */
    public static FractionalQuantityNotAllowedException wholeUnitsOnly(
            @Nullable UUID productId, String stockReference, String fieldName, BigDecimal quantity) {
        return new FractionalQuantityNotAllowedException(
                ERROR_CODE + ": " + fieldName + " " + quantity.toPlainString() + " is not valid for '" + stockReference
                        + "' — this product is stocked in whole units. Post a whole quantity, or declare a"
                        + " precision_scale for the product in the catalog if it is genuinely divisible.",
                productId);
    }

    /** The product is divisible, but not this finely. */
    public static FractionalQuantityNotAllowedException scaleExceeded(
            @Nullable UUID productId, String stockReference, String fieldName, BigDecimal quantity, int declaredScale) {
        return new FractionalQuantityNotAllowedException(
                ERROR_CODE + ": " + fieldName + " " + quantity.toPlainString() + " is not valid for '" + stockReference
                        + "' — this product is stocked to " + declaredScale
                        + " decimal places. Post a quantity with at most " + declaredScale + " decimal places.",
                productId);
    }

    public @Nullable UUID getProductId() {
        return productId;
    }
}

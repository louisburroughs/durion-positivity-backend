package com.positivity.order.internal.exception;

import java.util.UUID;

/**
 * Thrown when a quantity conversion is requested for a (product, UoM) pair that has no conversion
 * path to the product's base UoM in the catalog-fed replica (odoo-parity B1/B4, #1033) — either
 * the product has no {@code ext_product} row yet, or the UoM has no {@code ext_product_uom} row
 * and is not the product's base UoM.
 *
 * <p>Maps to a deterministic 422 with code {@code UOM_CONVERSION_UNDEFINED}; a missing conversion
 * must never degrade to a silent 1:1 assumption.
 */
public class UomConversionUndefinedException extends RuntimeException {

    private final String errorCode = "UOM_CONVERSION_UNDEFINED";
    private final UUID productId;
    private final String uomCode;

    public UomConversionUndefinedException(UUID productId, String uomCode, String reason) {
        super(String.format("UOM_CONVERSION_UNDEFINED: productId=%s uomCode=%s — %s", productId, uomCode, reason));
        this.productId = productId;
        this.uomCode = uomCode;
    }

    public static UomConversionUndefinedException unknownProduct(UUID productId, String uomCode) {
        return new UomConversionUndefinedException(productId, uomCode, "no catalog product replica for this product");
    }

    public static UomConversionUndefinedException unknownUom(UUID productId, String uomCode) {
        return new UomConversionUndefinedException(
                productId, uomCode, "no conversion factor to the product's base UoM");
    }

    /**
     * A document line carries a document UoM but its stock reference (free-text SKU, missing
     * skuId, ...) does not resolve to a catalog product id, so no conversion path can even be
     * looked up (odoo-parity B2, #1034). Deterministic 422 — never a silent 1:1 assumption.
     */
    public static UomConversionUndefinedException unresolvableProduct(String stockReference, String uomCode) {
        return new UomConversionUndefinedException(
                null,
                uomCode,
                "document line stock reference '" + stockReference + "' does not resolve to a catalog product id");
    }

    public String getErrorCode() {
        return errorCode;
    }

    public UUID getProductId() {
        return productId;
    }

    public String getUomCode() {
        return uomCode;
    }
}

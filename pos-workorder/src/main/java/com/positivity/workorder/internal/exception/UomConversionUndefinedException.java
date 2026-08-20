package com.positivity.workorder.internal.exception;

import java.util.UUID;

/**
 * A part line names a unit of measure the referenced product has no conversion row for in the
 * {@code ext_product_uom} replica (ADR-0055 stage 3, #1415).
 *
 * <p>Thrown instead of a silent 1:1 assumption — the same discipline pos-inventory's own
 * {@code UomConversionUndefinedException} documents, mirrored here because pos-workorder cannot
 * import pos-inventory internals across the module wall (ArchUnit). Surfaced as 422 UNPROCESSABLE
 * ENTITY: the request is well-formed, and what it violates — no declared conversion for this
 * (product, uomCode) pair — can only be known after a catalog lookup.
 */
public class UomConversionUndefinedException extends RuntimeException {

    public static final String ERROR_CODE = "UOM_CONVERSION_UNDEFINED";

    private final transient UUID productId;
    private final String uomCode;

    public UomConversionUndefinedException(UUID productId, String uomCode, String message) {
        super(message);
        this.productId = productId;
        this.uomCode = uomCode;
    }

    public static UomConversionUndefinedException noConversionRow(UUID productId, String uomCode) {
        return new UomConversionUndefinedException(
                productId,
                uomCode,
                "Product " + productId + " has no conversion row for unit '" + uomCode
                        + "' — enter the quantity in the product's base unit, or a unit the catalog declares a"
                        + " conversion for.");
    }

    public UUID getProductId() {
        return productId;
    }

    public String getUomCode() {
        return uomCode;
    }
}

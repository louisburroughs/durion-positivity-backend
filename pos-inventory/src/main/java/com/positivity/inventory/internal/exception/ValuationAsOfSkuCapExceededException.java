package com.positivity.inventory.internal.exception;

/**
 * Raised when a full-catalog as-of valuation would process too many SKUs for one request.
 */
public class ValuationAsOfSkuCapExceededException extends RuntimeException {
    public static final String ERROR_CODE = "VALUATION_AS_OF_SKU_CAP_EXCEEDED";

    public ValuationAsOfSkuCapExceededException(int skuCount, int cap) {
        super("as-of valuation rejected: " + skuCount + " SKUs exceed cap " + cap
                + "; refine with sku/location filters or use smaller windows");
    }
}

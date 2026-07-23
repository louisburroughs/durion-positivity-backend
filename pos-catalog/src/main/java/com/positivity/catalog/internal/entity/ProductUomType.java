package com.positivity.catalog.internal.entity;

/**
 * Role of a per-product unit of measure (#1023, inventory Odoo-parity Story X1).
 */
public enum ProductUomType {
    /**
     * Declares the product's base (stocking) UoM precision; factor must be 1 and the code should
     * match the product's {@code unitOfMeasure}.
     */
    BASE,
    /** UoM the product is purchased in (e.g. CASE). */
    PURCHASE,
    /** Pack/bundle UoM (e.g. BOX, PALLET). */
    PACK,
    /** Any other conversion relevant to the product. */
    OTHER
}

package com.positivity.bulkloader.internal.domain;

import lombok.Data;

@Data
public class BasePriceRecord {

    /**
     * Resolved from {@link #sku}, or supplied directly.
     *
     * <p>Product ids are generated when the catalog loads, so a portable file names the product by
     * its SKU and has the id looked up as it loads.
     */
    private String productId;

    private String sku;
    private String msrp;
    private String currency;
    private String effectiveFrom;
}

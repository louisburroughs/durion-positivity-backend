package com.positivity.bulkloader.internal.domain;

import java.math.BigDecimal;
import lombok.Data;

@Data
public class CatalogProductRecord {

    private String sku;
    private String upc;
    private String name;
    private String description;
    private String categoryName;
    private String subcategoryName;
    private BigDecimal price;
}

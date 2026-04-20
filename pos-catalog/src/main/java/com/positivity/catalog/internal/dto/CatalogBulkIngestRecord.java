package com.positivity.catalog.internal.dto;

import jakarta.validation.constraints.NotBlank;
import java.math.BigDecimal;
import lombok.Data;

@Data
public class CatalogBulkIngestRecord {

    @NotBlank
    private String sku;

    private String upc;

    @NotBlank
    private String name;

    private String description;

    private String categoryName;

    private String subcategoryName;

    private BigDecimal price;
}

package com.positivity.bulkloader.internal.domain;

import com.positivity.bulkloader.internal.enums.DomainType;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;

@Component
public class CatalogLoaderStrategy implements DomainLoaderStrategy<CatalogProductRecord> {

    @Override
    public DomainType getDomainType() {
        return DomainType.CATALOG_PRODUCT;
    }

    @Override
    public CatalogProductRecord mapRow(@NonNull Map<String, String> row) {
        CatalogProductRecord catalogProduct = new CatalogProductRecord();
        catalogProduct.setSku(row.get("sku"));
        catalogProduct.setUpc(row.get("upc"));
        catalogProduct.setName(row.getOrDefault("name", row.get("description")));
        catalogProduct.setDescription(row.get("description"));
        catalogProduct.setCategoryName(row.get("categoryName"));
        catalogProduct.setSubcategoryName(row.get("subcategoryName"));
        String priceStr = row.get("price");
        if (priceStr != null && !priceStr.isBlank()) {
            try {
                catalogProduct.setPrice(new BigDecimal(priceStr.replaceAll("[^0-9.]", "")));
            } catch (NumberFormatException ignored) {
                // Validation flow handles malformed values.
            }
        }
        return catalogProduct;
    }

    @Override
    public List<String> validate(@NonNull CatalogProductRecord item) {
        List<String> errors = new ArrayList<>();
        if (item.getSku() == null || item.getSku().isBlank()) {
            errors.add("sku is required");
        }
        if (item.getName() == null || item.getName().isBlank()) {
            errors.add("name is required");
        }
        return errors;
    }
}

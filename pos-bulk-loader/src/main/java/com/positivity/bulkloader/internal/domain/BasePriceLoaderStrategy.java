package com.positivity.bulkloader.internal.domain;

import com.positivity.bulkloader.internal.enums.DomainType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;

@Component
public class BasePriceLoaderStrategy implements DomainLoaderStrategy<BasePriceRecord> {

    @Override
    public DomainType getDomainType() {
        return DomainType.BASE_PRICE;
    }

    @Override
    public BasePriceRecord mapRow(@NonNull Map<String, String> row) {
        BasePriceRecord basePrice = new BasePriceRecord();
        basePrice.setProductId(row.get("productId"));
        basePrice.setMsrp(row.get("msrp"));
        basePrice.setCurrency(row.get("currency"));
        basePrice.setEffectiveFrom(row.get("effectiveFrom"));
        basePrice.setSku(row.get("sku"));
        return basePrice;
    }

    /**
     * Resolves a SKU to the product id the price endpoint expects.
     *
     * <p>Product ids are generated when the catalog loads, so a price file that carried them would
     * only work against the environment it was written for. Naming the product by SKU keeps the
     * file portable and makes the catalog pack a genuine prerequisite rather than a coincidence.
     */
    @Override
    @NonNull
    public BasePriceRecord resolve(@NonNull BasePriceRecord item, @NonNull ResolutionContext context) {
        if (LoaderValues.isBlank(item.getProductId()) && LoaderValues.isPresent(item.getSku())) {
            CatalogResolutions.productId(context, item.getSku()).ifPresent(item::setProductId);
        }
        return item;
    }

    @Override
    public List<String> validate(@NonNull BasePriceRecord item) {
        List<String> errors = new ArrayList<>();
        LoaderValues.requireUuid(item.getProductId(), "productId", "a sku that resolves to one", errors);
        if (item.getMsrp() == null || item.getMsrp().isBlank()) {
            errors.add("msrp is required");
        }
        String trimmedCurrency =
                item.getCurrency() == null ? null : item.getCurrency().trim();
        if (trimmedCurrency == null || trimmedCurrency.isBlank()) {
            errors.add("currency is required");
        } else if (trimmedCurrency.length() != 3) {
            errors.add("currency must be exactly 3 characters");
        }
        if (item.getEffectiveFrom() == null || item.getEffectiveFrom().isBlank()) {
            errors.add("effectiveFrom is required");
        }
        return errors;
    }
}

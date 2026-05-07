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
        return basePrice;
    }

    @Override
    public List<String> validate(@NonNull BasePriceRecord item) {
        List<String> errors = new ArrayList<>();
        if (item.getProductId() == null || item.getProductId().isBlank()) {
            errors.add("productId is required");
        }
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

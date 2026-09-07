package com.positivity.bulkloader.internal.domain;

import com.positivity.bulkloader.internal.enums.DomainType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;

/** Hourly labor rates, resolving each row's site from the location code the file names. */
@Component
public class LaborRateLoaderStrategy implements DomainLoaderStrategy<LaborRateLoaderRecord> {

    @Override
    public DomainType getDomainType() {
        return DomainType.LABOR_RATE;
    }

    @Override
    public LaborRateLoaderRecord mapRow(@NonNull Map<String, String> row) {
        LaborRateLoaderRecord rate = new LaborRateLoaderRecord();
        rate.setLocationId(row.get("locationId"));
        rate.setLocationCode(row.get("locationCode"));
        rate.setOperationCategory(row.get("operationCategory"));
        rate.setCurrency(row.get("currency"));
        rate.setHourlyRate(row.get("hourlyRate"));
        rate.setEffectiveFrom(row.get("effectiveFrom"));
        rate.setEffectiveTo(row.get("effectiveTo"));
        return rate;
    }

    @Override
    @NonNull
    public LaborRateLoaderRecord resolve(@NonNull LaborRateLoaderRecord item, @NonNull ResolutionContext context) {
        if (LoaderValues.isBlank(item.getLocationId()) && LoaderValues.isPresent(item.getLocationCode())) {
            LocationResolutions.siteId(context, item.getLocationCode()).ifPresent(item::setLocationId);
        }
        return item;
    }

    @Override
    public List<String> validate(@NonNull LaborRateLoaderRecord item) {
        List<String> errors = new ArrayList<>();
        // Only a code that was given and matched nothing is a failure: no code at all means the
        // platform default, which is a rate with no location by design.
        if (LoaderValues.isPresent(item.getLocationCode())) {
            LoaderValues.requireUuid(item.getLocationId(), "locationId", "a locationCode that resolves to one", errors);
        }
        if (LoaderValues.isBlank(item.getCurrency())) {
            errors.add("currency is required");
        } else if (item.getCurrency().trim().length() != 3) {
            errors.add("currency must be exactly 3 characters");
        }
        LoaderValues.requireDecimal(item.getHourlyRate(), "hourlyRate", errors);
        if (LoaderValues.isBlank(item.getEffectiveFrom())) {
            errors.add("effectiveFrom is required");
        }
        return errors;
    }
}

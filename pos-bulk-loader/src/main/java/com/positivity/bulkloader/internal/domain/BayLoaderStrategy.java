package com.positivity.bulkloader.internal.domain;

import com.positivity.bulkloader.internal.enums.DomainType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;

/** Service bays, with the location named by code. */
@Component
public class BayLoaderStrategy implements DomainLoaderStrategy<BayLoaderRecord> {

    @Override
    public DomainType getDomainType() {
        return DomainType.BAY;
    }

    @Override
    public BayLoaderRecord mapRow(@NonNull Map<String, String> row) {
        BayLoaderRecord record = new BayLoaderRecord();
        record.setLocationCode(row.get("locationCode"));
        record.setName(row.get("name"));
        record.setBayType(row.get("bayType"));
        record.setMaxConcurrentVehicles(row.get("maxConcurrentVehicles"));
        record.setStatus(row.get("status"));
        record.setLocationId(row.get("locationId"));
        return record;
    }

    @Override
    @NonNull
    public BayLoaderRecord resolve(@NonNull BayLoaderRecord item, @NonNull ResolutionContext context) {
        if (LoaderValues.isBlank(item.getLocationId()) && LoaderValues.isPresent(item.getLocationCode())) {
            LocationResolutions.siteId(context, item.getLocationCode()).ifPresent(item::setLocationId);
        }
        return item;
    }

    @Override
    public List<String> validate(@NonNull BayLoaderRecord item) {
        List<String> errors = new ArrayList<>();
        if (LoaderValues.isBlank(item.getName())) {
            errors.add("name is required");
        }
        if (LoaderValues.isBlank(item.getBayType())) {
            errors.add("bayType is required");
        }
        if (LoaderValues.isBlank(item.getMaxConcurrentVehicles())) {
            errors.add("maxConcurrentVehicles is required");
        } else {
            LoaderValues.requireIntegerOrBlank(item.getMaxConcurrentVehicles(), "maxConcurrentVehicles", errors);
        }
        LoaderValues.requireUuid(item.getLocationId(), "locationId", "a locationCode that resolves to one", errors);
        return errors;
    }
}

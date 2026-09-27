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
        record.setMaxDutyClass(row.get("maxDutyClass"));
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
        validateMaxDutyClass(item, errors);
        LoaderValues.requireUuid(item.getLocationId(), "locationId", "a locationCode that resolves to one", errors);
        return errors;
    }

    /**
     * A duty class is a maximum only (spec D13): blank means unconstrained, and a value must be a
     * whole GVWR class between 1 (light duty) and 8 (heavy duty).
     */
    private static void validateMaxDutyClass(BayLoaderRecord item, List<String> errors) {
        if (LoaderValues.isBlank(item.getMaxDutyClass())) {
            return;
        }
        try {
            int dutyClass = Integer.parseInt(item.getMaxDutyClass().trim());
            if (dutyClass < 1 || dutyClass > 8) {
                errors.add("maxDutyClass must be between 1 and 8");
            }
        } catch (NumberFormatException _) {
            errors.add("maxDutyClass must be a whole number");
        }
    }
}

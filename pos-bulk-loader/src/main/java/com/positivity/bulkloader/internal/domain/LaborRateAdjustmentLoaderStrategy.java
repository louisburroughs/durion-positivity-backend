package com.positivity.bulkloader.internal.domain;

import com.positivity.bulkloader.internal.enums.DomainType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;

/** Labor-matrix adjustment steps, resolving each row's site from the location code the file names. */
@Component
public class LaborRateAdjustmentLoaderStrategy implements DomainLoaderStrategy<LaborRateAdjustmentLoaderRecord> {

    @Override
    public DomainType getDomainType() {
        return DomainType.LABOR_RATE_ADJUSTMENT;
    }

    @Override
    public LaborRateAdjustmentLoaderRecord mapRow(@NonNull Map<String, String> row) {
        LaborRateAdjustmentLoaderRecord step = new LaborRateAdjustmentLoaderRecord();
        step.setLocationId(row.get("locationId"));
        step.setLocationCode(row.get("locationCode"));
        step.setOperationCategory(row.get("operationCategory"));
        step.setAdjustmentCode(row.get("adjustmentCode"));
        step.setDescription(row.get("description"));
        step.setAdjustmentType(row.get("adjustmentType"));
        step.setAdjustmentValue(row.get("adjustmentValue"));
        step.setSequence(row.get("sequence"));
        step.setEffectiveFrom(row.get("effectiveFrom"));
        step.setEffectiveTo(row.get("effectiveTo"));
        return step;
    }

    @Override
    @NonNull
    public LaborRateAdjustmentLoaderRecord resolve(
            @NonNull LaborRateAdjustmentLoaderRecord item, @NonNull ResolutionContext context) {
        if (LoaderValues.isBlank(item.getLocationId()) && LoaderValues.isPresent(item.getLocationCode())) {
            LocationResolutions.siteId(context, item.getLocationCode()).ifPresent(item::setLocationId);
        }
        return item;
    }

    @Override
    public List<String> validate(@NonNull LaborRateAdjustmentLoaderRecord item) {
        List<String> errors = new ArrayList<>();
        if (LoaderValues.isPresent(item.getLocationCode())) {
            LoaderValues.requireUuid(item.getLocationId(), "locationId", "a locationCode that resolves to one", errors);
        }
        if (LoaderValues.isBlank(item.getAdjustmentCode())) {
            errors.add("adjustmentCode is required");
        }
        if (LoaderValues.isBlank(item.getAdjustmentType())) {
            errors.add("adjustmentType is required");
        }
        LoaderValues.requireDecimal(item.getAdjustmentValue(), "adjustmentValue", errors);
        if (LoaderValues.isBlank(item.getSequence())) {
            errors.add("sequence is required");
        } else {
            LoaderValues.requireIntegerOrBlank(item.getSequence(), "sequence", errors);
        }
        if (LoaderValues.isBlank(item.getEffectiveFrom())) {
            errors.add("effectiveFrom is required");
        }
        return errors;
    }
}

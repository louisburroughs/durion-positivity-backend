package com.positivity.bulkloader.internal.domain;

import com.positivity.bulkloader.internal.enums.DomainType;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;

/**
 * Staffing assignments, with the location named by code.
 *
 * <p>The employee number is passed through untouched: pos-people owns employee numbers and resolves
 * them when the batch lands, which is one hop rather than one lookup per row from here.
 */
@Component
public class StaffingAssignmentLoaderStrategy implements DomainLoaderStrategy<StaffingAssignmentLoaderRecord> {

    @Override
    public DomainType getDomainType() {
        return DomainType.STAFFING_ASSIGNMENT;
    }

    @Override
    public StaffingAssignmentLoaderRecord mapRow(@NonNull Map<String, String> row) {
        StaffingAssignmentLoaderRecord record = new StaffingAssignmentLoaderRecord();
        record.setEmployeeNumber(row.get("employeeNumber"));
        record.setLocationCode(row.get("locationCode"));
        record.setRole(row.get("role"));
        record.setPrimary(row.get("primary"));
        record.setEffectiveFrom(row.get("effectiveFrom"));
        record.setEffectiveTo(row.get("effectiveTo"));
        record.setLocationId(row.get("locationId"));
        return record;
    }

    @Override
    @NonNull
    public StaffingAssignmentLoaderRecord resolve(
            @NonNull StaffingAssignmentLoaderRecord item, @NonNull ResolutionContext context) {
        if (LoaderValues.isBlank(item.getLocationId()) && LoaderValues.isPresent(item.getLocationCode())) {
            LocationResolutions.siteId(context, item.getLocationCode()).ifPresent(item::setLocationId);
        }
        return item;
    }

    @Override
    public List<String> validate(@NonNull StaffingAssignmentLoaderRecord item) {
        List<String> errors = new ArrayList<>();
        if (LoaderValues.isBlank(item.getEmployeeNumber())) {
            errors.add("employeeNumber is required");
        }
        if (LoaderValues.isBlank(item.getRole())) {
            errors.add("role is required");
        }
        LoaderValues.requireUuid(item.getLocationId(), "locationId", "a locationCode that resolves to one", errors);
        requireDateOrBlank(item.getEffectiveFrom(), "effectiveFrom", errors);
        requireDateOrBlank(item.getEffectiveTo(), "effectiveTo", errors);
        return errors;
    }

    private void requireDateOrBlank(String value, String field, List<String> errors) {
        if (LoaderValues.isBlank(value)) {
            return;
        }
        try {
            LocalDate.parse(value.trim());
        } catch (DateTimeParseException _) {
            errors.add(field + " must be a date in yyyy-MM-dd form");
        }
    }
}

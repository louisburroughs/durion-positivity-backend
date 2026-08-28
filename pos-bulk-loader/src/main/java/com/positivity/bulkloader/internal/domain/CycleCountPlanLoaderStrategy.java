package com.positivity.bulkloader.internal.domain;

import com.positivity.bulkloader.internal.enums.DomainType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;

/** Cycle count plans, with the site and every zone named rather than identified. */
@Component
@Slf4j
public class CycleCountPlanLoaderStrategy implements DomainLoaderStrategy<CycleCountPlanLoaderRecord> {

    private static final String ZONE_SEPARATOR = "\\|";

    @Override
    public DomainType getDomainType() {
        return DomainType.CYCLE_COUNT_PLAN;
    }

    @Override
    public CycleCountPlanLoaderRecord mapRow(@NonNull Map<String, String> row) {
        CycleCountPlanLoaderRecord record = new CycleCountPlanLoaderRecord();
        record.setLocationCode(row.get("locationCode"));
        record.setPlanName(row.get("planName"));
        record.setZoneNames(row.get("zoneNames"));
        record.setScheduledDaysOut(row.get("scheduledDaysOut"));
        record.setScheduledDate(row.get("scheduledDate"));
        record.setLocationId(row.get("locationId"));
        record.setZoneIds(row.get("zoneIds"));
        return record;
    }

    /**
     * Resolves the site and every zone.
     *
     * <p>A plan is all-or-nothing: if any zone name fails to resolve, none is kept, so the row fails
     * rather than creating a plan that silently walks fewer zones than it was meant to. A count that
     * skips a zone reports no variance for it, which is indistinguishable from finding none.
     */
    @Override
    @NonNull
    public CycleCountPlanLoaderRecord resolve(
            @NonNull CycleCountPlanLoaderRecord item, @NonNull ResolutionContext context) {

        if (LoaderValues.isBlank(item.getLocationCode())) {
            return item;
        }
        String locationCode = item.getLocationCode().trim();

        if (LoaderValues.isBlank(item.getLocationId())) {
            LocationResolutions.siteId(context, locationCode).ifPresent(item::setLocationId);
        }
        if (LoaderValues.isPresent(item.getZoneIds()) || LoaderValues.isBlank(item.getZoneNames())) {
            return item;
        }

        List<String> resolved = new ArrayList<>();
        List<String> unresolved = new ArrayList<>();
        for (String zoneName : item.getZoneNames().split(ZONE_SEPARATOR)) {
            if (zoneName.isBlank()) {
                continue;
            }
            Optional<String> zoneId = LocationResolutions.storageLocationId(context, locationCode, zoneName);
            if (zoneId.isPresent()) {
                resolved.add(zoneId.get());
            } else {
                unresolved.add(zoneName.trim());
            }
        }

        if (!unresolved.isEmpty()) {
            log.warn(
                    "Cycle count plan '{}': unresolved zone(s) {} at {} — the row will fail on its missing zoneIds",
                    item.getPlanName(),
                    unresolved,
                    locationCode);
            return item;
        }
        item.setZoneIds(String.join(",", resolved));
        return item;
    }

    @Override
    public List<String> validate(@NonNull CycleCountPlanLoaderRecord item) {
        List<String> errors = new ArrayList<>();
        if (LoaderValues.isBlank(item.getPlanName())) {
            errors.add("planName is required");
        }
        LoaderValues.requireUuid(item.getLocationId(), "locationId", "a locationCode that resolves to one", errors);
        if (LoaderValues.isBlank(item.getZoneIds())) {
            errors.add("zoneIds is required (or zoneNames that all resolve to storage locations at the site)");
        }
        LoaderValues.requireIntegerOrBlank(item.getScheduledDaysOut(), "scheduledDaysOut", errors);
        return errors;
    }
}

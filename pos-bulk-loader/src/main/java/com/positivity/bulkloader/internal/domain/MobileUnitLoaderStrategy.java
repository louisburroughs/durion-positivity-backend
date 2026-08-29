package com.positivity.bulkloader.internal.domain;

import com.positivity.bulkloader.internal.enums.DomainType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;

/** Mobile units, with the base location named by code. */
@Component
public class MobileUnitLoaderStrategy implements DomainLoaderStrategy<MobileUnitLoaderRecord> {

    @Override
    public DomainType getDomainType() {
        return DomainType.MOBILE_UNIT;
    }

    @Override
    public MobileUnitLoaderRecord mapRow(@NonNull Map<String, String> row) {
        MobileUnitLoaderRecord record = new MobileUnitLoaderRecord();
        record.setBaseLocationCode(row.get("baseLocationCode"));
        record.setName(row.get("name"));
        record.setStatus(row.get("status"));
        record.setNotes(row.get("notes"));
        record.setBaseLocationId(row.get("baseLocationId"));
        return record;
    }

    @Override
    @NonNull
    public MobileUnitLoaderRecord resolve(@NonNull MobileUnitLoaderRecord item, @NonNull ResolutionContext context) {
        if (LoaderValues.isBlank(item.getBaseLocationId()) && LoaderValues.isPresent(item.getBaseLocationCode())) {
            LocationResolutions.siteId(context, item.getBaseLocationCode()).ifPresent(item::setBaseLocationId);
        }
        return item;
    }

    @Override
    public List<String> validate(@NonNull MobileUnitLoaderRecord item) {
        List<String> errors = new ArrayList<>();
        if (LoaderValues.isBlank(item.getName())) {
            errors.add("name is required");
        }
        LoaderValues.requireUuid(
                item.getBaseLocationId(), "baseLocationId", "a baseLocationCode that resolves to one", errors);
        return errors;
    }
}

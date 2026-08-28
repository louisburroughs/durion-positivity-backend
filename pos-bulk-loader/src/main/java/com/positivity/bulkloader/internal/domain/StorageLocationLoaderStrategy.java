package com.positivity.bulkloader.internal.domain;

import com.positivity.bulkloader.internal.enums.DomainType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;

/** A site's storage topology, with the site named by code. */
@Component
public class StorageLocationLoaderStrategy implements DomainLoaderStrategy<StorageLocationLoaderRecord> {

    @Override
    public DomainType getDomainType() {
        return DomainType.STORAGE_LOCATION;
    }

    @Override
    public StorageLocationLoaderRecord mapRow(@NonNull Map<String, String> row) {
        StorageLocationLoaderRecord record = new StorageLocationLoaderRecord();
        record.setLocationCode(row.get("locationCode"));
        record.setName(row.get("name"));
        record.setType(row.get("type"));
        record.setParentName(row.get("parentName"));
        record.setStorageCategoryCode(row.get("storageCategoryCode"));
        record.setHazardContainment(row.get("hazardContainment"));
        record.setAllowNewProduct(row.get("allowNewProduct"));
        record.setMaxUnitCount(row.get("maxUnitCount"));
        record.setStatus(row.get("status"));
        record.setSiteId(row.get("siteId"));
        return record;
    }

    /**
     * Resolves the site only. The parent is left as a name for the owning service, which is the
     * only place that can see both the site's existing locations and the ones earlier rows of the
     * same batch just created.
     */
    @Override
    @NonNull
    public StorageLocationLoaderRecord resolve(
            @NonNull StorageLocationLoaderRecord item, @NonNull ResolutionContext context) {
        if (LoaderValues.isBlank(item.getSiteId()) && LoaderValues.isPresent(item.getLocationCode())) {
            LocationResolutions.siteId(context, item.getLocationCode()).ifPresent(item::setSiteId);
        }
        return item;
    }

    @Override
    public List<String> validate(@NonNull StorageLocationLoaderRecord item) {
        List<String> errors = new ArrayList<>();
        if (LoaderValues.isBlank(item.getName())) {
            errors.add("name is required");
        }
        if (LoaderValues.isBlank(item.getType())) {
            errors.add("type is required");
        }
        LoaderValues.requireUuid(item.getSiteId(), "siteId", "a locationCode that resolves to one", errors);
        LoaderValues.requireIntegerOrBlank(item.getMaxUnitCount(), "maxUnitCount", errors);
        return errors;
    }
}

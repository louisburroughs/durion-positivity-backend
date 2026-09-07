package com.positivity.bulkloader.internal.domain;

import com.positivity.bulkloader.internal.enums.DomainType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;

/**
 * Service operations, keyed by their Durion operation code.
 *
 * <p>Nothing to resolve: the file carries the only key the ingest endpoint needs, and it is the
 * same key in every environment.
 */
@Component
public class CatalogServiceLoaderStrategy implements DomainLoaderStrategy<CatalogServiceLoaderRecord> {

    @Override
    public DomainType getDomainType() {
        return DomainType.CATALOG_SERVICE;
    }

    @Override
    public CatalogServiceLoaderRecord mapRow(@NonNull Map<String, String> row) {
        CatalogServiceLoaderRecord service = new CatalogServiceLoaderRecord();
        service.setOperationCode(row.get("operationCode"));
        service.setName(row.get("name"));
        service.setShortDescription(row.get("shortDescription"));
        service.setLongDescription(row.get("longDescription"));
        service.setOperationCategory(row.get("operationCategory"));
        service.setDefaultLaborHours(row.get("defaultLaborHours"));
        return service;
    }

    @Override
    public List<String> validate(@NonNull CatalogServiceLoaderRecord item) {
        List<String> errors = new ArrayList<>();
        if (LoaderValues.isBlank(item.getOperationCode())) {
            errors.add("operationCode is required");
        }
        if (LoaderValues.isBlank(item.getName())) {
            errors.add("name is required");
        }
        LoaderValues.requireDecimalOrBlank(item.getDefaultLaborHours(), "defaultLaborHours", errors);
        return errors;
    }
}

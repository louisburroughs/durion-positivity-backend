package com.positivity.bulkloader.internal.domain;

import com.positivity.bulkloader.internal.enums.DomainType;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;

/** Vehicle-keyed labor standards, resolving a shop-scoped row's site by its location code. */
@Component
public class ServiceLaborStandardLoaderStrategy implements DomainLoaderStrategy<ServiceLaborStandardLoaderRecord> {

    private static final String SHOP_SCOPE = "SHOP";

    @Override
    public DomainType getDomainType() {
        return DomainType.SERVICE_LABOR_STANDARD;
    }

    @Override
    public ServiceLaborStandardLoaderRecord mapRow(@NonNull Map<String, String> row) {
        ServiceLaborStandardLoaderRecord standard = new ServiceLaborStandardLoaderRecord();
        standard.setOperationCode(row.get("operationCode"));
        standard.setSourceCode(row.get("sourceCode"));
        standard.setSourceRevision(row.get("sourceRevision"));
        standard.setVehicleYear(row.get("vehicleYear"));
        standard.setMake(row.get("make"));
        standard.setModel(row.get("model"));
        standard.setSubmodel(row.get("submodel"));
        standard.setEngineCode(row.get("engineCode"));
        standard.setLaborHours(row.get("laborHours"));
        standard.setTimeType(row.get("timeType"));
        standard.setOverlapGroup(row.get("overlapGroup"));
        standard.setIncludedOpCodes(row.get("includedOpCodes"));
        standard.setOwnerScope(row.get("ownerScope"));
        standard.setOwnerLocationId(row.get("ownerLocationId"));
        standard.setOwnerLocationCode(row.get("ownerLocationCode"));
        standard.setPublishedAt(row.get("publishedAt"));
        return standard;
    }

    @Override
    @NonNull
    public ServiceLaborStandardLoaderRecord resolve(
            @NonNull ServiceLaborStandardLoaderRecord item, @NonNull ResolutionContext context) {
        if (LoaderValues.isBlank(item.getOwnerLocationId()) && LoaderValues.isPresent(item.getOwnerLocationCode())) {
            LocationResolutions.siteId(context, item.getOwnerLocationCode()).ifPresent(item::setOwnerLocationId);
        }
        return item;
    }

    @Override
    public List<String> validate(@NonNull ServiceLaborStandardLoaderRecord item) {
        List<String> errors = new ArrayList<>();
        if (LoaderValues.isBlank(item.getOperationCode())) {
            errors.add("operationCode is required");
        }
        if (LoaderValues.isBlank(item.getSourceCode())) {
            errors.add("sourceCode is required");
        }
        if (LoaderValues.isBlank(item.getSourceRevision())) {
            errors.add("sourceRevision is required");
        }
        LoaderValues.requireDecimal(item.getLaborHours(), "laborHours", errors);
        // A SHOP row without its site resolves for nobody, and the catalog would refuse it anyway;
        // caught here so the row lands in the review queue naming the code that matched nothing.
        if (SHOP_SCOPE.equalsIgnoreCase(trimmed(item.getOwnerScope()))) {
            LoaderValues.requireUuid(
                    item.getOwnerLocationId(), "ownerLocationId", "an ownerLocationCode that resolves to one", errors);
        }
        return errors;
    }

    private static String trimmed(String value) {
        return value == null ? null : value.trim().toUpperCase(Locale.ROOT);
    }
}

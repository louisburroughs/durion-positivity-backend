package com.positivity.bulkloader.internal.domain;

import com.positivity.bulkloader.internal.enums.DomainType;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;

/** Service packages, resolving a shop's site and a fleet's account from the names the file uses. */
@Component
public class ServicePackageLoaderStrategy implements DomainLoaderStrategy<ServicePackageLoaderRecord> {

    private static final String SHOP_SCOPE = "SHOP";
    private static final String COMMERCIAL_PARTY_TYPE = "COMMERCIAL";

    @Override
    public DomainType getDomainType() {
        return DomainType.SERVICE_PACKAGE;
    }

    @Override
    public ServicePackageLoaderRecord mapRow(@NonNull Map<String, String> row) {
        ServicePackageLoaderRecord servicePackage = new ServicePackageLoaderRecord();
        servicePackage.setPackageCode(row.get("packageCode"));
        servicePackage.setName(row.get("name"));
        servicePackage.setDescription(row.get("description"));
        servicePackage.setOwnerScope(row.get("ownerScope"));
        servicePackage.setOwnerLocationId(row.get("ownerLocationId"));
        servicePackage.setOwnerLocationCode(row.get("ownerLocationCode"));
        servicePackage.setFleetPartyId(row.get("fleetPartyId"));
        servicePackage.setFleetCustomerName(row.get("fleetCustomerName"));
        servicePackage.setPackageLaborHours(row.get("packageLaborHours"));
        servicePackage.setActive(row.get("active"));
        servicePackage.setEffectiveFrom(row.get("effectiveFrom"));
        servicePackage.setEffectiveTo(row.get("effectiveTo"));
        return servicePackage;
    }

    @Override
    @NonNull
    public ServicePackageLoaderRecord resolve(
            @NonNull ServicePackageLoaderRecord item, @NonNull ResolutionContext context) {
        if (LoaderValues.isBlank(item.getOwnerLocationId()) && LoaderValues.isPresent(item.getOwnerLocationCode())) {
            LocationResolutions.siteId(context, item.getOwnerLocationCode()).ifPresent(item::setOwnerLocationId);
        }
        if (LoaderValues.isBlank(item.getFleetPartyId()) && LoaderValues.isPresent(item.getFleetCustomerName())) {
            CustomerResolutions.partyId(
                            context, COMMERCIAL_PARTY_TYPE, item.getFleetCustomerName(), "Fleet requirement set owner")
                    .ifPresent(item::setFleetPartyId);
        }
        return item;
    }

    @Override
    public List<String> validate(@NonNull ServicePackageLoaderRecord item) {
        List<String> errors = new ArrayList<>();
        if (LoaderValues.isBlank(item.getPackageCode())) {
            errors.add("packageCode is required");
        }
        if (LoaderValues.isBlank(item.getName())) {
            errors.add("name is required");
        }
        LoaderValues.requireDecimal(item.getPackageLaborHours(), "packageLaborHours", errors);
        if (SHOP_SCOPE.equalsIgnoreCase(normalized(item.getOwnerScope()))) {
            LoaderValues.requireUuid(
                    item.getOwnerLocationId(), "ownerLocationId", "an ownerLocationCode that resolves to one", errors);
        }
        // A named fleet that resolved to nothing must fail its row rather than load as an ordinary
        // offering: a requirement set silently demoted to something a writer may decline is the
        // opposite of what a contract says.
        if (LoaderValues.isPresent(item.getFleetCustomerName())) {
            LoaderValues.requireUuid(
                    item.getFleetPartyId(), "fleetPartyId", "a fleetCustomerName that resolves to one", errors);
        }
        return errors;
    }

    private static String normalized(String value) {
        return value == null ? null : value.trim().toUpperCase(Locale.ROOT);
    }
}

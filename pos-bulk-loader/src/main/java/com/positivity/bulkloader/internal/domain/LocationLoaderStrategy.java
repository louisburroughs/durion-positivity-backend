package com.positivity.bulkloader.internal.domain;

import com.positivity.bulkloader.internal.enums.DomainType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;

@Component
public class LocationLoaderStrategy implements DomainLoaderStrategy<LocationRecord> {

    @Override
    public DomainType getDomainType() {
        return DomainType.LOCATION;
    }

    @Override
    public LocationRecord mapRow(@NonNull Map<String, String> row) {
        LocationRecord item = new LocationRecord();
        item.setName(row.get("name"));
        item.setCode(row.get("code"));
        item.setAddressLine1(row.get("addressLine1"));
        item.setAddressLine2(row.get("addressLine2"));
        item.setCity(row.get("city"));
        item.setStateOrProvince(row.get("stateOrProvince"));
        item.setPostalCode(row.get("postalCode"));
        item.setCountryCode(row.get("countryCode"));
        item.setPhoneNumber(row.get("phoneNumber"));
        item.setActive(row.get("active"));
        item.setLocationTypeName(row.get("locationTypeName"));
        return item;
    }

    @Override
    public List<String> validate(@NonNull LocationRecord item) {
        List<String> errors = new ArrayList<>();
        if (item.getName() == null || item.getName().isBlank()) {
            errors.add("name is required");
        }
        if (item.getCode() == null || item.getCode().isBlank()) {
            errors.add("code is required");
        }
        return errors;
    }
}

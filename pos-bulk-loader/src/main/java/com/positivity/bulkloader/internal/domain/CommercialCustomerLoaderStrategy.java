package com.positivity.bulkloader.internal.domain;

import com.positivity.bulkloader.internal.enums.DomainType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;

@Component
public class CommercialCustomerLoaderStrategy implements DomainLoaderStrategy<CommercialCustomerRecord> {

    @Override
    public DomainType getDomainType() {
        return DomainType.COMMERCIAL_CUSTOMER;
    }

    @Override
    public CommercialCustomerRecord mapRow(@NonNull Map<String, String> row) {
        CommercialCustomerRecord item = new CommercialCustomerRecord();
        item.setLegalName(row.get("legalName"));
        item.setDisplayName(row.get("displayName"));
        item.setTaxId(row.get("taxId"));
        item.setBillingTermsId(row.get("billingTermsId"));
        item.setContactFirstName(row.get("contactFirstName"));
        item.setContactLastName(row.get("contactLastName"));
        item.setContactEmail(row.get("contactEmail"));
        item.setContactPhone(row.get("contactPhone"));
        return item;
    }

    @Override
    public List<String> validate(@NonNull CommercialCustomerRecord item) {
        List<String> errors = new ArrayList<>();
        if (item.getLegalName() == null || item.getLegalName().isBlank()) {
            errors.add("legalName is required");
        }
        return errors;
    }
}

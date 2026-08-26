package com.positivity.bulkloader.internal.domain;

import com.positivity.bulkloader.internal.enums.DomainType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;

@Component
public class PersonLoaderStrategy implements DomainLoaderStrategy<PersonRecord> {

    @Override
    public DomainType getDomainType() {
        return DomainType.PERSON;
    }

    @Override
    public PersonRecord mapRow(@NonNull Map<String, String> row) {
        PersonRecord person = new PersonRecord();
        person.setFirstName(row.get("firstName"));
        person.setLastName(row.get("lastName"));
        person.setPreferredName(row.get("preferredName"));
        person.setEmployeeNumber(row.get("employeeNumber"));
        person.setHireDate(row.get("hireDate"));
        person.setPrimaryEmail(row.get("primaryEmail"));
        person.setPrimaryPhone(row.get("primaryPhone"));
        return person;
    }

    @Override
    public List<String> validate(@NonNull PersonRecord item) {
        List<String> errors = new ArrayList<>();
        if (item.getFirstName() == null || item.getFirstName().isBlank()) {
            errors.add("firstName is required");
        }
        if (item.getLastName() == null || item.getLastName().isBlank()) {
            errors.add("lastName is required");
        }
        if (item.getEmployeeNumber() == null || item.getEmployeeNumber().isBlank()) {
            errors.add("employeeNumber is required");
        }
        return errors;
    }
}

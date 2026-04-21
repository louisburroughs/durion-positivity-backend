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
    person.setLegalName(row.get("legalName"));
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
    if (item.getLegalName() == null || item.getLegalName().isBlank()) {
      errors.add("legalName is required");
    }
    if (item.getEmployeeNumber() == null || item.getEmployeeNumber().isBlank()) {
      errors.add("employeeNumber is required");
    }
    return errors;
  }
}
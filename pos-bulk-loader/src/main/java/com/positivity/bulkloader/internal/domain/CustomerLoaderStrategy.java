package com.positivity.bulkloader.internal.domain;

import com.positivity.bulkloader.internal.enums.DomainType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;

@Component
public class CustomerLoaderStrategy implements DomainLoaderStrategy<CustomerPersonRecord> {

  @Override
  public DomainType getDomainType() {
    return DomainType.CUSTOMER;
  }

  @Override
  public CustomerPersonRecord mapRow(@NonNull Map<String, String> row) {
    CustomerPersonRecord customer = new CustomerPersonRecord();
    customer.setFirstName(row.get("firstName"));
    customer.setLastName(row.get("lastName"));
    customer.setEmail(row.get("email"));
    customer.setPhoneNumber(row.get("phoneNumber"));
    customer.setPrimaryAddress(row.get("primaryAddress"));
    customer.setCustomerNumber(row.get("customerNumber"));
    return customer;
  }

  @Override
  public List<String> validate(@NonNull CustomerPersonRecord item) {
    List<String> errors = new ArrayList<>();
    if (item.getFirstName() == null || item.getFirstName().isBlank()) {
      errors.add("firstName is required");
    }
    if (item.getLastName() == null || item.getLastName().isBlank()) {
      errors.add("lastName is required");
    }
    return errors;
  }
}
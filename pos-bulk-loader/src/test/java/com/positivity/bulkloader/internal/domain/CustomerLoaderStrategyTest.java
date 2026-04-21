package com.positivity.bulkloader.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.bulkloader.internal.enums.DomainType;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

@SuppressWarnings({ "java:S100", "java:S1192" })
class CustomerLoaderStrategyTest {

  private final CustomerLoaderStrategy strategy = new CustomerLoaderStrategy();

  // ─── mapRow ──────────────────────────────────────────────────────────────

  @Test
  void mapRow_populatesAllFields() {
    Map<String, String> row = Map.of(
        "firstName", "Jane",
        "lastName", "Doe",
        "email", "jane@example.com",
        "phoneNumber", "555-1234",
        "primaryAddress", "123 Main St",
        "customerNumber", "CUST-001");

    CustomerPersonRecord result = strategy.mapRow(row);

    assertThat(result.getFirstName()).isEqualTo("Jane");
    assertThat(result.getLastName()).isEqualTo("Doe");
    assertThat(result.getEmail()).isEqualTo("jane@example.com");
    assertThat(result.getPhoneNumber()).isEqualTo("555-1234");
    assertThat(result.getPrimaryAddress()).isEqualTo("123 Main St");
    assertThat(result.getCustomerNumber()).isEqualTo("CUST-001");
  }

  @Test
  void mapRow_handlesMinimalRow() {
    Map<String, String> row = Map.of(
        "firstName", "Bob",
        "lastName", "Smith");

    CustomerPersonRecord result = strategy.mapRow(row);

    assertThat(result.getFirstName()).isEqualTo("Bob");
    assertThat(result.getLastName()).isEqualTo("Smith");
    assertThat(result.getEmail()).isNull();
    assertThat(result.getPhoneNumber()).isNull();
    assertThat(result.getPrimaryAddress()).isNull();
    assertThat(result.getCustomerNumber()).isNull();
  }

  // ─── validate ────────────────────────────────────────────────────────────

  @Test
  void validate_passesWhenRequiredFieldsPresent() {
    CustomerPersonRecord record = new CustomerPersonRecord();
    record.setFirstName("Alice");
    record.setLastName("Jones");

    List<String> errors = strategy.validate(record);

    assertThat(errors).isEmpty();
  }

  @Test
  void validate_failsWhenFirstNameBlank() {
    CustomerPersonRecord record = new CustomerPersonRecord();
    record.setFirstName("  ");
    record.setLastName("Jones");

    List<String> errors = strategy.validate(record);

    assertThat(errors).anyMatch(e -> e.contains("firstName"));
  }

  @Test
  void validate_failsWhenLastNameBlank() {
    CustomerPersonRecord record = new CustomerPersonRecord();
    record.setFirstName("Alice");
    record.setLastName("");

    List<String> errors = strategy.validate(record);

    assertThat(errors).anyMatch(e -> e.contains("lastName"));
  }

  // ─── getDomainType ────────────────────────────────────────────────────────

  @Test
  void getDomainType_returnsCustomer() {
    assertThat(strategy.getDomainType()).isEqualTo(DomainType.CUSTOMER);
  }
}

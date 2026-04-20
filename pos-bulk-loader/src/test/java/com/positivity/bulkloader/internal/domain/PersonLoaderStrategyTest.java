package com.positivity.bulkloader.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.bulkloader.internal.enums.DomainType;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

@SuppressWarnings({ "java:S100", "java:S1192" })
class PersonLoaderStrategyTest {

  private final PersonLoaderStrategy strategy = new PersonLoaderStrategy();

  // ─── mapRow ──────────────────────────────────────────────────────────────

  @Test
  void mapRow_populatesAllFields() {
    Map<String, String> row = Map.of(
        "legalName", "John Smith",
        "preferredName", "Johnny",
        "employeeNumber", "EMP-001",
        "hireDate", "2024-01-15",
        "primaryEmail", "john@example.com",
        "primaryPhone", "555-9999");

    PersonRecord result = strategy.mapRow(row);

    assertThat(result.getLegalName()).isEqualTo("John Smith");
    assertThat(result.getPreferredName()).isEqualTo("Johnny");
    assertThat(result.getEmployeeNumber()).isEqualTo("EMP-001");
    assertThat(result.getHireDate()).isEqualTo("2024-01-15");
    assertThat(result.getPrimaryEmail()).isEqualTo("john@example.com");
    assertThat(result.getPrimaryPhone()).isEqualTo("555-9999");
  }

  @Test
  void mapRow_handlesMinimalRow() {
    Map<String, String> row = Map.of(
        "legalName", "Jane Doe",
        "employeeNumber", "EMP-002",
        "hireDate", "2025-03-01");

    PersonRecord result = strategy.mapRow(row);

    assertThat(result.getLegalName()).isEqualTo("Jane Doe");
    assertThat(result.getEmployeeNumber()).isEqualTo("EMP-002");
    assertThat(result.getHireDate()).isEqualTo("2025-03-01");
    assertThat(result.getPreferredName()).isNull();
    assertThat(result.getPrimaryEmail()).isNull();
    assertThat(result.getPrimaryPhone()).isNull();
  }

  // ─── validate ────────────────────────────────────────────────────────────

  @Test
  void validate_passesWhenRequiredFieldsPresent() {
    PersonRecord record = new PersonRecord();
    record.setLegalName("Alice Brown");
    record.setEmployeeNumber("EMP-003");

    List<String> errors = strategy.validate(record);

    assertThat(errors).isEmpty();
  }

  @Test
  void validate_failsWhenLegalNameBlank() {
    PersonRecord record = new PersonRecord();
    record.setLegalName("  ");
    record.setEmployeeNumber("EMP-004");

    List<String> errors = strategy.validate(record);

    assertThat(errors).anyMatch(e -> e.contains("legalName"));
  }

  @Test
  void validate_failsWhenEmployeeNumberBlank() {
    PersonRecord record = new PersonRecord();
    record.setLegalName("Alice Brown");
    record.setEmployeeNumber("");

    List<String> errors = strategy.validate(record);

    assertThat(errors).anyMatch(e -> e.contains("employeeNumber"));
  }

  // ─── getDomainType ────────────────────────────────────────────────────────

  @Test
  void getDomainType_returnsPerson() {
    assertThat(strategy.getDomainType()).isEqualTo(DomainType.PERSON);
  }
}

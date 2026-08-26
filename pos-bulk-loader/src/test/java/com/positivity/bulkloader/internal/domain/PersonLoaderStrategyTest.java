package com.positivity.bulkloader.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.bulkloader.internal.enums.DomainType;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

@SuppressWarnings({"java:S100", "java:S1192"})
class PersonLoaderStrategyTest {

    private final PersonLoaderStrategy strategy = new PersonLoaderStrategy();

    // ─── mapRow ──────────────────────────────────────────────────────────────

    @Test
    void mapRow_populatesAllFields() {
        Map<String, String> row = Map.of(
                "firstName", "John",
                "lastName", "Smith",
                "preferredName", "Johnny",
                "employeeNumber", "EMP-0001",
                "hireDate", "2024-01-15",
                "primaryEmail", "john.smith@example.com",
                "primaryPhone", "555-1234");

        PersonRecord result = strategy.mapRow(row);

        assertThat(result.getFirstName()).isEqualTo("John");
        assertThat(result.getLastName()).isEqualTo("Smith");
        assertThat(result.getPreferredName()).isEqualTo("Johnny");
        assertThat(result.getEmployeeNumber()).isEqualTo("EMP-0001");
        assertThat(result.getHireDate()).isEqualTo("2024-01-15");
        assertThat(result.getPrimaryEmail()).isEqualTo("john.smith@example.com");
        assertThat(result.getPrimaryPhone()).isEqualTo("555-1234");
    }

    @Test
    void mapRow_handlesMinimalRow() {
        Map<String, String> row = Map.of(
                "firstName", "Jane",
                "lastName", "Doe",
                "employeeNumber", "EMP-0002");

        PersonRecord result = strategy.mapRow(row);

        assertThat(result.getFirstName()).isEqualTo("Jane");
        assertThat(result.getLastName()).isEqualTo("Doe");
        assertThat(result.getEmployeeNumber()).isEqualTo("EMP-0002");
        assertThat(result.getPreferredName()).isNull();
        assertThat(result.getHireDate()).isNull();
        assertThat(result.getPrimaryEmail()).isNull();
        assertThat(result.getPrimaryPhone()).isNull();
    }

    // ─── validate ────────────────────────────────────────────────────────────

    @Test
    void validate_passesWhenRequiredFieldsPresent() {
        PersonRecord record = new PersonRecord();
        record.setFirstName("Alice");
        record.setLastName("Brown");
        record.setEmployeeNumber("EMP-0003");

        List<String> errors = strategy.validate(record);

        assertThat(errors).isEmpty();
    }

    @Test
    void validate_failsWhenFirstNameBlank() {
        PersonRecord record = new PersonRecord();
        record.setFirstName("  ");
        record.setLastName("Brown");
        record.setEmployeeNumber("EMP-0003");

        List<String> errors = strategy.validate(record);

        assertThat(errors).anyMatch(e -> e.contains("firstName"));
    }

    @Test
    void validate_failsWhenLastNameBlank() {
        PersonRecord record = new PersonRecord();
        record.setFirstName("Alice");
        record.setLastName("");
        record.setEmployeeNumber("EMP-0003");

        List<String> errors = strategy.validate(record);

        assertThat(errors).anyMatch(e -> e.contains("lastName"));
    }

    @Test
    void validate_failsWhenEmployeeNumberBlank() {
        PersonRecord record = new PersonRecord();
        record.setFirstName("Alice");
        record.setLastName("Brown");
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

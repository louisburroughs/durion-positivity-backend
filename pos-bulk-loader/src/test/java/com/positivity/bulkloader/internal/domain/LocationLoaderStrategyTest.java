package com.positivity.bulkloader.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.bulkloader.internal.enums.DomainType;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

@SuppressWarnings({"java:S100", "java:S1192"})
class LocationLoaderStrategyTest {

    private final LocationLoaderStrategy strategy = new LocationLoaderStrategy();

    // ─── mapRow ──────────────────────────────────────────────────────────────

    @Test
    void mapRow_populatesAllFields() {
        Map<String, String> row = Map.ofEntries(
                Map.entry("name", "Charlotte South"),
                Map.entry("code", "CLT-SOUTH"),
                Map.entry("addressLine1", "6301 South Blvd"),
                Map.entry("addressLine2", "Suite 200"),
                Map.entry("city", "Charlotte"),
                Map.entry("stateOrProvince", "NC"),
                Map.entry("postalCode", "28217"),
                Map.entry("countryCode", "US"),
                Map.entry("phoneNumber", "704-555-0142"),
                Map.entry("active", "true"),
                Map.entry("locationTypeName", "STORE"),
                Map.entry("timezone", "America/New_York"));

        LocationRecord result = strategy.mapRow(row);

        assertThat(result.getName()).isEqualTo("Charlotte South");
        assertThat(result.getCode()).isEqualTo("CLT-SOUTH");
        assertThat(result.getAddressLine1()).isEqualTo("6301 South Blvd");
        assertThat(result.getAddressLine2()).isEqualTo("Suite 200");
        assertThat(result.getCity()).isEqualTo("Charlotte");
        assertThat(result.getStateOrProvince()).isEqualTo("NC");
        assertThat(result.getPostalCode()).isEqualTo("28217");
        assertThat(result.getCountryCode()).isEqualTo("US");
        assertThat(result.getPhoneNumber()).isEqualTo("704-555-0142");
        assertThat(result.getActive()).isEqualTo("true");
        assertThat(result.getLocationTypeName()).isEqualTo("STORE");
        assertThat(result.getTimezone()).isEqualTo("America/New_York");
    }

    @Test
    void mapRow_handlesMinimalRow() {
        Map<String, String> row = Map.of(
                "name", "Charlotte North",
                "code", "CLT-NORTH");

        LocationRecord result = strategy.mapRow(row);

        assertThat(result.getName()).isEqualTo("Charlotte North");
        assertThat(result.getCode()).isEqualTo("CLT-NORTH");
        assertThat(result.getAddressLine1()).isNull();
        assertThat(result.getAddressLine2()).isNull();
        assertThat(result.getCity()).isNull();
        assertThat(result.getStateOrProvince()).isNull();
        assertThat(result.getPostalCode()).isNull();
        assertThat(result.getCountryCode()).isNull();
        assertThat(result.getPhoneNumber()).isNull();
        assertThat(result.getActive()).isNull();
        assertThat(result.getLocationTypeName()).isNull();
        assertThat(result.getTimezone()).isNull();
    }

    // ─── validate ────────────────────────────────────────────────────────────

    @Test
    void validate_passesWhenRequiredFieldsPresent() {
        LocationRecord record = new LocationRecord();
        record.setName("Charlotte South");
        record.setCode("CLT-SOUTH");

        List<String> errors = strategy.validate(record);

        assertThat(errors).isEmpty();
    }

    @Test
    void validate_failsWhenNameBlank() {
        LocationRecord record = new LocationRecord();
        record.setName("  ");
        record.setCode("CLT-SOUTH");

        List<String> errors = strategy.validate(record);

        assertThat(errors).anyMatch(e -> e.contains("name"));
    }

    @Test
    void validate_failsWhenCodeBlank() {
        LocationRecord record = new LocationRecord();
        record.setName("Charlotte South");
        record.setCode("");

        List<String> errors = strategy.validate(record);

        assertThat(errors).anyMatch(e -> e.contains("code"));
    }

    // ─── getDomainType ────────────────────────────────────────────────────────

    @Test
    void getDomainType_returnsLocation() {
        assertThat(strategy.getDomainType()).isEqualTo(DomainType.LOCATION);
    }
}

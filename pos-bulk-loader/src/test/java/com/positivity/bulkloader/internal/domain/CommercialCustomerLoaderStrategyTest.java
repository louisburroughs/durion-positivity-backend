package com.positivity.bulkloader.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.bulkloader.internal.enums.DomainType;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

@SuppressWarnings({"java:S100", "java:S1192"})
class CommercialCustomerLoaderStrategyTest {

    private final CommercialCustomerLoaderStrategy strategy = new CommercialCustomerLoaderStrategy();

    // ─── mapRow ──────────────────────────────────────────────────────────────

    @Test
    void mapRow_populatesAllFields() {
        Map<String, String> row = Map.of(
                "legalName", "Piedmont Freight Carriers LLC",
                "displayName", "Piedmont Freight",
                "taxId", "27-4481203",
                "billingTermsId", "NET-30",
                "contactFirstName", "Greg",
                "contactLastName", "Whitfield",
                "contactEmail", "g.whitfield@piedmontfreight.example.com",
                "contactPhone", "+17045550188");

        CommercialCustomerRecord result = strategy.mapRow(row);

        assertThat(result.getLegalName()).isEqualTo("Piedmont Freight Carriers LLC");
        assertThat(result.getDisplayName()).isEqualTo("Piedmont Freight");
        assertThat(result.getTaxId()).isEqualTo("27-4481203");
        assertThat(result.getBillingTermsId()).isEqualTo("NET-30");
        assertThat(result.getContactFirstName()).isEqualTo("Greg");
        assertThat(result.getContactLastName()).isEqualTo("Whitfield");
        assertThat(result.getContactEmail()).isEqualTo("g.whitfield@piedmontfreight.example.com");
        assertThat(result.getContactPhone()).isEqualTo("+17045550188");
    }

    @Test
    void mapRow_handlesMinimalRow() {
        Map<String, String> row = Map.of("legalName", "Carolina Concrete Supply Co.");

        CommercialCustomerRecord result = strategy.mapRow(row);

        assertThat(result.getLegalName()).isEqualTo("Carolina Concrete Supply Co.");
        assertThat(result.getDisplayName()).isNull();
        assertThat(result.getTaxId()).isNull();
        assertThat(result.getBillingTermsId()).isNull();
        assertThat(result.getContactFirstName()).isNull();
        assertThat(result.getContactLastName()).isNull();
        assertThat(result.getContactEmail()).isNull();
        assertThat(result.getContactPhone()).isNull();
    }

    // ─── validate ────────────────────────────────────────────────────────────

    @Test
    void validate_passesWhenLegalNamePresent() {
        CommercialCustomerRecord record = new CommercialCustomerRecord();
        record.setLegalName("Piedmont Freight Carriers LLC");

        List<String> errors = strategy.validate(record);

        assertThat(errors).isEmpty();
    }

    @Test
    void validate_failsWhenLegalNameBlank() {
        CommercialCustomerRecord record = new CommercialCustomerRecord();
        record.setLegalName("  ");

        List<String> errors = strategy.validate(record);

        assertThat(errors).anyMatch(e -> e.contains("legalName"));
    }

    // ─── getDomainType ────────────────────────────────────────────────────────

    @Test
    void getDomainType_returnsCommercialCustomer() {
        assertThat(strategy.getDomainType()).isEqualTo(DomainType.COMMERCIAL_CUSTOMER);
    }
}

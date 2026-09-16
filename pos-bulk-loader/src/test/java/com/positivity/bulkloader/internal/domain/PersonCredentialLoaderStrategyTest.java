package com.positivity.bulkloader.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.bulkloader.internal.enums.DomainType;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code people/credentials.csv} → the credential ingest (CAP-328). The strategy only shapes and
 * validates; resolving the employee number and the vendor code is the endpoint's job, so an
 * unknown ASE code is a row rejection there, never a load of a skill nobody holds.
 */
@SuppressWarnings({"java:S100", "java:S1192"})
class PersonCredentialLoaderStrategyTest {

    private final PersonCredentialLoaderStrategy strategy = new PersonCredentialLoaderStrategy();

    private static Map<String, String> row(String... kv) {
        Map<String, String> row = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            row.put(kv[i], kv[i + 1]);
        }
        return row;
    }

    private static Map<String, String> aseRow() {
        return row(
                "employeeNumber", "EMP-0006",
                "skillCode", "",
                "sourceCode", "ASE",
                "sourceCredentialCode", "T4-BRAKES",
                "issuer", "ASE",
                "issuedOn", "2022-04-27",
                "expiresOn", "2027-04-27",
                "proficiency", "4",
                "evidenceRef", "");
    }

    @Test
    @DisplayName("an ASE row maps every column and validates clean; the domain is PERSON_CREDENTIAL")
    void aseRowIsValid() {
        PersonCredentialLoaderRecord record = strategy.mapRow(aseRow());

        assertThat(record.getEmployeeNumber()).isEqualTo("EMP-0006");
        assertThat(record.getSourceCredentialCode()).isEqualTo("T4-BRAKES");
        assertThat(record.getExpiresOn()).isEqualTo("2027-04-27");
        assertThat(strategy.validate(record)).isEmpty();
        assertThat(strategy.getDomainType()).isEqualTo(DomainType.PERSON_CREDENTIAL);
    }

    @Test
    @DisplayName("a Durion-coded row (DOT-INSPECTOR) needs an issuer but no vendor code; no expiry is fine")
    void skillCodeRowNeedsAnIssuer() {
        Map<String, String> dot = row(
                "employeeNumber", "EMP-0008", "skillCode", "DOT-INSPECTOR", "issuer", "SHOP", "issuedOn", "2025-03-01");
        assertThat(strategy.validate(strategy.mapRow(dot))).isEmpty();

        dot.put("issuer", "");
        assertThat(strategy.validate(strategy.mapRow(dot)))
                .singleElement()
                .asString()
                .contains("issuer");
    }

    @Test
    @DisplayName("a row naming the skill neither way, or without an employee or issue date, fails its row")
    void requiredFields() {
        List<String> errors = strategy.validate(strategy.mapRow(row("employeeNumber", "", "issuedOn", "")));
        assertThat(errors)
                .anySatisfy(e -> assertThat(e).contains("employeeNumber"))
                .anySatisfy(e -> assertThat(e).contains("skillCode or sourceCode"))
                .anySatisfy(e -> assertThat(e).contains("issuedOn"));
    }

    @Test
    @DisplayName("dates must be ISO-8601, proficiency 1-5 when present, evidenceRef a UUID when present")
    void formats() {
        Map<String, String> bad = aseRow();
        bad.put("issuedOn", "04/27/2022");
        bad.put("expiresOn", "never");
        bad.put("proficiency", "9");
        bad.put("evidenceRef", "doc-1");
        List<String> errors = strategy.validate(strategy.mapRow(bad));
        assertThat(errors)
                .anySatisfy(e -> assertThat(e).contains("issuedOn"))
                .anySatisfy(e -> assertThat(e).contains("expiresOn"))
                .anySatisfy(e -> assertThat(e).contains("proficiency"))
                .anySatisfy(e -> assertThat(e).contains("evidenceRef"));

        Map<String, String> notANumber = aseRow();
        notANumber.put("proficiency", "high");
        assertThat(strategy.validate(strategy.mapRow(notANumber)))
                .singleElement()
                .asString()
                .contains("whole number");
    }

    @Test
    @DisplayName("the deliberately expired fixture rows are valid rows — expiry is a fact, not an error")
    void expiredRowsAreValid() {
        Map<String, String> expired = aseRow();
        expired.put("employeeNumber", "EMP-0009");
        expired.put("sourceCredentialCode", "T8-PMI");
        expired.put("issuedOn", "2020-06-30");
        expired.put("expiresOn", "2025-06-30");
        assertThat(strategy.validate(strategy.mapRow(expired))).isEmpty();
    }
}

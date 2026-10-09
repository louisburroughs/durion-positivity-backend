package com.positivity.domainevents.tax;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.positivity.domainevents.DomainEventEnvelope;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

@DisplayName("TaxRegistrationChangedV1 (CAP:550 S32c)")
class TaxRegistrationChangedV1Test {

    private static final ObjectMapper MAPPER =
            JsonMapper.builder().findAndAddModules().build();

    private static final UUID REGISTRATION_ID = UUID.fromString("01990000-0000-7000-8000-0000000000a1");
    private static final UUID TENANT_ID = UUID.fromString("01990000-0000-7000-8000-0000000000b1");
    private static final Instant CHANGED_AT = Instant.parse("2026-10-08T12:00:00Z");
    private static final String NUMBER = "A123456789";

    private static TaxRegistrationChangedV1 fact(LocalDate effectiveTo) {
        return new TaxRegistrationChangedV1(
                REGISTRATION_ID,
                TENANT_ID,
                "ZZ",
                "R_1",
                NUMBER,
                "ZZ",
                LocalDate.of(2026, 1, 1),
                effectiveTo,
                "ACTIVE",
                3L,
                CHANGED_AT);
    }

    @Test
    @DisplayName("round-trips through JSON inside the envelope, open-ended and ended")
    void roundTrips() {
        for (TaxRegistrationChangedV1 original :
                new TaxRegistrationChangedV1[] {fact(null), fact(LocalDate.of(2026, 12, 31))}) {
            DomainEventEnvelope<TaxRegistrationChangedV1> envelope = new DomainEventEnvelope<>(
                    UUID.fromString("01990000-0000-7000-8000-0000000000c1"),
                    TaxRegistrationChangedV1.EVENT_TYPE,
                    TaxRegistrationChangedV1.SCHEMA_VERSION,
                    REGISTRATION_ID,
                    original.version(),
                    CHANGED_AT,
                    "pos-tax",
                    TENANT_ID,
                    null,
                    null,
                    original);
            String json = MAPPER.writeValueAsString(envelope);
            JsonNode node = MAPPER.readTree(json);

            TaxRegistrationChangedV1 read = MAPPER.treeToValue(node.path("payload"), TaxRegistrationChangedV1.class);

            assertThat(read).isEqualTo(original);
            assertThat(node.path("payload").path("effectiveFrom").stringValue(null))
                    .isEqualTo("2026-01-01");
            assertThat(node.path("eventType").stringValue(null)).isEqualTo("tax.registration.changed");
        }
    }

    @Test
    @DisplayName("toString never carries the registration number")
    void toStringMasksTheNumber() {
        assertThat(fact(null).toString())
                .contains("TaxRegistrationChangedV1", "R_1", "registrationNumber=****")
                .doesNotContain(NUMBER);
    }

    @Test
    @DisplayName("refuses an end before its start, a negative version and a blank number")
    void refusesInvalidValues() {
        assertThatThrownBy(() -> fact(LocalDate.of(2025, 12, 31)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("effectiveTo");
        assertThatThrownBy(() -> new TaxRegistrationChangedV1(
                        REGISTRATION_ID,
                        TENANT_ID,
                        "ZZ",
                        "R_1",
                        NUMBER,
                        "ZZ",
                        LocalDate.of(2026, 1, 1),
                        null,
                        "ACTIVE",
                        -1L,
                        CHANGED_AT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TaxRegistrationChangedV1(
                        REGISTRATION_ID,
                        TENANT_ID,
                        "ZZ",
                        "R_1",
                        " ",
                        "ZZ",
                        LocalDate.of(2026, 1, 1),
                        null,
                        "ACTIVE",
                        0L,
                        CHANGED_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining(NUMBER);
        assertThat(fact(LocalDate.of(2026, 1, 1)).effectiveTo())
                .as("a one-day registration")
                .isEqualTo(LocalDate.of(2026, 1, 1));
    }
}

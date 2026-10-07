package com.positivity.domainevents.accounting;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * CAP:550 S16 (#2512): a float kind a newer producer adds (a relocation, #2571) reads as UNKNOWN, so
 * a state-based consumer still applies the fact's amount and location.
 */
@DisplayName("RegisterFloatChangedV1 kind tolerates values this build does not know")
class RegisterFloatChangedV1Test {

    private static final ObjectMapper MAPPER =
            JsonMapper.builder().findAndAddModules().build();

    private static String fact(String kind) {
        return """
                {"registerId":"T-1","locationId":"01980a58-0000-7000-8000-0000000000a2","amount":250.00,
                 "previousAmount":200.00,"kind":"%s","effectiveDate":"2026-10-07",
                 "journalEntryId":"01980a58-0000-7000-8000-0000000000e1",
                 "previousLocationId":"01980a58-0000-7000-8000-0000000000a1"}
                """.formatted(kind);
    }

    @Test
    @DisplayName("an unknown kind reads as UNKNOWN with amount and location intact")
    void unknownKindReadsAsUnknown() {
        RegisterFloatChangedV1 read = MAPPER.readValue(fact("RELOCATION"), RegisterFloatChangedV1.class);

        assertThat(read.kind()).isEqualTo(RegisterFloatChangedV1.Kind.UNKNOWN);
        assertThat(read.amount()).isEqualByComparingTo("250.00");
        assertThat(read.locationId()).hasToString("01980a58-0000-7000-8000-0000000000a2");
    }

    @Test
    @DisplayName("known kinds read as themselves")
    void knownKindsRoundTrip() {
        for (RegisterFloatChangedV1.Kind kind : new RegisterFloatChangedV1.Kind[] {
            RegisterFloatChangedV1.Kind.GO_LIVE,
            RegisterFloatChangedV1.Kind.CHANGE,
            RegisterFloatChangedV1.Kind.REVERSAL
        }) {
            assertThat(MAPPER.readValue(fact(kind.name()), RegisterFloatChangedV1.class)
                            .kind())
                    .isEqualTo(kind);
        }
    }
}

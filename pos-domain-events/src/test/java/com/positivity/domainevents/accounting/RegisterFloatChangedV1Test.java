package com.positivity.domainevents.accounting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

@DisplayName("RegisterFloatChangedV1 relocation (additive, schema version 2; #2571)")
class RegisterFloatChangedV1Test {

    private static final ObjectMapper MAPPER =
            JsonMapper.builder().findAndAddModules().build();

    private static final UUID SHOP_A = UUID.fromString("019a0000-0000-7000-8000-00000000a001");
    private static final UUID SHOP_B = UUID.fromString("019a0000-0000-7000-8000-00000000a002");
    private static final UUID ENTRY = UUID.fromString("019a0000-0000-7000-8000-00000000e001");

    private static RegisterFloatChangedV1 fact(RegisterFloatChangedV1.Kind kind, UUID entry, UUID previousLocation) {
        return new RegisterFloatChangedV1(
                "T-1",
                SHOP_B,
                new BigDecimal("200.00"),
                new BigDecimal("200.00"),
                kind,
                LocalDate.of(2026, 10, 15),
                entry,
                previousLocation);
    }

    @Test
    @DisplayName("is schema version 2 and round-trips a relocation with its origin")
    void roundTripsARelocation() {
        RegisterFloatChangedV1 moved = fact(RegisterFloatChangedV1.Kind.RELOCATION, ENTRY, SHOP_A);

        RegisterFloatChangedV1 read = MAPPER.readValue(MAPPER.writeValueAsString(moved), RegisterFloatChangedV1.class);

        assertThat(RegisterFloatChangedV1.SCHEMA_VERSION).isEqualTo(2);
        assertThat(read).isEqualTo(moved);
        assertThat(read.previousLocationId()).isEqualTo(SHOP_A);
    }

    @Test
    @DisplayName("a version-1 payload, without previousLocationId, still parses with a null origin")
    void versionOnePayloadStillParses() {
        String v1 = """
                {"registerId":"T-1","locationId":"019a0000-0000-7000-8000-00000000a001","amount":200.00,
                 "previousAmount":0,"kind":"GO_LIVE","effectiveDate":"2026-10-01",
                 "journalEntryId":"019a0000-0000-7000-8000-00000000e001"}
                """;

        RegisterFloatChangedV1 read = MAPPER.readValue(v1, RegisterFloatChangedV1.class);

        assertThat(read.kind()).isEqualTo(RegisterFloatChangedV1.Kind.GO_LIVE);
        assertThat(read.previousLocationId()).isNull();
        assertThat(read.journalEntryId()).isEqualTo(ENTRY);
    }

    @Test
    @DisplayName("only the kinds that always post must name their entry: a zero-float relocation carries none")
    void nullEntryRule() {
        assertThat(fact(RegisterFloatChangedV1.Kind.RELOCATION, null, SHOP_A).journalEntryId())
                .isNull();
        for (RegisterFloatChangedV1.Kind posting : new RegisterFloatChangedV1.Kind[] {
            RegisterFloatChangedV1.Kind.GO_LIVE,
            RegisterFloatChangedV1.Kind.CHANGE,
            RegisterFloatChangedV1.Kind.REVERSAL
        }) {
            assertThatThrownBy(() -> fact(posting, null, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("journalEntryId");
        }
    }
}

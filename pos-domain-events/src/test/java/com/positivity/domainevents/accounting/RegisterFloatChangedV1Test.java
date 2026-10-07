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

/**
 * The float fact's wire contract. CAP:550 S16 (#2512): a kind a newer producer adds reads as UNKNOWN, so a
 * state-based consumer still applies the fact's amount and location. S38 (#2571): schema version 2 adds the
 * RELOCATION kind and {@code previousLocationId}, additively, and only the kinds that always post must name their
 * journal entry. #2577 (ADR-0067 R-1): schema version 3 adds {@code currencyCode}, additively; an older payload
 * without it still parses.
 */
@DisplayName("RegisterFloatChangedV1 (schema version 3; tolerant kinds, relocation, currencyCode)")
class RegisterFloatChangedV1Test {

    private static final ObjectMapper MAPPER =
            JsonMapper.builder().findAndAddModules().build();

    private static final UUID SHOP_A = UUID.fromString("019a0000-0000-7000-8000-00000000a001");
    private static final UUID SHOP_B = UUID.fromString("019a0000-0000-7000-8000-00000000a002");
    private static final UUID ENTRY = UUID.fromString("019a0000-0000-7000-8000-00000000e001");

    private static String json(String kind) {
        return """
                {"registerId":"T-1","locationId":"01980a58-0000-7000-8000-0000000000a2","amount":250.00,
                 "previousAmount":200.00,"kind":"%s","effectiveDate":"2026-10-07",
                 "journalEntryId":"01980a58-0000-7000-8000-0000000000e1",
                 "previousLocationId":"01980a58-0000-7000-8000-0000000000a1"}
                """.formatted(kind);
    }

    private static RegisterFloatChangedV1 fact(RegisterFloatChangedV1.Kind kind, UUID entry, UUID previousLocation) {
        return new RegisterFloatChangedV1(
                "T-1",
                SHOP_B,
                new BigDecimal("200.00"),
                new BigDecimal("200.00"),
                kind,
                LocalDate.of(2026, 10, 15),
                entry,
                previousLocation,
                "USD");
    }

    @Test
    @DisplayName("an unknown kind reads as UNKNOWN with amount and location intact")
    void unknownKindReadsAsUnknown() {
        RegisterFloatChangedV1 read =
                MAPPER.readValue(json("A_KIND_FROM_A_NEWER_PRODUCER"), RegisterFloatChangedV1.class);

        assertThat(read.kind()).isEqualTo(RegisterFloatChangedV1.Kind.UNKNOWN);
        assertThat(read.amount()).isEqualByComparingTo("250.00");
        assertThat(read.locationId()).hasToString("01980a58-0000-7000-8000-0000000000a2");
    }

    @Test
    @DisplayName("an unknown kind without a journal entry still reads: a tolerant reader never throws")
    void unknownKindWithoutAnEntryStillReads() {
        String withoutEntry = """
                {"registerId":"T-1","locationId":"01980a58-0000-7000-8000-0000000000a2","amount":250.00,
                 "previousAmount":250.00,"kind":"A_KIND_FROM_A_NEWER_PRODUCER","effectiveDate":"2026-10-07"}
                """;

        RegisterFloatChangedV1 read = MAPPER.readValue(withoutEntry, RegisterFloatChangedV1.class);

        assertThat(read.kind()).isEqualTo(RegisterFloatChangedV1.Kind.UNKNOWN);
        assertThat(read.journalEntryId()).isNull();
    }

    @Test
    @DisplayName("known kinds, RELOCATION included, read as themselves")
    void knownKindsRoundTrip() {
        for (RegisterFloatChangedV1.Kind kind : new RegisterFloatChangedV1.Kind[] {
            RegisterFloatChangedV1.Kind.GO_LIVE,
            RegisterFloatChangedV1.Kind.CHANGE,
            RegisterFloatChangedV1.Kind.REVERSAL,
            RegisterFloatChangedV1.Kind.RELOCATION
        }) {
            assertThat(MAPPER.readValue(json(kind.name()), RegisterFloatChangedV1.class)
                            .kind())
                    .isEqualTo(kind);
        }
    }

    @Test
    @DisplayName("is schema version 3 and round-trips a relocation with its origin and its currency")
    void roundTripsARelocation() {
        RegisterFloatChangedV1 moved = fact(RegisterFloatChangedV1.Kind.RELOCATION, ENTRY, SHOP_A);

        String wire = MAPPER.writeValueAsString(moved);
        RegisterFloatChangedV1 read = MAPPER.readValue(wire, RegisterFloatChangedV1.class);

        assertThat(RegisterFloatChangedV1.SCHEMA_VERSION).isEqualTo(3);
        assertThat(read).isEqualTo(moved);
        assertThat(read.previousLocationId()).isEqualTo(SHOP_A);
        assertThat(wire).contains("\"currencyCode\":\"USD\"");
        assertThat(read.currencyCode()).isEqualTo("USD");
    }

    @Test
    @DisplayName("#2577: a version-2 payload, without currencyCode, still parses with a null currency")
    void versionTwoPayloadStillParses() {
        RegisterFloatChangedV1 read = MAPPER.readValue(json("RELOCATION"), RegisterFloatChangedV1.class);

        assertThat(read.kind()).isEqualTo(RegisterFloatChangedV1.Kind.RELOCATION);
        assertThat(read.amount()).isEqualByComparingTo("250.00");
        assertThat(read.currencyCode()).isNull();
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
        assertThat(read.currencyCode()).isNull();
    }

    @Test
    @DisplayName("only the kinds that always post must name their entry: a zero-float relocation carries none")
    void nullEntryRule() {
        assertThat(fact(RegisterFloatChangedV1.Kind.RELOCATION, null, SHOP_A).journalEntryId())
                .isNull();
        assertThat(fact(RegisterFloatChangedV1.Kind.UNKNOWN, null, null).journalEntryId())
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

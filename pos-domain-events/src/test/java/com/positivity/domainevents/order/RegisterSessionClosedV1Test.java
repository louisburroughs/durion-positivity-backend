package com.positivity.domainevents.order;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * CAP:550 S16 (#2512, spec §4.6 and §6.5): the close fact carries per-movement detail at schema
 * version 2, in place on {@code order.events.v1}; version-1 messages and version-1 consumers keep
 * working (ADR-0044 §3).
 */
@DisplayName("RegisterSessionClosedV1 movements[] (schema version 2, additive)")
class RegisterSessionClosedV1Test {

    private static final ObjectMapper MAPPER =
            JsonMapper.builder().findAndAddModules().build();

    private static final UUID SESSION_ID = UUID.fromString("01980a58-0000-7000-8000-0000000000c1");
    private static final UUID MOVEMENT_ID = UUID.fromString("01980a58-0000-7000-8000-0000000000c2");
    private static final Instant OPENED = Instant.parse("2026-10-07T08:00:00Z");
    private static final Instant CLOSED = Instant.parse("2026-10-07T18:00:00Z");

    private static RegisterSessionClosedV1 versionTwo() {
        return new RegisterSessionClosedV1(
                SESSION_ID,
                "T-1",
                null,
                "opener",
                "closer",
                new BigDecimal("200.00"),
                new BigDecimal("170.00"),
                new BigDecimal("170.00"),
                new BigDecimal("0.00"),
                false,
                "USD",
                List.of(new RegisterSessionClosedV1.TenderTotal("CASH", new BigDecimal("0.00"))),
                new BigDecimal("-30.00"),
                OPENED,
                CLOSED,
                List.of(new RegisterSessionClosedV1.Movement(
                        MOVEMENT_ID,
                        "PETTY_EXPENSE",
                        RegisterSessionClosedV1.Movement.OUT,
                        new BigDecimal("30.00"),
                        "USD",
                        "SHOP_SUPPLIES",
                        null,
                        null,
                        "R-100",
                        "cashier",
                        UUID.fromString("01980a58-0000-7000-8000-0000000000c3"),
                        null,
                        OPENED.plusSeconds(60),
                        null,
                        List.of(),
                        null,
                        null,
                        null)));
    }

    @Test
    @DisplayName("the schema version is 2 on the same event type")
    void schemaVersionIsTwo() {
        assertThat(RegisterSessionClosedV1.SCHEMA_VERSION).isEqualTo(2);
        assertThat(RegisterSessionClosedV1.EVENT_TYPE).isEqualTo("order.session.closed");
    }

    @Test
    @DisplayName("a version-2 fact round-trips with its movements")
    void roundTripsMovements() {
        RegisterSessionClosedV1 fact = versionTwo();

        RegisterSessionClosedV1 read = MAPPER.readValue(MAPPER.writeValueAsString(fact), RegisterSessionClosedV1.class);

        assertThat(read).isEqualTo(fact);
        assertThat(read.movements()).singleElement().satisfies(movement -> {
            assertThat(movement.reason()).isEqualTo("PETTY_EXPENSE");
            assertThat(movement.categoryCode()).isEqualTo("SHOP_SUPPLIES");
            assertThat(movement.currencyCode()).isEqualTo("USD");
            assertThat(movement.clerkId()).isEqualTo("cashier");
        });
    }

    @Test
    @DisplayName("a version-1 payload without movements still deserialises, movements null")
    void versionOnePayloadStillParses() {
        String legacyJson = """
                {"sessionId":"01980a58-0000-7000-8000-0000000000c1","terminalId":"T-1",
                 "openedByClerkId":"opener","closedByClerkId":"closer",
                 "openingFloat":100.00,"countedCash":140.00,"theoreticalCash":150.00,
                 "overShort":-10.00,"varianceApproved":false,"currencyCode":"USD",
                 "tenderTotals":[],"cashMovementTotal":0.00,
                 "openedAt":"2026-07-23T08:00:00Z","closedAt":"2026-07-23T18:30:00Z"}
                """;

        RegisterSessionClosedV1 read = MAPPER.readValue(legacyJson, RegisterSessionClosedV1.class);

        assertThat(read.movements()).isNull();
        assertThat(read.overShort()).isEqualByComparingTo("-10.00");
    }

    /** The version-1 shape, as a consumer compiled before S16 declares it. */
    record VersionOneShape(
            UUID sessionId,
            String terminalId,
            BigDecimal openingFloat,
            BigDecimal overShort,
            BigDecimal cashMovementTotal,
            Instant closedAt) {}

    @Test
    @DisplayName("a consumer of the version-1 shape reads a version-2 message unchanged")
    void versionOneConsumerReadsVersionTwo() {
        String json = MAPPER.writeValueAsString(versionTwo());

        VersionOneShape read = MAPPER.readValue(json, VersionOneShape.class);

        assertThat(read.sessionId()).isEqualTo(SESSION_ID);
        assertThat(read.openingFloat()).isEqualByComparingTo("200.00");
        assertThat(read.cashMovementTotal()).isEqualByComparingTo("-30.00");
        assertThat(read.closedAt()).isEqualTo(CLOSED);
    }

    // ── CAP:550 S32d (#2639): five additive fields, still schema version 2 ─────────────────────

    private static RegisterSessionClosedV1.Movement statedTaxMovement(Boolean required) {
        return new RegisterSessionClosedV1.Movement(
                MOVEMENT_ID,
                "PETTY_EXPENSE",
                RegisterSessionClosedV1.Movement.OUT,
                new BigDecimal("40.00"),
                "CAD",
                "SHOP_SUPPLIES",
                null,
                null,
                "R-200",
                "cashier",
                null,
                null,
                OPENED.plusSeconds(120),
                "Corner Hardware",
                List.of(new RegisterSessionClosedV1.StatedTax("GST_HST", new BigDecimal("4.60"))),
                "000000000RT0001",
                RegisterSessionClosedV1.Movement.PLAUSIBLE,
                required);
    }

    private static RegisterSessionClosedV1 withMovement(RegisterSessionClosedV1.Movement movement) {
        RegisterSessionClosedV1 base = versionTwo();
        return new RegisterSessionClosedV1(
                base.sessionId(),
                base.terminalId(),
                base.locationId(),
                base.openedByClerkId(),
                base.closedByClerkId(),
                base.openingFloat(),
                base.countedCash(),
                base.theoreticalCash(),
                base.overShort(),
                base.varianceApproved(),
                "CAD",
                base.tenderTotals(),
                base.cashMovementTotal(),
                base.openedAt(),
                base.closedAt(),
                List.of(movement));
    }

    @Test
    @DisplayName("S32d: the stated-tax fields round-trip and the schema version stays 2")
    void statedTaxFieldsRoundTrip() {
        RegisterSessionClosedV1 fact = withMovement(statedTaxMovement(Boolean.TRUE));

        RegisterSessionClosedV1 read = MAPPER.readValue(MAPPER.writeValueAsString(fact), RegisterSessionClosedV1.class);

        assertThat(RegisterSessionClosedV1.SCHEMA_VERSION).isEqualTo(2);
        assertThat(read).isEqualTo(fact);
        assertThat(read.movements()).singleElement().satisfies(movement -> {
            assertThat(movement.statedTaxes())
                    .containsExactly(new RegisterSessionClosedV1.StatedTax("GST_HST", new BigDecimal("4.60")));
            assertThat(movement.taxPlausibility()).isEqualTo("PLAUSIBLE");
            assertThat(movement.supplierRegistrationRequired()).isTrue();
        });
    }

    @Test
    @DisplayName("S32d: supplierRegistrationRequired round-trips null, never a default false")
    void supplierRegistrationRequiredRoundTripsNull() {
        RegisterSessionClosedV1 fact = withMovement(statedTaxMovement(null));

        RegisterSessionClosedV1 read = MAPPER.readValue(MAPPER.writeValueAsString(fact), RegisterSessionClosedV1.class);

        assertThat(read.movements().getFirst().supplierRegistrationRequired()).isNull();
    }

    @Test
    @DisplayName("S32d: a version-2 message produced before S32d reads with the new fields null")
    void preS32dVersionTwoPayloadReadsNewFieldsNull() {
        String json = """
                {"sessionId":"01980a58-0000-7000-8000-0000000000c1","terminalId":"T-1",
                 "openedByClerkId":"opener","closedByClerkId":"closer",
                 "openingFloat":200.00,"countedCash":170.00,"theoreticalCash":170.00,
                 "overShort":0.00,"varianceApproved":false,"currencyCode":"USD",
                 "tenderTotals":[],"cashMovementTotal":-30.00,
                 "openedAt":"2026-10-07T08:00:00Z","closedAt":"2026-10-07T18:00:00Z",
                 "movements":[{"movementId":"01980a58-0000-7000-8000-0000000000c2","reason":"PETTY_EXPENSE",
                   "direction":"OUT","amount":30.00,"currencyCode":"USD","categoryCode":"SHOP_SUPPLIES",
                   "receiptReference":"R-100","clerkId":"cashier","occurredAt":"2026-10-07T08:01:00Z"}]}
                """;

        RegisterSessionClosedV1 read = MAPPER.readValue(json, RegisterSessionClosedV1.class);

        assertThat(read.movements()).singleElement().satisfies(movement -> {
            assertThat(movement.statedTaxes()).isNull();
            assertThat(movement.supplierName()).isNull();
            assertThat(movement.supplierRegistrationNumber()).isNull();
            assertThat(movement.taxPlausibility()).isNull();
            assertThat(movement.supplierRegistrationRequired()).isNull();
        });
    }

    /** The S16 movement shape, as a consumer compiled before S32d declares it. */
    record PreS32dMovement(UUID movementId, String reason, BigDecimal amount, String categoryCode) {}

    /** The S16 fact shape, as a consumer compiled before S32d declares it. */
    record PreS32dShape(UUID sessionId, List<PreS32dMovement> movements) {}

    @Test
    @DisplayName("S32d: a consumer built before S32d reads a message carrying the new fields")
    void preS32dConsumerReadsNewFields() {
        String json = MAPPER.writeValueAsString(withMovement(statedTaxMovement(Boolean.FALSE)));

        PreS32dShape read = MAPPER.readValue(json, PreS32dShape.class);

        assertThat(read.movements()).singleElement().satisfies(movement -> {
            assertThat(movement.movementId()).isEqualTo(MOVEMENT_ID);
            assertThat(movement.amount()).isEqualByComparingTo("40.00");
        });
    }

    @Test
    @DisplayName("S32d item 7: the movement's toString carries neither the supplier's name nor its number")
    void movementToStringRedactsSupplier() {
        String text = statedTaxMovement(Boolean.TRUE).toString();

        assertThat(text)
                .doesNotContain("Corner Hardware")
                .doesNotContain("000000000RT0001")
                .contains("supplierNameProvided=true")
                .contains("supplierRegistrationNumberProvided=true")
                .contains("GST_HST");
        assertThat(withMovement(statedTaxMovement(Boolean.TRUE)).toString())
                .doesNotContain("Corner Hardware")
                .doesNotContain("000000000RT0001");
    }

    @Test
    @DisplayName("S32d: the movement carries no well-formed flag")
    void movementCarriesNoWellFormedFlag() {
        assertThat(java.util.Arrays.stream(RegisterSessionClosedV1.Movement.class.getRecordComponents())
                        .map(java.lang.reflect.RecordComponent::getName))
                .noneMatch(name -> name.toLowerCase(java.util.Locale.ROOT).contains("wellformed"))
                .endsWith(
                        "supplierName",
                        "statedTaxes",
                        "supplierRegistrationNumber",
                        "taxPlausibility",
                        "supplierRegistrationRequired");
    }
}

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
                        "SHOP_SUPPLIES",
                        null,
                        null,
                        "R-100",
                        "cashier",
                        null,
                        OPENED.plusSeconds(60))));
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
}

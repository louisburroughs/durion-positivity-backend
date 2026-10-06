package com.positivity.domainevents.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * CAP:550 S9 (spec §4.4 item 1, AW12/AW13): {@code partyId} is non-null from schema version 2,
 * but the record stays null-tolerant so version-1 facts published before go-live still parse.
 */
@DisplayName("PaymentSettledV1 partyId (schema version 2, null-tolerant for version 1)")
class PaymentSettledV1Test {

    private static final ObjectMapper MAPPER =
            JsonMapper.builder().findAndAddModules().build();

    private static final UUID PAYMENT_INTENT_ID = UUID.fromString("01980a58-0000-7000-8000-0000000000b1");
    private static final UUID INVOICE_ID = UUID.fromString("01980a58-0000-7000-8000-0000000000b2");
    private static final String PARTY_ID = "01980a58-0000-7000-8000-0000000000a1";

    @Test
    @DisplayName("the schema version is 2, bumped in place on payment.events.v1")
    void schemaVersionIsTwo() {
        assertThat(PaymentSettledV1.SCHEMA_VERSION).isEqualTo(2);
        assertThat(PaymentSettledV1.EVENT_TYPE).isEqualTo("payment.payment.settled");
    }

    @Test
    @DisplayName("a version-2 fact round-trips with its party")
    void roundTripsParty() {
        PaymentSettledV1 fact = new PaymentSettledV1(
                PAYMENT_INTENT_ID,
                INVOICE_ID,
                "INV-000001",
                null,
                null,
                PARTY_ID,
                "CARD",
                new BigDecimal("98.51"),
                "USD",
                "stub",
                "ref-1",
                Instant.parse("2026-10-05T12:00:00Z"));

        PaymentSettledV1 read = MAPPER.readValue(MAPPER.writeValueAsString(fact), PaymentSettledV1.class);

        assertThat(read).isEqualTo(fact);
        assertThat(read.partyId()).isEqualTo(PARTY_ID);
    }

    @Test
    @DisplayName("a version-1 envelope payload with \"partyId\": null still deserialises (AW13, ADR-0044 §3)")
    void versionOnePayloadWithNullPartyStillParses() {
        String legacyJson = """
                {"paymentIntentId":"01980a58-0000-7000-8000-0000000000b1",
                 "invoiceId":"01980a58-0000-7000-8000-0000000000b2",
                 "invoiceNumber":"INV-000001","orderId":null,"workorderId":null,
                 "partyId":null,"methodType":"CARD","amount":98.51,"currencyCode":"USD",
                 "gatewayProvider":"stub","gatewayReference":"ref-1",
                 "settledAt":"2026-01-05T12:00:00Z"}
                """;

        assertThatCode(() -> MAPPER.readValue(legacyJson, PaymentSettledV1.class))
                .doesNotThrowAnyException();
        PaymentSettledV1 read = MAPPER.readValue(legacyJson, PaymentSettledV1.class);
        assertThat(read.paymentIntentId()).isEqualTo(PAYMENT_INTENT_ID);
        assertThat(read.partyId()).isNull();
    }

    @Test
    @DisplayName("a version-1 payload that omits partyId entirely still deserialises")
    void versionOnePayloadWithoutPartyFieldStillParses() {
        String legacyJson = """
                {"paymentIntentId":"01980a58-0000-7000-8000-0000000000b1",
                 "invoiceId":"01980a58-0000-7000-8000-0000000000b2",
                 "methodType":"CARD","amount":98.51,"currencyCode":"USD",
                 "settledAt":"2026-01-05T12:00:00Z"}
                """;

        PaymentSettledV1 read = MAPPER.readValue(legacyJson, PaymentSettledV1.class);

        assertThat(read.partyId()).isNull();
        assertThat(read.amount()).isEqualByComparingTo("98.51");
    }
}

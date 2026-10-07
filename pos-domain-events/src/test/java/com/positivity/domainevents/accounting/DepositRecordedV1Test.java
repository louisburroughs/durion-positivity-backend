package com.positivity.domainevents.accounting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * The deposit fact's wire contract (CAP:550 S18, #2514): it round-trips, states its currency, names at least one
 * session and a positive amount, and a status a newer producer adds reads as UNKNOWN.
 */
@DisplayName("DepositRecordedV1 (accounting.deposit.recorded v1)")
class DepositRecordedV1Test {

    private static final ObjectMapper MAPPER =
            JsonMapper.builder().findAndAddModules().build();

    private static final UUID DEPOSIT = UUID.fromString("019a0000-0000-7000-8000-00000000d001");
    private static final UUID BANK = UUID.fromString("019a0000-0000-7000-8000-00000000b000");
    private static final UUID SESSION = UUID.fromString("019a0000-0000-7000-8000-00000000c001");
    private static final UUID ENTRY = UUID.fromString("019a0000-0000-7000-8000-00000000e001");

    private static DepositRecordedV1 fact(
            BigDecimal amount, String currency, List<UUID> sessions, DepositRecordedV1.Status status) {
        return new DepositRecordedV1(
                DEPOSIT, BANK, LocalDate.of(2026, 10, 8), amount, currency, sessions, status, ENTRY);
    }

    @Test
    @DisplayName("round-trips through JSON with its status and currency")
    void roundTrips() {
        DepositRecordedV1 fact =
                fact(new BigDecimal("1197.00"), "USD", List.of(SESSION), DepositRecordedV1.Status.REVERSED);

        DepositRecordedV1 read = MAPPER.readValue(MAPPER.writeValueAsString(fact), DepositRecordedV1.class);

        assertThat(read).isEqualTo(fact);
        assertThat(read.status()).isEqualTo(DepositRecordedV1.Status.REVERSED);
        assertThat(read.currencyCode()).isEqualTo("USD");
        assertThat(DepositRecordedV1.EVENT_TYPE).isEqualTo("accounting.deposit.recorded");
        assertThat(DepositRecordedV1.SCHEMA_VERSION).isEqualTo(1);
    }

    @Test
    @DisplayName("a status a newer producer adds reads as UNKNOWN")
    void unknownStatusReadsAsUnknown() {
        String json = """
                {"depositId":"%s","bankGlAccountId":"%s","depositDate":"2026-10-08","amount":1197.00,
                 "currencyCode":"USD","sessionIds":["%s"],"status":"PARTLY_RETURNED","journalEntryId":"%s"}
                """.formatted(DEPOSIT, BANK, SESSION, ENTRY);

        assertThat(MAPPER.readValue(json, DepositRecordedV1.class).status())
                .isEqualTo(DepositRecordedV1.Status.UNKNOWN);
    }

    @Test
    @DisplayName("refuses no session, a non-positive amount and a currency that is not three upper-case letters")
    void refusesAnInvalidFact() {
        assertThatThrownBy(() -> fact(BigDecimal.ONE, "USD", List.of(), DepositRecordedV1.Status.RECORDED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sessionIds");
        assertThatThrownBy(() -> fact(BigDecimal.ZERO, "USD", List.of(SESSION), DepositRecordedV1.Status.RECORDED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("amount");
        assertThatThrownBy(() -> fact(BigDecimal.ONE, "usd", List.of(SESSION), DepositRecordedV1.Status.RECORDED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("currencyCode");
    }
}

package com.positivity.accounting.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.config.LedgerCurrency;
import com.positivity.accounting.internal.entity.UndepositedSession;
import com.positivity.accounting.internal.entity.UndepositedSessionDrop;
import com.positivity.accounting.internal.enums.UndepositedSessionStatus;
import com.positivity.accounting.internal.repository.UndepositedSessionDropRepository;
import com.positivity.accounting.internal.repository.UndepositedSessionRepository;
import com.positivity.domainevents.order.RegisterSessionClosedV1;
import com.positivity.domainevents.order.RegisterSessionClosedV1.Movement;
import com.positivity.domainevents.order.RegisterSessionClosedV1.TenderTotal;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * The undeposited-sessions read model from the close fact (CAP:550 S18, #2514): a schema-2 fact in functional currency
 * writes one row with its drops, expected cash (the CASH tender total) and clearing net (the over/short plus each
 * petty expense's credit to 1095); a schema-1 fact, a held currency or a redelivery writes none.
 */
@DisplayName("Undeposited sessions from the close fact (#2514)")
class UndepositedSessionProjectionTest {

    private static final UUID LOCATION = UUID.fromString("019a0000-0000-7000-8000-00000000b001");
    private static final Instant CLOSED_AT = Instant.parse("2026-10-07T22:00:00Z");

    private final UndepositedSessionRepository sessions = mock(UndepositedSessionRepository.class);
    private final UndepositedSessionDropRepository drops = mock(UndepositedSessionDropRepository.class);
    private final List<UndepositedSessionDrop> savedDrops = new ArrayList<>();
    private UndepositedSessionProjection projection;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        projection = new UndepositedSessionProjection(sessions, drops, new LedgerCurrency("USD"));
        when(sessions.saveAndFlush(any())).thenAnswer(invocation -> {
            UndepositedSession row = invocation.getArgument(0);
            row.setUndepositedSessionId(UUID.randomUUID());
            return row;
        });
        when(drops.saveAll(any())).thenAnswer(invocation -> {
            Iterable<UndepositedSessionDrop> rows = invocation.getArgument(0);
            rows.forEach(savedDrops::add);
            return savedDrops;
        });
    }

    @Test
    @DisplayName("AC1: cash sales 1,240.00, petty 40.00, a short of 3.00 and a drop of 1,197.00 in bag B-0912 write"
            + " depositAmount 1,197.00, expectedCash 1,240.00 and clearingNet -43.00, UNDEPOSITED")
    void workedExample() {
        UUID drop = UUID.randomUUID();
        RegisterSessionClosedV1 fact = fact(
                "USD",
                "-3.00",
                List.of(cash("1240.00"), new TenderTotal("CARD", new BigDecimal("812.50"))),
                petty("40.00"),
                bankDrop(drop, "1197.00", "B-0912", "USD"),
                movement("FLOAT_INCREASE", "IN", "50.00", null));

        assertThat(projection.record(fact, 2)).isTrue();

        UndepositedSession row = savedSession();
        assertThat(row.getSessionId()).isEqualTo(fact.sessionId());
        assertThat(row.getTerminalId()).isEqualTo("T-7");
        assertThat(row.getLocationId()).isEqualTo(LOCATION);
        assertThat(row.getClosedAt()).isEqualTo(CLOSED_AT);
        assertThat(row.getDepositAmount()).isEqualByComparingTo("1197.00");
        assertThat(row.getExpectedCash()).isEqualByComparingTo("1240.00");
        assertThat(row.getClearingNet()).isEqualByComparingTo("-43.00");
        assertThat(row.getOverShort()).isEqualByComparingTo("-3.00");
        assertThat(row.getCurrencyCode()).isEqualTo("USD");
        assertThat(row.getStatus()).isEqualTo(UndepositedSessionStatus.UNDEPOSITED);
        assertThat(row.getDepositId()).isNull();
        assertThat(savedDrops).singleElement().satisfies(saved -> {
            assertThat(saved.getMovementId()).isEqualTo(drop);
            assertThat(saved.getBagNumber()).isEqualTo("B-0912");
            assertThat(saved.getAmount()).isEqualByComparingTo("1197.00");
            assertThat(saved.getUndepositedSessionId()).isEqualTo(row.getUndepositedSessionId());
        });
    }

    @Test
    @DisplayName("AC7: a session with card tenders only has expected cash 0; an over posts a debit clearing net")
    void cardOnlySessionHasNoExpectedCash() {
        RegisterSessionClosedV1 fact = fact(
                "USD",
                "2.00",
                List.of(
                        new TenderTotal("CARD", new BigDecimal("500.00")),
                        new TenderTotal("ON_ACCOUNT", BigDecimal.TEN)));

        assertThat(projection.record(fact, 2)).isTrue();

        UndepositedSession row = savedSession();
        assertThat(row.getExpectedCash()).isEqualByComparingTo("0");
        assertThat(row.getDepositAmount()).isEqualByComparingTo("0");
        assertThat(row.getClearingNet()).isEqualByComparingTo("2.00");
        assertThat(row.getStatus())
                .as("an over leaves a clearing net to deposit")
                .isEqualTo(UndepositedSessionStatus.UNDEPOSITED);
        assertThat(savedDrops).isEmpty();
    }

    @Test
    @DisplayName("review MINOR-2: a card-only session with no over/short has nothing to deposit: NOTHING_TO_DEPOSIT")
    void cardOnlySessionWithoutVarianceHasNothingToDeposit() {
        RegisterSessionClosedV1 fact = fact(
                "USD",
                "0.00",
                List.of(new TenderTotal("CARD", new BigDecimal("500.00"))),
                movement("FLOAT_INCREASE", "IN", "50.00", null));

        assertThat(projection.record(fact, 2)).isTrue();

        UndepositedSession row = savedSession();
        assertThat(row.getStatus()).isEqualTo(UndepositedSessionStatus.NOTHING_TO_DEPOSIT);
        assertThat(row.getDepositId()).isNull();
    }

    @Test
    @DisplayName("a schema-1 fact, a session closed in another currency or with a drop in one, and a redelivery"
            + " write no row")
    void noRow() {
        RegisterSessionClosedV1 usd =
                fact("USD", "0.00", List.of(cash("100.00")), bankDrop(UUID.randomUUID(), "100.00", "B-1", "USD"));
        assertThat(projection.record(usd, 1)).as("schema 1").isFalse();
        assertThat(projection.record(fact("CAD", "0.00", List.of(cash("100.00"))), 2))
                .as("session in CAD")
                .isFalse();
        assertThat(projection.record(
                        fact(
                                "USD",
                                "0.00",
                                List.of(cash("100.00")),
                                bankDrop(UUID.randomUUID(), "100.00", "B-1", "CAD")),
                        2))
                .as("drop in CAD")
                .isFalse();
        when(sessions.existsBySessionId(usd.sessionId())).thenReturn(true);
        assertThat(projection.record(usd, 2)).as("redelivery").isFalse();
        RegisterSessionClosedV1 schemaOneShape = new RegisterSessionClosedV1(
                UUID.randomUUID(),
                "T-7",
                LOCATION,
                "c",
                "c",
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                false,
                "USD",
                List.of(),
                BigDecimal.ZERO,
                CLOSED_AT,
                CLOSED_AT,
                null);
        assertThat(projection.record(schemaOneShape, 1))
                .as("schema 1 without movements")
                .isFalse();
        assertThatThrownBy(() -> projection.record(schemaOneShape, 2))
                .as("schema 2 without its movements list goes to retry / DLQ, never marked done without a row")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no movements list");

        verify(sessions, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("a bank drop that breaks the fact's contract fails the session for retry / DLQ")
    void malformedDropFails() {
        assertThatThrownBy(() -> projection.record(
                        fact("USD", "0.00", List.of(), movement("BANK_DROP", "IN", "10.00", "B-1")), 2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("OUT");
        UUID twice = UUID.randomUUID();
        assertThatThrownBy(() -> projection.record(
                        fact(
                                "USD",
                                "0.00",
                                List.of(),
                                bankDrop(twice, "10.00", "B-1", "USD"),
                                bankDrop(twice, "10.00", "B-1", "USD")),
                        2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("more than once");
        verify(sessions, never()).saveAndFlush(any());
    }

    private UndepositedSession savedSession() {
        ArgumentCaptor<UndepositedSession> saved = ArgumentCaptor.forClass(UndepositedSession.class);
        verify(sessions).saveAndFlush(saved.capture());
        return saved.getValue();
    }

    private static TenderTotal cash(String amount) {
        return new TenderTotal("CASH", new BigDecimal(amount));
    }

    private static Movement petty(String amount) {
        return new Movement(
                UUID.randomUUID(),
                "PETTY_EXPENSE",
                "OUT",
                new BigDecimal(amount),
                "USD",
                "SHOP_SUPPLIES",
                null,
                null,
                "R-1",
                "clerk-1",
                null,
                null,
                CLOSED_AT.minusSeconds(3600));
    }

    private static Movement bankDrop(UUID id, String amount, String bag, String currency) {
        return new Movement(
                id,
                "BANK_DROP",
                "OUT",
                new BigDecimal(amount),
                currency,
                null,
                null,
                bag,
                null,
                "clerk-1",
                null,
                null,
                CLOSED_AT.minusSeconds(600));
    }

    private static Movement movement(String reason, String direction, String amount, String bag) {
        return new Movement(
                UUID.randomUUID(),
                reason,
                direction,
                new BigDecimal(amount),
                "USD",
                null,
                null,
                bag,
                null,
                "clerk-1",
                null,
                null,
                CLOSED_AT.minusSeconds(1200));
    }

    private static RegisterSessionClosedV1 fact(
            String currency, String overShort, List<TenderTotal> tenders, Movement... movements) {
        return new RegisterSessionClosedV1(
                UUID.randomUUID(),
                "T-7",
                LOCATION,
                "clerk-1",
                "clerk-2",
                new BigDecimal("200.00"),
                new BigDecimal("200.00"),
                new BigDecimal("200.00").subtract(new BigDecimal(overShort)),
                new BigDecimal(overShort),
                false,
                currency,
                tenders,
                BigDecimal.ZERO,
                CLOSED_AT.minusSeconds(28_800),
                CLOSED_AT,
                Arrays.asList(movements));
    }
}

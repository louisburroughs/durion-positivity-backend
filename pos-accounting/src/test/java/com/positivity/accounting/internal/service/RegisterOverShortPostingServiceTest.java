package com.positivity.accounting.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.config.LedgerCurrency;
import com.positivity.domainevents.order.RegisterSessionClosedV1;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link RegisterOverShortPostingService} (odoo-parity G3, issue #1083): direction
 * routing (shortage vs overage), account resolution through the {@code REGISTER_OVER_SHORT}
 * category, business-time transaction date, zero-variance skip, and sessionId-keyed idempotency.
 */
class RegisterOverShortPostingServiceTest {

    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2026-07-23T12:00:00Z"), ZoneOffset.UTC);
    private static final UUID SESSION_ID = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID CASH_SHORT = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID CASH_OVER = UUID.fromString("00000000-0000-0000-0000-00000000000c");
    private static final UUID CASH_CLEARING = UUID.fromString("00000000-0000-0000-0000-00000000000d");
    private static final UUID JOURNAL_ENTRY_ID = UUID.fromString("00000000-0000-0000-0000-00000000000e");
    private static final Instant CLOSED_AT = Instant.parse("2026-07-23T18:30:00Z");
    private static final String KEY = "REGISTER_OVER_SHORT_GL_POSTING:" + SESSION_ID;

    private static final String ENVELOPE_EVENT_ID = "01960003-0000-7000-8000-0000000000e2";

    private final IdempotencyService idempotencyService = mock(IdempotencyService.class);
    private final GLMappingResolver glMappingResolver = mock(GLMappingResolver.class);
    private final GLPostingService glPostingService = mock(GLPostingService.class);
    private final KafkaFactIngestionRecorder ingestionRecorder = mock(KafkaFactIngestionRecorder.class);

    private RegisterOverShortPostingService service;

    @BeforeEach
    void setUp() {
        service = new RegisterOverShortPostingService(
                com.positivity.accounting.internal.service.TestZoneResolvers.utc(TEST_CLOCK),
                idempotencyService,
                glMappingResolver,
                glPostingService,
                new LedgerCurrency("USD"),
                ingestionRecorder);
    }

    private RegisterSessionClosedV1 fact(BigDecimal overShort, BigDecimal counted, BigDecimal theoretical) {
        return fact(overShort, counted, theoretical, "USD");
    }

    private RegisterSessionClosedV1 fact(
            BigDecimal overShort, BigDecimal counted, BigDecimal theoretical, String currencyCode) {
        return new RegisterSessionClosedV1(
                SESSION_ID,
                "terminal-1",
                UUID.fromString("00000000-0000-0000-0000-0000000000aa"),
                "clerk-1",
                "clerk-2",
                new BigDecimal("100.00"),
                counted,
                theoretical,
                overShort,
                false,
                currencyCode,
                List.of(new RegisterSessionClosedV1.TenderTotal("CASH", new BigDecimal("50.00"))),
                BigDecimal.ZERO,
                Instant.parse("2026-07-23T08:00:00Z"),
                CLOSED_AT);
    }

    private UUID postedEntry() {
        return JOURNAL_ENTRY_ID;
    }

    @Test
    @DisplayName("Shortage posts Dr Cash Short / Cr Register Cash Clearing for abs(overShort)")
    void shortagePostsShortDebitClearingCredit() {
        LocalDateTime expectedDate = LocalDateTime.ofInstant(CLOSED_AT, ZoneOffset.UTC);
        when(idempotencyService.isKeyProcessed(KEY)).thenReturn(false);
        when(glMappingResolver.resolveGLAccount("REGISTER_OVER_SHORT", "CASH_SHORT", expectedDate))
                .thenReturn(CASH_SHORT);
        when(glMappingResolver.resolveGLAccount("REGISTER_OVER_SHORT", "CASH_CLEARING", expectedDate))
                .thenReturn(CASH_CLEARING);
        when(glPostingService.postRegisterOverShort(any(), any(), any(), any(), any(), any(), anyString(), any()))
                .thenReturn(postedEntry());

        assertThat(service.postOverShort(
                        fact(new BigDecimal("-10.00"), new BigDecimal("140.00"), new BigDecimal("150.00")),
                        ENVELOPE_EVENT_ID))
                .isEqualTo(FactPostingOutcome.posted(JOURNAL_ENTRY_ID));

        verify(glPostingService)
                .postRegisterOverShort(
                        eq(RegisterOverShortPostingService.toSourceEventId(SESSION_ID)),
                        eq(SESSION_ID),
                        eq(CASH_SHORT),
                        eq(CASH_CLEARING),
                        eq(new BigDecimal("10.00")),
                        eq(expectedDate),
                        anyString(),
                        eq(null));
        verify(idempotencyService).registerKey(KEY, JOURNAL_ENTRY_ID);
    }

    @Test
    @DisplayName("Overage posts Dr Register Cash Clearing / Cr Cash Over for abs(overShort)")
    void overagePostsClearingDebitOverCredit() {
        LocalDateTime expectedDate = LocalDateTime.ofInstant(CLOSED_AT, ZoneOffset.UTC);
        when(idempotencyService.isKeyProcessed(KEY)).thenReturn(false);
        when(glMappingResolver.resolveGLAccount("REGISTER_OVER_SHORT", "CASH_CLEARING", expectedDate))
                .thenReturn(CASH_CLEARING);
        when(glMappingResolver.resolveGLAccount("REGISTER_OVER_SHORT", "CASH_OVER", expectedDate))
                .thenReturn(CASH_OVER);
        when(glPostingService.postRegisterOverShort(any(), any(), any(), any(), any(), any(), anyString(), any()))
                .thenReturn(postedEntry());

        service.postOverShort(
                fact(new BigDecimal("7.50"), new BigDecimal("157.50"), new BigDecimal("150.00")), ENVELOPE_EVENT_ID);

        verify(glPostingService)
                .postRegisterOverShort(
                        eq(RegisterOverShortPostingService.toSourceEventId(SESSION_ID)),
                        eq(SESSION_ID),
                        eq(CASH_CLEARING),
                        eq(CASH_OVER),
                        eq(new BigDecimal("7.50")),
                        eq(expectedDate),
                        anyString(),
                        eq(null));
        verify(idempotencyService).registerKey(KEY, JOURNAL_ENTRY_ID);
    }

    @Test
    @DisplayName("Zero-variance close posts nothing")
    void zeroVariancePostsNothing() {
        assertThat(service.postOverShort(
                        fact(BigDecimal.ZERO, new BigDecimal("150.00"), new BigDecimal("150.00")), ENVELOPE_EVENT_ID))
                .isEqualTo(FactPostingOutcome.nothingToPost());

        verify(glPostingService, never())
                .postRegisterOverShort(any(), any(), any(), any(), any(), any(), anyString(), any());
        verify(idempotencyService, never()).registerKey(anyString(), any());
    }

    @Test
    @DisplayName("Already-processed session key is a no-op — exactly once per sessionId")
    void replayedSessionIsNoOp() {
        when(idempotencyService.isKeyProcessed(KEY)).thenReturn(true);

        assertThat(service.postOverShort(
                        fact(new BigDecimal("-10.00"), new BigDecimal("140.00"), new BigDecimal("150.00")),
                        ENVELOPE_EVENT_ID))
                .isEqualTo(new FactPostingOutcome.AlreadyPosted(
                        null, RegisterOverShortPostingService.toSourceEventId(SESSION_ID)));

        verify(glPostingService, never())
                .postRegisterOverShort(any(), any(), any(), any(), any(), any(), anyString(), any());
        verify(idempotencyService, never()).registerKey(anyString(), any());
    }

    @Test
    @DisplayName("A variance in another currency is held with a currency reason, never posted at par (#2312)")
    void foreignCurrencyVarianceIsHeldNotPosted() {
        when(idempotencyService.isKeyProcessed(KEY)).thenReturn(false);
        RegisterSessionClosedV1 eurFact =
                fact(new BigDecimal("-10.00"), new BigDecimal("140.00"), new BigDecimal("150.00"), "EUR");

        assertThat(service.postOverShort(eurFact, ENVELOPE_EVENT_ID)).isEqualTo(new FactPostingOutcome.CurrencyHeld());

        verify(glPostingService, never())
                .postRegisterOverShort(any(), any(), any(), any(), any(), any(), anyString(), any());
        verify(idempotencyService, never()).registerKey(anyString(), any());
        verify(ingestionRecorder)
                .recordCurrencyHeld(
                        eq("pos-order"),
                        eq(RegisterSessionClosedV1.EVENT_TYPE),
                        eq(ENVELOPE_EVENT_ID),
                        eq(SESSION_ID),
                        eq(LocalDateTime.ofInstant(CLOSED_AT, ZoneOffset.UTC)),
                        eq(eurFact),
                        contains("EUR"));
    }

    @Test
    @DisplayName("A USD variance is not held (#2312)")
    void ledgerCurrencyVarianceIsNotHeld() {
        LocalDateTime expectedDate = LocalDateTime.ofInstant(CLOSED_AT, ZoneOffset.UTC);
        when(idempotencyService.isKeyProcessed(KEY)).thenReturn(false);
        when(glMappingResolver.resolveGLAccount(anyString(), anyString(), eq(expectedDate)))
                .thenReturn(CASH_SHORT, CASH_CLEARING);
        when(glPostingService.postRegisterOverShort(any(), any(), any(), any(), any(), any(), anyString(), any()))
                .thenReturn(postedEntry());

        service.postOverShort(
                fact(new BigDecimal("-10.00"), new BigDecimal("140.00"), new BigDecimal("150.00")), ENVELOPE_EVENT_ID);

        verify(ingestionRecorder, never()).recordCurrencyHeld(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("#2558: a fact at 2026-01-31T23:30-06:00 posts on 2026-01-31 in a Chicago calendar, clock in UTC")
    void chicagoCalendar_lastEveningOfJanuaryPostsInJanuary() {
        RegisterOverShortPostingService service2 = new RegisterOverShortPostingService(
                TestZoneResolvers.fixed(TestZoneResolvers.CHICAGO, TEST_CLOCK),
                idempotencyService,
                glMappingResolver,
                glPostingService,
                new LedgerCurrency("USD"),
                ingestionRecorder);
        when(glMappingResolver.resolveGLAccount(anyString(), anyString(), eq(LocalDateTime.of(2026, 1, 31, 23, 30))))
                .thenReturn(UUID.fromString("00000000-0000-0000-0000-000000002558"));
        when(glPostingService.postRegisterOverShort(any(), any(), any(), any(), any(), any(), anyString(), any()))
                .thenReturn(JOURNAL_ENTRY_ID);

        service2.postOverShort(
                TestZoneResolvers.movedTo(
                        fact(new BigDecimal("-10.00"), new BigDecimal("140.00"), new BigDecimal("150.00")),
                        CLOSED_AT,
                        TestZoneResolvers.JAN_31_2330_CHICAGO),
                ENVELOPE_EVENT_ID);

        verify(glPostingService)
                .postRegisterOverShort(
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        eq(LocalDateTime.of(2026, 1, 31, 23, 30)),
                        anyString(),
                        any());
    }
}

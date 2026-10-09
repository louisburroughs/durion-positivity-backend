package com.positivity.order.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.order.internal.config.FunctionalCurrency;
import com.positivity.order.internal.entity.ExtAccountingPettyExpenseCategory;
import com.positivity.order.internal.entity.ExtAccountingRegisterFloat;
import com.positivity.order.internal.entity.ProcessedEvent;
import com.positivity.order.internal.entity.RegisterSession;
import com.positivity.order.internal.entity.RegisterSessionStatus;
import com.positivity.order.internal.repository.ExtAccountingPettyExpenseCategoryRepository;
import com.positivity.order.internal.repository.ExtAccountingRegisterFloatRepository;
import com.positivity.order.internal.repository.ProcessedEventRepository;
import com.positivity.order.internal.repository.RegisterSessionRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

/**
 * CAP:550 S16 (#2512; ADR-0044 R3, §4): accounting's category and float facts feed pos-order's copies,
 * guarded by aggregateVersion; duplicates are skipped; every accounting eventId is recorded for the
 * manifest; transient failures propagate.
 */
@DisplayName("AccountingEventsListener — category and float copies")
class AccountingEventsListenerTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-07T12:00:00Z"), ZoneOffset.UTC);
    private static final UUID CATEGORY_ID = UUID.fromString("01900000-0000-7000-8000-0000000c0001");
    private static final UUID FLOAT_ID = UUID.fromString("01900000-0000-7000-8000-0000000f0001");

    private final ProcessedEventRepository processed = mock(ProcessedEventRepository.class);
    private final ExtAccountingPettyExpenseCategoryRepository categories =
            mock(ExtAccountingPettyExpenseCategoryRepository.class);
    private final ExtAccountingRegisterFloatRepository floats = mock(ExtAccountingRegisterFloatRepository.class);
    private final RegisterSessionRepository sessions = mock(RegisterSessionRepository.class);
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private AccountingEventsListener listener;

    @BeforeEach
    void setUp() {
        @SuppressWarnings("unchecked")
        ObjectProvider<MeterRegistry> meters = mock(ObjectProvider.class);
        when(meters.getIfAvailable()).thenReturn(meterRegistry);
        listener = new AccountingEventsListener(
                CLOCK,
                new ObjectMapper(),
                processed,
                categories,
                floats,
                sessions,
                new FunctionalCurrency("USD"),
                mock(PlatformTransactionManager.class),
                meters);
        when(categories.findById(any())).thenReturn(Optional.empty());
        when(floats.findById(any())).thenReturn(Optional.empty());
    }

    private static String categoryFact(String eventId, long version, String status) {
        return """
                {"eventId":"%s","eventType":"accounting.petty-expense-category.changed","schemaVersion":1,
                 "aggregateId":"%s","aggregateVersion":%d,"occurredAtUtc":"2026-10-07T11:59:00Z",
                 "sourceService":"pos-accounting",
                 "payload":{"code":"SHOP_SUPPLIES","label":"Shop supplies","examples":"rags, gloves",
                            "status":"%s","accountCode":"6340","accountName":"Shop Supplies & Consumables"}}
                """.formatted(eventId, CATEGORY_ID, version, status);
    }

    private static String floatFact(String eventId, long version, String amount) {
        return """
                {"eventId":"%s","eventType":"accounting.float.changed","schemaVersion":1,
                 "aggregateId":"%s","aggregateVersion":%d,"occurredAtUtc":"2026-10-07T11:59:30Z",
                 "sourceService":"pos-accounting",
                 "payload":{"registerId":"T-1","locationId":"01900000-0000-7000-8000-0000000000aa",
                            "amount":%s,"previousAmount":200.00,"kind":"CHANGE","effectiveDate":"2026-10-07",
                            "journalEntryId":"01900000-0000-7000-8000-0000000000e1"}}
                """.formatted(eventId, FLOAT_ID, version, amount);
    }

    @Test
    @DisplayName("a category fact writes the copy keyed by the aggregate and marks the event processed")
    void categoryCopied() {
        listener.onAccountingEvent(categoryFact("e-1", 3, "ACTIVE"));

        ArgumentCaptor<ExtAccountingPettyExpenseCategory> copy =
                ArgumentCaptor.forClass(ExtAccountingPettyExpenseCategory.class);
        verify(categories).save(copy.capture());
        assertThat(copy.getValue().getPettyExpenseCategoryId()).isEqualTo(CATEGORY_ID);
        assertThat(copy.getValue().getCode()).isEqualTo("SHOP_SUPPLIES");
        assertThat(copy.getValue().isActive()).isTrue();
        assertThat(copy.getValue().getAggregateVersion()).isEqualTo(3L);
        ArgumentCaptor<ProcessedEvent> mark = ArgumentCaptor.forClass(ProcessedEvent.class);
        verify(processed).save(mark.capture());
        assertThat(mark.getValue().getOwner()).isEqualTo("accounting");
        assertThat(meterRegistry
                        .get("replica.lag")
                        .tag("entity", "petty-expense-category")
                        .timer()
                        .count())
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("CAP:550 S32d: taxRecoverable and recoverablePercent are copied from the category fact")
    void categoryRecoveryCopied() {
        String fact = categoryFact("e-r1", 4, "ACTIVE")
                .replace(
                        "\"accountName\":\"Shop Supplies & Consumables\"",
                        "\"accountName\":\"Shop Supplies & Consumables\",\"taxRecoverable\":true,"
                                + "\"recoverablePercent\":50.00");

        listener.onAccountingEvent(fact);

        ArgumentCaptor<ExtAccountingPettyExpenseCategory> copy =
                ArgumentCaptor.forClass(ExtAccountingPettyExpenseCategory.class);
        verify(categories).save(copy.capture());
        assertThat(copy.getValue().isTaxRecoverable()).isTrue();
        assertThat(copy.getValue().getRecoverablePercent()).isEqualByComparingTo("50.00");
    }

    @Test
    @DisplayName("CAP:550 S32d: a category fact without the recovery fields maps to not recoverable")
    void categoryWithoutRecoveryIsNotRecoverable() {
        listener.onAccountingEvent(categoryFact("e-r2", 4, "ACTIVE"));

        ArgumentCaptor<ExtAccountingPettyExpenseCategory> copy =
                ArgumentCaptor.forClass(ExtAccountingPettyExpenseCategory.class);
        verify(categories).save(copy.capture());
        assertThat(copy.getValue().isTaxRecoverable()).isFalse();
        assertThat(copy.getValue().getRecoverablePercent()).isNull();
    }

    @Test
    @DisplayName("a float fact writes the configured float, a negative one (after a reversal) as it stands")
    void floatCopied() {
        listener.onAccountingEvent(floatFact("e-2", 5, "-25.00"));

        ArgumentCaptor<ExtAccountingRegisterFloat> copy = ArgumentCaptor.forClass(ExtAccountingRegisterFloat.class);
        verify(floats).save(copy.capture());
        assertThat(copy.getValue().getRegisterFloatId()).isEqualTo(FLOAT_ID);
        assertThat(copy.getValue().getRegisterId()).isEqualTo("T-1");
        assertThat(copy.getValue().getAmount()).isEqualByComparingTo("-25.00");
        assertThat(copy.getValue().getAggregateVersion()).isEqualTo(5L);
    }

    @Test
    @DisplayName("#2577 (ADR-0067 R-1): a schema-3 float fact's currencyCode is copied with its amount")
    void floatCurrencyCopied() {
        listener.onAccountingEvent(floatFact("e-11", 2, "200.00")
                .replace("\"schemaVersion\":1", "\"schemaVersion\":3")
                .replace("\"kind\":\"CHANGE\"", "\"kind\":\"CHANGE\",\"currencyCode\":\"CAD\""));

        ArgumentCaptor<ExtAccountingRegisterFloat> copy = ArgumentCaptor.forClass(ExtAccountingRegisterFloat.class);
        verify(floats).save(copy.capture());
        assertThat(copy.getValue().getCurrencyCode()).isEqualTo("CAD");
    }

    @Test
    @DisplayName(
            "#2577 (ADR-0067 PC-8): a float fact without currencyCode (schema 1 or 2) is in the functional currency")
    void floatWithoutCurrencyIsInTheFunctionalCurrency() {
        listener.onAccountingEvent(floatFact("e-12", 2, "200.00"));

        ArgumentCaptor<ExtAccountingRegisterFloat> copy = ArgumentCaptor.forClass(ExtAccountingRegisterFloat.class);
        verify(floats).save(copy.capture());
        assertThat(copy.getValue().getCurrencyCode()).isEqualTo("USD");
    }

    @Test
    @DisplayName("#2573: a RELOCATION to B while a drawer is open at A moves the copy, counts once, leaves the session")
    void relocationWithAnOpenDrawerElsewhereIsCounted() {
        RegisterSession open = RegisterSession.builder()
                .sessionId(UUID.randomUUID())
                .terminalId("T-1")
                .locationId(UUID.fromString("01900000-0000-7000-8000-0000000000aa"))
                .status(RegisterSessionStatus.OPEN)
                .build();
        when(sessions.findByTerminalIdAndStatusIn(org.mockito.ArgumentMatchers.eq("T-1"), any()))
                .thenReturn(List.of(open));

        listener.onAccountingEvent(floatFact("e-10", 3, "200.00")
                .replace("\"kind\":\"CHANGE\"", "\"kind\":\"RELOCATION\"")
                .replace(
                        "\"locationId\":\"01900000-0000-7000-8000-0000000000aa\"",
                        "\"locationId\":\"01900000-0000-7000-8000-0000000000bb\""));

        ArgumentCaptor<ExtAccountingRegisterFloat> copy = ArgumentCaptor.forClass(ExtAccountingRegisterFloat.class);
        verify(floats).save(copy.capture());
        assertThat(copy.getValue().getLocationId()).hasToString("01900000-0000-7000-8000-0000000000bb");
        assertThat(meterRegistry
                        .get(AccountingEventsListener.FLOAT_LOCATION_MISMATCH)
                        .tag("kind", "RELOCATION")
                        .counter()
                        .count())
                .isEqualTo(1.0);
        assertThat(open.getLocationId()).hasToString("01900000-0000-7000-8000-0000000000aa");
        verify(sessions, never()).save(any());
        verify(processed).save(any());
    }

    @Test
    @DisplayName("a fact older than the copy (out of order) changes nothing but is still marked processed")
    void staleFactIgnored() {
        when(floats.findById(FLOAT_ID))
                .thenReturn(Optional.of(ExtAccountingRegisterFloat.builder()
                        .registerFloatId(FLOAT_ID)
                        .aggregateVersion(7L)
                        .build()));

        listener.onAccountingEvent(floatFact("e-3", 6, "300.00"));

        verify(floats, never()).save(any());
        verify(processed).save(any());
        // A stale fact neither applies nor counts a location mismatch.
        verify(sessions, never()).findByTerminalIdAndStatusIn(any(), any());
        assertThat(meterRegistry
                        .find(AccountingEventsListener.FLOAT_LOCATION_MISMATCH)
                        .counter())
                .isNull();
    }

    @Test
    @DisplayName("#2577: a later fact without currencyCode (schema 1 or 2) keeps an existing copy's currency; its"
            + " amount and location still apply by state")
    void olderFactKeepsTheStoredCurrency() {
        when(floats.findById(FLOAT_ID))
                .thenReturn(Optional.of(ExtAccountingRegisterFloat.builder()
                        .registerFloatId(FLOAT_ID)
                        .registerId("T-1")
                        .locationId(UUID.fromString("01900000-0000-7000-8000-0000000000bb"))
                        .amount(new java.math.BigDecimal("150.0000"))
                        .currencyCode("CAD")
                        .aggregateVersion(4L)
                        .build()));

        listener.onAccountingEvent(floatFact("e-13", 5, "275.00"));

        ArgumentCaptor<ExtAccountingRegisterFloat> copy = ArgumentCaptor.forClass(ExtAccountingRegisterFloat.class);
        verify(floats).save(copy.capture());
        assertThat(copy.getValue().getCurrencyCode()).as("never re-denominated").isEqualTo("CAD");
        assertThat(copy.getValue().getAmount()).isEqualByComparingTo("275.00");
        assertThat(copy.getValue().getLocationId()).hasToString("01900000-0000-7000-8000-0000000000aa");
        assertThat(copy.getValue().getAggregateVersion()).isEqualTo(5L);
    }

    @Test
    @DisplayName("an equal version applies (start-up republish and replay repair the copy)")
    void equalVersionApplies() {
        when(categories.findById(CATEGORY_ID))
                .thenReturn(Optional.of(ExtAccountingPettyExpenseCategory.builder()
                        .pettyExpenseCategoryId(CATEGORY_ID)
                        .aggregateVersion(3L)
                        .status("ACTIVE")
                        .build()));

        listener.onAccountingEvent(categoryFact("e-4", 3, "INACTIVE"));

        ArgumentCaptor<ExtAccountingPettyExpenseCategory> copy =
                ArgumentCaptor.forClass(ExtAccountingPettyExpenseCategory.class);
        verify(categories).save(copy.capture());
        assertThat(copy.getValue().isActive()).isFalse();
    }

    @Test
    @DisplayName("a duplicate eventId is skipped")
    void duplicateSkipped() {
        when(processed.existsById("e-5")).thenReturn(true);

        listener.onAccountingEvent(floatFact("e-5", 1, "100.00"));

        verify(floats, never()).save(any());
        verify(processed, never()).save(any());
    }

    @Test
    @DisplayName("another accounting fact is only recorded, for the manifest")
    void otherFactsRecorded() {
        listener.onAccountingEvent("""
                {"eventId":"e-6","eventType":"accounting.invoice.gl-posted","aggregateId":"%s",
                 "aggregateVersion":1,"payload":{}}
                """.formatted(UUID.randomUUID()));

        verify(processed).save(any());
        verify(categories, never()).save(any());
        verify(floats, never()).save(any());
    }

    @Test
    @DisplayName("a transient database failure propagates for container retry")
    void transientFailurePropagates() {
        when(floats.save(any())).thenThrow(new QueryTimeoutException("timeout"));

        assertThatExceptionOfType(QueryTimeoutException.class)
                .isThrownBy(() -> listener.onAccountingEvent(floatFact("e-7", 1, "100.00")));
    }

    @Test
    @DisplayName(
            "Copilot: a malformed fact is skipped without a copy but marked processed, so the manifest stops drifting")
    void malformedSkippedButMarked() {
        listener.onAccountingEvent(floatFact("e-8", 1, "\"not-a-number\""));
        listener.onAccountingEvent("not json");

        verify(floats, never()).save(any());
        ArgumentCaptor<ProcessedEvent> mark = ArgumentCaptor.forClass(ProcessedEvent.class);
        verify(processed).save(mark.capture());
        assertThat(mark.getValue().getEventId()).isEqualTo("e-8");
        assertThat(mark.getValue().getOwner()).isEqualTo("accounting");
    }

    @Test
    @DisplayName("#2571: a float kind this build does not know still updates amount and location (state-based)")
    void unknownKindStillApplies() {
        listener.onAccountingEvent(floatFact("e-9", 2, "180.00")
                .replace("\"kind\":\"CHANGE\"", "\"kind\":\"RELOCATION\"")
                .replace(
                        "\"locationId\":\"01900000-0000-7000-8000-0000000000aa\"",
                        "\"locationId\":\"01900000-0000-7000-8000-0000000000bb\","
                                + "\"previousLocationId\":\"01900000-0000-7000-8000-0000000000aa\""));

        ArgumentCaptor<ExtAccountingRegisterFloat> copy = ArgumentCaptor.forClass(ExtAccountingRegisterFloat.class);
        verify(floats).save(copy.capture());
        assertThat(copy.getValue().getAmount()).isEqualByComparingTo("180.00");
        assertThat(copy.getValue().getLocationId()).hasToString("01900000-0000-7000-8000-0000000000bb");
    }
}

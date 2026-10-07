package com.positivity.accounting.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.entity.ProcessedEvent;
import com.positivity.accounting.internal.repository.ProcessedEventRepository;
import com.positivity.domainevents.order.RegisterSessionClosedV1;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

/**
 * Consumer-side contract test for {@code order.session.closed} ingestion (odoo-parity G3, issue
 * #1083). The envelope JSON literal pins the {@link RegisterSessionClosedV1} fact schema so a
 * producer-side field rename or type change fails here, not in production.
 */
class OrderEventsListenerTest {
    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2026-07-23T12:00:00Z"), ZoneOffset.UTC);
    private static final UUID SESSION_ID = UUID.fromString("00000000-0000-0000-0000-00000000000a");

    private final ProcessedEventRepository processedEvents = mock(ProcessedEventRepository.class);
    private final RegisterOverShortPostingService postingService = mock(RegisterOverShortPostingService.class);
    private final RegisterCashMovementPostingService movementPostingService =
            mock(RegisterCashMovementPostingService.class);
    private final KafkaFactIngestionRecorder ingestionRecorder = mock(KafkaFactIngestionRecorder.class);
    private final RegisterSessionReplica sessionReplica = mock(RegisterSessionReplica.class);
    private final UndepositedSessionProjection undepositedSessions = mock(UndepositedSessionProjection.class);

    private OrderEventsListener listener;

    @BeforeEach
    void setUp() {
        when(postingService.postOverShort(any(), any())).thenReturn(FactPostingOutcome.nothingToPost());
        when(movementPostingService.postMovements(any(), any())).thenReturn(FactPostingOutcome.nothingToPost());
        listener = new OrderEventsListener(
                TEST_CLOCK,
                new ObjectMapper(),
                processedEvents,
                postingService,
                movementPostingService,
                ingestionRecorder,
                org.mockito.Mockito.mock(ObjectProvider.class),
                mock(PlatformTransactionManager.class),
                com.positivity.accounting.internal.service.TestZoneResolvers.utc(java.time.Clock.systemUTC()),
                sessionReplica,
                undepositedSessions);
    }

    private String sessionClosed(String eventId) {
        return """
                {"eventId":"%s","eventType":"order.session.closed","schemaVersion":1,
                 "aggregateId":"%s","aggregateVersion":0,
                 "occurredAtUtc":"2026-07-23T18:30:00Z","sourceService":"pos-order",
                 "payload":{"sessionId":"%s","terminalId":"terminal-1",
                            "locationId":"00000000-0000-0000-0000-0000000000aa",
                            "openedByClerkId":"clerk-1","closedByClerkId":"clerk-2",
                            "openingFloat":100.00,"countedCash":140.00,"theoreticalCash":150.00,
                            "overShort":-10.00,"varianceApproved":false,"currencyCode":"USD",
                            "tenderTotals":[{"methodType":"CASH","amount":50.00}],
                            "cashMovementTotal":0.00,
                            "openedAt":"2026-07-23T08:00:00Z","closedAt":"2026-07-23T18:30:00Z"}}
                """.formatted(eventId, SESSION_ID, SESSION_ID);
    }

    @Test
    @DisplayName("Session-closed fact deserializes per the pinned schema and is handed to posting")
    void sessionClosedPostsOverShort() {
        when(processedEvents.existsById("e-1")).thenReturn(false);

        listener.onOrderEvent(sessionClosed("e-1"));

        ArgumentCaptor<RegisterSessionClosedV1> fact = ArgumentCaptor.forClass(RegisterSessionClosedV1.class);
        verify(postingService).postOverShort(fact.capture(), org.mockito.ArgumentMatchers.eq("e-1"));
        assertThat(fact.getValue().sessionId()).isEqualTo(SESSION_ID);
        assertThat(fact.getValue().terminalId()).isEqualTo("terminal-1");
        assertThat(fact.getValue().overShort()).isEqualByComparingTo(new BigDecimal("-10.00"));
        assertThat(fact.getValue().countedCash()).isEqualByComparingTo(new BigDecimal("140.00"));
        assertThat(fact.getValue().theoreticalCash()).isEqualByComparingTo(new BigDecimal("150.00"));
        assertThat(fact.getValue().closedAt()).isEqualTo(Instant.parse("2026-07-23T18:30:00Z"));

        ArgumentCaptor<ProcessedEvent> processed = ArgumentCaptor.forClass(ProcessedEvent.class);
        verify(processedEvents).save(processed.capture());
        assertThat(processed.getValue().getEventId()).isEqualTo("e-1");
        assertThat(processed.getValue().getOwner()).isEqualTo(OrderEventsListener.OWNER);
        assertThat(processed.getValue().getProcessedAt()).isEqualTo(Instant.now(TEST_CLOCK));
    }

    @Test
    @DisplayName("CAP:550 S16 (#2512): a schema-2 fact with movements[] still posts the over/short unchanged")
    void schemaTwoFactWithMovementsStillPostsOverShort() {
        when(processedEvents.existsById("e-v2")).thenReturn(false);
        String message = sessionClosed("e-v2")
                .replace("\"schemaVersion\":1", "\"schemaVersion\":2")
                .replace("\"closedAt\":\"2026-07-23T18:30:00Z\"}", """
                        "closedAt":"2026-07-23T18:30:00Z",
                         "movements":[{"movementId":"00000000-0000-0000-0000-0000000000f1",
                                       "reason":"PETTY_EXPENSE","direction":"OUT","amount":12.50,"currencyCode":"USD",
                                       "categoryCode":"SHOP_SUPPLIES","vendorId":null,"bagNumber":null,
                                       "receiptReference":"R-1","clerkId":"clerk-1","clerkUserId":null,"approvedBy":null,
                                       "occurredAt":"2026-07-23T10:00:00Z"}]}""");

        listener.onOrderEvent(message);

        ArgumentCaptor<RegisterSessionClosedV1> fact = ArgumentCaptor.forClass(RegisterSessionClosedV1.class);
        verify(postingService).postOverShort(fact.capture(), org.mockito.ArgumentMatchers.eq("e-v2"));
        assertThat(fact.getValue().overShort()).isEqualByComparingTo(new BigDecimal("-10.00"));
        assertThat(fact.getValue().movements()).singleElement().satisfies(movement -> {
            assertThat(movement.reason()).isEqualTo("PETTY_EXPENSE");
            assertThat(movement.amount()).isEqualByComparingTo("12.50");
        });
        verify(processedEvents).save(any());
    }

    @Test
    @DisplayName("CAP:550 S18 (#2514): once the session posted, its undeposited session is written in the same"
            + " handler transaction, with the envelope's schema version; a session held for its currency writes none")
    void postedSessionWritesItsUndepositedSession() {
        listener.onOrderEvent(sessionClosedWithMovements("e-s18"));

        ArgumentCaptor<RegisterSessionClosedV1> fact = ArgumentCaptor.forClass(RegisterSessionClosedV1.class);
        verify(undepositedSessions).record(fact.capture(), org.mockito.ArgumentMatchers.eq(2));
        assertThat(fact.getValue().sessionId()).isEqualTo(SESSION_ID);

        listener.onOrderEvent(sessionClosed("e-s18-v1"));
        verify(undepositedSessions).record(any(), org.mockito.ArgumentMatchers.eq(1));

        org.mockito.Mockito.clearInvocations(undepositedSessions);
        when(movementPostingService.postMovements(any(), any())).thenReturn(new FactPostingOutcome.CurrencyHeld());
        listener.onOrderEvent(sessionClosedWithMovements("e-s18-held"));
        verify(undepositedSessions, never()).record(any(), org.mockito.ArgumentMatchers.anyInt());

        when(movementPostingService.postMovements(any(), any())).thenReturn(FactPostingOutcome.nothingToPost());
        when(postingService.postOverShort(any(), any())).thenReturn(new FactPostingOutcome.CurrencyHeld());
        listener.onOrderEvent(sessionClosedWithMovements("e-s18-held-variance"));
        verify(undepositedSessions, never()).record(any(), org.mockito.ArgumentMatchers.anyInt());
    }

    private String sessionClosedWithMovements(String eventId) {
        return sessionClosed(eventId)
                .replace("\"schemaVersion\":1", "\"schemaVersion\":2")
                .replace("\"closedAt\":\"2026-07-23T18:30:00Z\"}", """
                        "closedAt":"2026-07-23T18:30:00Z",
                         "movements":[{"movementId":"00000000-0000-0000-0000-0000000000f1",
                                       "reason":"PETTY_EXPENSE","direction":"OUT","amount":12.50,"currencyCode":"USD",
                                       "categoryCode":"SHOP_SUPPLIES","vendorId":null,"bagNumber":null,
                                       "receiptReference":"R-1","clerkId":"clerk-1","clerkUserId":null,"approvedBy":null,
                                       "occurredAt":"2026-07-23T10:00:00Z"}]}""");
    }

    @Test
    @DisplayName("#2513 PROPOSED 1: a v2 fact goes to the movement posting and the over/short, in that order, in one"
            + " handler transaction before the processed mark")
    void schemaTwoFactPostsMovementsThenOverShort() {
        when(processedEvents.existsById("e-m1")).thenReturn(false);

        listener.onOrderEvent(sessionClosedWithMovements("e-m1"));

        org.mockito.InOrder order =
                org.mockito.Mockito.inOrder(sessionReplica, movementPostingService, postingService, processedEvents);
        order.verify(sessionReplica).closed(any(RegisterSessionClosedV1.class), org.mockito.ArgumentMatchers.eq(0L));
        ArgumentCaptor<RegisterSessionClosedV1> fact = ArgumentCaptor.forClass(RegisterSessionClosedV1.class);
        order.verify(movementPostingService).postMovements(fact.capture(), org.mockito.ArgumentMatchers.eq("e-m1"));
        order.verify(postingService).postOverShort(any(), org.mockito.ArgumentMatchers.eq("e-m1"));
        order.verify(processedEvents).save(any());
        assertThat(fact.getValue().movements()).singleElement().satisfies(movement -> {
            assertThat(movement.movementId()).isEqualTo(UUID.fromString("00000000-0000-0000-0000-0000000000f1"));
            assertThat(movement.categoryCode()).isEqualTo("SHOP_SUPPLIES");
            assertThat(movement.receiptReference()).isEqualTo("R-1");
        });
    }

    @Test
    @DisplayName("#2513 AC8: a schema-1 fact reaches the movement posting with no movements and posts its over/short")
    void schemaOneFactStillPostsTheOverShort() {
        UUID entry = UUID.randomUUID();
        when(processedEvents.existsById("e-m2")).thenReturn(false);
        when(postingService.postOverShort(any(), org.mockito.ArgumentMatchers.eq("e-m2")))
                .thenReturn(FactPostingOutcome.posted(entry));

        listener.onOrderEvent(sessionClosed("e-m2"));

        ArgumentCaptor<RegisterSessionClosedV1> fact = ArgumentCaptor.forClass(RegisterSessionClosedV1.class);
        verify(movementPostingService).postMovements(fact.capture(), org.mockito.ArgumentMatchers.eq("e-m2"));
        assertThat(fact.getValue().movements()).isNull();
        verify(ingestionRecorder)
                .record(
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        org.mockito.ArgumentMatchers.eq(FactPostingOutcome.posted(entry)));
    }

    @Test
    @DisplayName("#2513: a movement entry with a zero-variance close is the fact's NEW record, linked to that entry")
    void postedMovementIsRecordedWhenTheVarianceIsZero() {
        UUID movementEntry = UUID.randomUUID();
        when(processedEvents.existsById("e-m3")).thenReturn(false);
        when(movementPostingService.postMovements(any(), org.mockito.ArgumentMatchers.eq("e-m3")))
                .thenReturn(FactPostingOutcome.posted(movementEntry));

        listener.onOrderEvent(sessionClosedWithMovements("e-m3"));

        verify(ingestionRecorder)
                .record(
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        org.mockito.ArgumentMatchers.eq(FactPostingOutcome.posted(movementEntry)));
    }

    @Test
    @DisplayName("#2513 AC7: a session the movement posting holds for its currency posts no over/short; the hold is"
            + " its record")
    void currencyHeldMovementsSkipTheOverShort() {
        when(processedEvents.existsById("e-m4")).thenReturn(false);
        when(movementPostingService.postMovements(any(), org.mockito.ArgumentMatchers.eq("e-m4")))
                .thenReturn(new FactPostingOutcome.CurrencyHeld());

        listener.onOrderEvent(sessionClosedWithMovements("e-m4"));

        verify(postingService, never()).postOverShort(any(), any());
        verify(ingestionRecorder)
                .record(any(), any(), any(), any(), any(), any(), any(FactPostingOutcome.CurrencyHeld.class));
        verify(processedEvents).save(any());
    }

    @Test
    @DisplayName("#2513: a movement posting failure (closed period, missing mapping) propagates before the over/short;"
            + " nothing is marked")
    void movementPostingFailurePropagates() {
        when(processedEvents.existsById("e-m5")).thenReturn(false);
        doThrow(new IllegalStateException("simulated PERIOD_CLOSED"))
                .when(movementPostingService)
                .postMovements(any(), any());

        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> listener.onOrderEvent(sessionClosedWithMovements("e-m5")));

        verify(postingService, never()).postOverShort(any(), any());
        verify(processedEvents, never()).save(any());
        verifyNoInteractions(ingestionRecorder);
    }

    @Test
    @DisplayName("#2433: the posted over/short is recorded as one ingestion row under pos-order, linked to its entry")
    void postedOverShortIsRecorded() {
        UUID journalEntryId = UUID.randomUUID();
        FactPostingOutcome outcome = FactPostingOutcome.posted(journalEntryId);
        when(processedEvents.existsById("e-10")).thenReturn(false);
        when(postingService.postOverShort(any(), org.mockito.ArgumentMatchers.eq("e-10")))
                .thenReturn(outcome);

        listener.onOrderEvent(sessionClosed("e-10"));

        verify(ingestionRecorder)
                .record(
                        org.mockito.ArgumentMatchers.eq("pos-order"),
                        org.mockito.ArgumentMatchers.eq(RegisterSessionClosedV1.EVENT_TYPE),
                        org.mockito.ArgumentMatchers.eq("e-10"),
                        org.mockito.ArgumentMatchers.eq(SESSION_ID),
                        org.mockito.ArgumentMatchers.eq(java.time.LocalDateTime.of(2026, 7, 23, 18, 30)),
                        any(RegisterSessionClosedV1.class),
                        org.mockito.ArgumentMatchers.same(outcome));
        verify(processedEvents).save(any());
        assertThat(OrderEventsListener.RECORDED_EVENT_TYPES).containsExactly("order.session.closed");
    }

    @Test
    @DisplayName("#2433: a duplicate eventId writes no second ingestion row")
    void duplicateEventIdRecordsNothing() {
        when(processedEvents.existsById("e-11")).thenReturn(true);

        listener.onOrderEvent(sessionClosed("e-11"));

        verifyNoInteractions(ingestionRecorder);
    }

    @Test
    @DisplayName("Duplicate eventId is skipped without touching posting")
    void duplicateEventIdSkipped() {
        when(processedEvents.existsById("e-2")).thenReturn(true);

        listener.onOrderEvent(sessionClosed("e-2"));

        verifyNoInteractions(postingService, movementPostingService);
        verify(processedEvents, never()).save(any());
    }

    @Test
    @DisplayName("#2579: other order fact types post nothing but are recorded under the order owner for the manifest")
    void otherEventTypesIgnoredButRecorded() {
        when(processedEvents.existsById("e-3")).thenReturn(false);

        listener.onOrderEvent("""
                {"eventId":"e-3","eventType":"order.order.completed","payload":{}}
                """);

        verifyNoInteractions(postingService, movementPostingService, ingestionRecorder, sessionReplica);
        ArgumentCaptor<ProcessedEvent> processed = ArgumentCaptor.forClass(ProcessedEvent.class);
        verify(processedEvents).save(processed.capture());
        assertThat(processed.getValue().getEventId()).isEqualTo("e-3");
        assertThat(processed.getValue().getOwner()).isEqualTo(OrderEventsListener.OWNER);
    }

    @Test
    @DisplayName("#2579: a duplicate of an ignored fact type is not recorded twice")
    void duplicateOtherEventTypeSkipped() {
        when(processedEvents.existsById("e-3")).thenReturn(true);

        listener.onOrderEvent("""
                {"eventId":"e-3","eventType":"order.order.completed","payload":{}}
                """);

        verify(processedEvents, never()).save(any());
    }

    @Test
    @DisplayName("#2579: a fact without an eventId is skipped without recording anything, whatever its type")
    void factWithoutEventIdSkipped() {
        listener.onOrderEvent("""
                {"eventType":"order.order.completed","payload":{}}
                """);

        verifyNoInteractions(processedEvents, postingService);
    }

    @Test
    @DisplayName("Malformed payload is skipped but marked processed so the partition is not poisoned")
    void malformedPayloadSkippedAndMarkedProcessed() {
        when(processedEvents.existsById("e-4")).thenReturn(false);
        String message = sessionClosed("e-4").replace("-10.00", "\"not-a-number\"");

        listener.onOrderEvent(message);

        verify(postingService, never()).postOverShort(any(), any());
        verify(processedEvents).save(any());
    }

    @Test
    @DisplayName("Unparsable message is skipped without recording anything")
    void unparsableMessageSkipped() {
        listener.onOrderEvent("this is not json");

        verifyNoInteractions(postingService, movementPostingService);
        verifyNoInteractions(processedEvents);
    }

    @Test
    @DisplayName("Posting failures propagate unwrapped for container retry / DLQ; nothing marked processed")
    void postingFailurePropagates() {
        when(processedEvents.existsById("e-5")).thenReturn(false);
        doThrow(new QueryTimeoutException("db down")).when(postingService).postOverShort(any(), any());

        assertThatExceptionOfType(QueryTimeoutException.class)
                .isThrownBy(() -> listener.onOrderEvent(sessionClosed("e-5")));

        verify(processedEvents, never()).save(any());
    }

    @Test
    @DisplayName(
            "Posting failures propagate unwrapped for container retry / DLQ; nothing marked processed (lost connection, #2355)")
    void postingFailurePropagatesWhenTheConnectionIsLost() {
        when(processedEvents.existsById("e-5")).thenReturn(false);
        doThrow(new DataAccessResourceFailureException("connection reset"))
                .when(postingService)
                .postOverShort(any(), any());

        assertThatExceptionOfType(DataAccessResourceFailureException.class)
                .isThrownBy(() -> listener.onOrderEvent(sessionClosed("e-5")));

        verify(processedEvents, never()).save(any());
    }

    @Test
    @DisplayName("#2558: a session closed at 2026-01-31T23:30-06:00 is recorded on 2026-01-31 in a Chicago calendar")
    void recordDateIsTheTenantCalendarDate() {
        org.springframework.test.util.ReflectionTestUtils.setField(
                listener, "zoneResolver", TestZoneResolvers.fixed(TestZoneResolvers.CHICAGO, TEST_CLOCK));
        when(processedEvents.existsById("e-chi")).thenReturn(false);
        when(postingService.postOverShort(any(), org.mockito.ArgumentMatchers.eq("e-chi")))
                .thenReturn(FactPostingOutcome.posted(UUID.randomUUID()));

        listener.onOrderEvent(sessionClosed("e-chi").replace("2026-07-23T18:30:00Z", "2026-02-01T05:30:00Z"));

        verify(ingestionRecorder)
                .record(
                        any(),
                        any(),
                        org.mockito.ArgumentMatchers.eq("e-chi"),
                        any(),
                        org.mockito.ArgumentMatchers.eq(java.time.LocalDateTime.of(2026, 1, 31, 23, 30)),
                        any(RegisterSessionClosedV1.class),
                        any());
    }

    // ---- the session replica (#2571, #2573) -----------------------------------------------------------------

    private String sessionOpened(String eventId, long version) {
        return """
                {"eventId":"%s","eventType":"order.session.opened","schemaVersion":1,
                 "aggregateId":"%s","aggregateVersion":%d,
                 "occurredAtUtc":"2026-07-23T08:00:00Z","sourceService":"pos-order",
                 "payload":{"sessionId":"%s","terminalId":"terminal-1",
                            "locationId":"00000000-0000-0000-0000-0000000000aa",
                            "openedAt":"2026-07-23T08:00:00Z"}}
                """.formatted(eventId, SESSION_ID, version, SESSION_ID);
    }

    @Test
    @DisplayName("#2571: an opened fact writes the session replica with its version and is marked processed; it posts"
            + " and records nothing")
    void sessionOpenedWritesTheReplica() {
        when(processedEvents.existsById("e-20")).thenReturn(false);

        listener.onOrderEvent(sessionOpened("e-20", 3));

        ArgumentCaptor<com.positivity.domainevents.order.RegisterSessionOpenedV1> fact =
                ArgumentCaptor.forClass(com.positivity.domainevents.order.RegisterSessionOpenedV1.class);
        verify(sessionReplica).opened(fact.capture(), org.mockito.ArgumentMatchers.eq(3L));
        assertThat(fact.getValue().sessionId()).isEqualTo(SESSION_ID);
        assertThat(fact.getValue().terminalId()).isEqualTo("terminal-1");
        assertThat(fact.getValue().locationId()).isEqualTo(UUID.fromString("00000000-0000-0000-0000-0000000000aa"));
        assertThat(fact.getValue().openedAt()).isEqualTo(Instant.parse("2026-07-23T08:00:00Z"));
        verify(processedEvents).save(any());
        verifyNoInteractions(postingService, movementPostingService, ingestionRecorder);
    }

    @Test
    @DisplayName("#2571: a closed fact closes the session replica, with its version, before the posting")
    void sessionClosedClosesTheReplica() {
        when(processedEvents.existsById("e-21")).thenReturn(false);

        listener.onOrderEvent(sessionClosed("e-21"));

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(sessionReplica, postingService, processedEvents);
        order.verify(sessionReplica).closed(any(RegisterSessionClosedV1.class), org.mockito.ArgumentMatchers.eq(0L));
        order.verify(postingService).postOverShort(any(), org.mockito.ArgumentMatchers.eq("e-21"));
        order.verify(processedEvents).save(any());
    }

    @Test
    @DisplayName("#2571: a posting failure still closes the replica; the fact stays unmarked for retry, and the"
            + " redelivery closes it again and posts")
    void postingFailureStillClosesTheReplica() {
        when(processedEvents.existsById("e-22")).thenReturn(false);
        doThrow(new DataAccessResourceFailureException("db down"))
                .doReturn(FactPostingOutcome.posted(UUID.randomUUID()))
                .when(postingService)
                .postOverShort(any(), org.mockito.ArgumentMatchers.eq("e-22"));

        assertThatExceptionOfType(DataAccessResourceFailureException.class)
                .isThrownBy(() -> listener.onOrderEvent(sessionClosed("e-22")));
        verify(sessionReplica).closed(any(), org.mockito.ArgumentMatchers.eq(0L));
        verify(processedEvents, never()).save(any());

        listener.onOrderEvent(sessionClosed("e-22"));

        verify(sessionReplica, org.mockito.Mockito.times(2)).closed(any(), org.mockito.ArgumentMatchers.eq(0L));
        verify(processedEvents).save(any());
    }

    @Test
    @DisplayName("#2571: a replica failure on an opened fact propagates unwrapped for retry / DLQ; nothing is marked")
    void sessionOpenedFailurePropagates() {
        when(processedEvents.existsById("e-25")).thenReturn(false);
        doThrow(new DataAccessResourceFailureException("db down"))
                .when(sessionReplica)
                .opened(any(), org.mockito.ArgumentMatchers.anyLong());

        assertThatExceptionOfType(DataAccessResourceFailureException.class)
                .isThrownBy(() -> listener.onOrderEvent(sessionOpened("e-25", 1)));
        verify(processedEvents, never()).save(any());
        verifyNoInteractions(postingService, movementPostingService, ingestionRecorder);
    }

    @Test
    @DisplayName("#2571: a malformed opened fact is marked processed and never reaches the replica")
    void malformedSessionOpenedIsMarked() {
        when(processedEvents.existsById("e-23")).thenReturn(false);

        listener.onOrderEvent("""
                {"eventId":"e-23","eventType":"order.session.opened","aggregateVersion":1,
                 "payload":{"sessionId":"%s","openedAt":"2026-07-23T08:00:00Z"}}
                """.formatted(SESSION_ID));

        verify(sessionReplica, never()).opened(any(), org.mockito.ArgumentMatchers.anyLong());
        verify(processedEvents).save(any());
    }

    @Test
    @DisplayName("#2571: a duplicate opened fact is skipped")
    void duplicateSessionOpenedIsSkipped() {
        when(processedEvents.existsById("e-24")).thenReturn(true);

        listener.onOrderEvent(sessionOpened("e-24", 1));

        verifyNoInteractions(sessionReplica);
        verify(processedEvents, never()).save(any());
    }
}

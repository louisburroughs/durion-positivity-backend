package com.positivity.accounting.internal.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.positivity.accounting.internal.dto.APPaymentGLPostingEvent;
import com.positivity.accounting.internal.entity.EventOutbox;
import com.positivity.accounting.internal.entity.EventOutbox.OutboxStatus;
import com.positivity.accounting.internal.repository.EventOutboxRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Pageable;

/**
 * Unit tests for OutboxProcessor.
 *
 * Tests the scheduled outbox polling, event publication, retry/failure marking,
 * and old event cleanup.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OutboxProcessor Unit Tests")
class OutboxProcessorTest {

    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC);

    @Spy
    Clock clock = TEST_CLOCK;

    @Mock
    private EventOutboxRepository outboxRepository;

    @Mock
    private OutboxServiceImpl outboxService;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private APPaymentFailurePersistenceService apPaymentFailures;

    private ObjectMapper objectMapper;

    @InjectMocks
    private OutboxProcessor processor;

    private UUID outboxId;
    private UUID eventId;
    private EventOutbox testOutbox;
    private static final UUID TENANT = UUID.fromString("01900000-0000-7000-8000-000000000001");

    @BeforeEach
    void setUp() throws Exception {
        outboxId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        eventId = UUID.fromString("00000000-0000-0000-0000-000000000002");

        objectMapper = new ObjectMapper();
        processor = new OutboxProcessor(
                clock, outboxRepository, outboxService, eventPublisher, objectMapper, apPaymentFailures);

        testOutbox = new EventOutbox();
        testOutbox.setTenantId(TENANT);
        testOutbox.setOutboxId(outboxId);
        testOutbox.setEventId(eventId);
        testOutbox.setStatus(OutboxStatus.PENDING);
        testOutbox.setAggregateType("APPayment");
        testOutbox.setAggregateId(UUID.fromString("00000000-0000-0000-0000-000000000003"));
        testOutbox.setEventType(APPaymentGLPostingEvent.class.getName());
        testOutbox.setRetryCount(0);
    }

    @Test
    @DisplayName("processPendingEvents - does nothing when no pending events")
    void processPendingEvents_emptyQueue() {
        when(outboxRepository.findPendingForRetry(eq(OutboxStatus.PENDING), any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of());

        processor.processPendingEvents();

        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("processPendingEvents - publishes event and marks as published on success")
    void processPendingEvents_publishesSuccessfully() throws Exception {
        APPaymentGLPostingEvent event = APPaymentGLPostingEvent.builder()
                .eventId(eventId)
                .organizationId(UUID.fromString("00000000-0000-0000-0000-000000000010"))
                .paymentId(UUID.fromString("00000000-0000-0000-0000-000000000020"))
                .paymentRef("PAY-001")
                .vendorId(UUID.fromString("00000000-0000-0000-0000-000000000030"))
                .grossAmount(new java.math.BigDecimal("1000.00"))
                .currency("USD")
                .paymentMethod("ACH")
                .allocations(java.util.List.of())
                .build();

        String payload = objectMapper.writeValueAsString(event);
        testOutbox.setPayload(payload);

        when(outboxRepository.findPendingForRetry(eq(OutboxStatus.PENDING), any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of(testOutbox));

        processor.processPendingEvents();

        verify(eventPublisher).publishEvent(any(APPaymentGLPostingEvent.class));
        verify(outboxService).markAsPublished(outboxId);
    }

    @Test
    @DisplayName("processPendingEvents - publishes PaymentApplicationGLPostingEvent (story C1)")
    void processPendingEvents_publishesPaymentApplicationGLPostingEvent() throws Exception {
        com.positivity.accounting.internal.dto.PaymentApplicationGLPostingEvent event =
                com.positivity.accounting.internal.dto.PaymentApplicationGLPostingEvent.builder()
                        .eventId(eventId)
                        .applicationRequestId("00000000-0000-0000-0000-000000000042")
                        .paymentId(UUID.fromString("00000000-0000-0000-0000-000000000020"))
                        .customerId(UUID.fromString("00000000-0000-0000-0000-000000000021"))
                        .currency("USD")
                        .appliedAmount(new java.math.BigDecimal("500.00"))
                        .applicationTimestamp(Instant.parse("2024-01-01T00:00:00Z"))
                        .build();

        ObjectMapper timeAwareMapper =
                new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
        OutboxProcessor timeAwareProcessor = new OutboxProcessor(
                clock, outboxRepository, outboxService, eventPublisher, timeAwareMapper, apPaymentFailures);

        testOutbox.setAggregateType("PaymentApplication");
        testOutbox.setEventType(
                com.positivity.accounting.internal.dto.PaymentApplicationGLPostingEvent.class.getName());
        testOutbox.setPayload(timeAwareMapper.writeValueAsString(event));

        when(outboxRepository.findPendingForRetry(eq(OutboxStatus.PENDING), any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of(testOutbox));

        timeAwareProcessor.processPendingEvents();

        verify(eventPublisher)
                .publishEvent(any(com.positivity.accounting.internal.dto.PaymentApplicationGLPostingEvent.class));
        verify(outboxService).markAsPublished(outboxId);
    }

    @Test
    @DisplayName("processPendingEvents - marks as failed when event type is unsupported")
    void processPendingEvents_unsupportedEventType() {
        testOutbox.setEventType("com.example.UnknownEvent");
        testOutbox.setPayload("{\"test\":true}");

        when(outboxRepository.findPendingForRetry(eq(OutboxStatus.PENDING), any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of(testOutbox));

        processor.processPendingEvents();

        verify(outboxService).markAsFailed(eq(outboxId), any(String.class), eq(5));
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("processPendingEvents - handles repository exception gracefully")
    void processPendingEvents_repositoryException() {
        when(outboxRepository.findPendingForRetry(eq(OutboxStatus.PENDING), any(Instant.class), any(Pageable.class)))
                .thenThrow(new RuntimeException("DB connection lost"));

        // Should not propagate exception
        processor.processPendingEvents();

        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("cleanupOldEvents - delegates cleanup to outboxService")
    void cleanupOldEvents_success() {
        when(outboxService.cleanupOldEvents(any(Instant.class))).thenReturn(3);

        processor.cleanupOldEvents();

        verify(outboxService).cleanupOldEvents(any(Instant.class));
    }

    @Test
    @DisplayName("cleanupOldEvents - handles exception gracefully")
    void cleanupOldEvents_exception() {
        when(outboxService.cleanupOldEvents(any(Instant.class))).thenThrow(new RuntimeException("DB error"));

        // Exception must not propagate — scheduled methods that throw will be unscheduled by Spring
        assertDoesNotThrow(() -> processor.cleanupOldEvents());

        // Cleanup was attempted before the exception was caught (not an early return)
        verify(outboxService).cleanupOldEvents(any(Instant.class));
    }

    // ---- CAP:550 S42 (#2603): AP payment delivery through the real handler ----------------------------------------

    private OutboxProcessor processorDeliveringTo(
            com.positivity.accounting.internal.handler.APPaymentGLPostingEventHandler handler) {
        return new OutboxProcessor(
                clock,
                outboxRepository,
                outboxService,
                event -> handler.onAPPaymentGLPosting((APPaymentGLPostingEvent) event),
                objectMapper,
                apPaymentFailures);
    }

    private void pendingApPayment(UUID paymentId) throws Exception {
        testOutbox.setPayload(objectMapper.writeValueAsString(APPaymentGLPostingEvent.builder()
                .eventId(eventId)
                .organizationId(UUID.fromString("00000000-0000-0000-0000-000000000010"))
                .paymentId(paymentId)
                .paymentRef("PAY-412")
                .vendorId(UUID.fromString("00000000-0000-0000-0000-000000000030"))
                .grossAmount(new java.math.BigDecimal("412.00"))
                .currency("USD")
                .paymentMethod("ACH")
                .allocations(List.of())
                .build()));
        when(outboxRepository.findPendingForRetry(eq(OutboxStatus.PENDING), any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of(testOutbox));
    }

    @Test
    @DisplayName("S42 AC7: a transient posting failure is retried by the outbox; the next poll posts and completes it")
    void apPaymentTransientFailureIsRetried() throws Exception {
        UUID paymentId = UUID.fromString("00000000-0000-0000-0000-000000000042");
        APPaymentPostingService postingService = org.mockito.Mockito.mock(APPaymentPostingService.class);
        APPaymentFailurePersistenceService failures =
                org.mockito.Mockito.mock(APPaymentFailurePersistenceService.class);
        when(postingService.postPending(paymentId))
                .thenThrow(new org.springframework.dao.CannotAcquireLockException("lock timeout"))
                .thenReturn(UUID.fromString("00000000-0000-0000-0000-0000000000e1"));
        pendingApPayment(paymentId);
        OutboxProcessor delivering =
                processorDeliveringTo(new com.positivity.accounting.internal.handler.APPaymentGLPostingEventHandler(
                        postingService, failures));

        delivering.processPendingEvents();
        verify(outboxService).markAsFailed(eq(outboxId), any(String.class), eq(5));
        verify(outboxService, never()).markAsPublished(outboxId);

        delivering.processPendingEvents();
        verify(outboxService).markAsPublished(outboxId);
        verify(postingService, org.mockito.Mockito.times(2)).postPending(paymentId);
        verify(failures, never()).persistGLPostRefusal(any(), any());
    }

    @Test
    @DisplayName("S42 AC6: a refused posting completes the outbox row without spending a retry")
    void apPaymentRefusalCompletesTheRow() throws Exception {
        UUID paymentId = UUID.fromString("00000000-0000-0000-0000-000000000043");
        APPaymentPostingService postingService = org.mockito.Mockito.mock(APPaymentPostingService.class);
        APPaymentFailurePersistenceService failures =
                org.mockito.Mockito.mock(APPaymentFailurePersistenceService.class);
        when(postingService.postPending(paymentId))
                .thenThrow(new com.positivity.accounting.internal.exception.GLMappingNotConfiguredException("missing"));
        pendingApPayment(paymentId);

        processorDeliveringTo(new com.positivity.accounting.internal.handler.APPaymentGLPostingEventHandler(
                        postingService, failures))
                .processPendingEvents();

        verify(failures).persistGLPostRefusal(paymentId, "GL_MAPPING_NOT_CONFIGURED");
        verify(outboxService).markAsPublished(outboxId);
        verify(outboxService, never()).markAsFailed(any(), any(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    @DisplayName("S42 MINOR 3 (#2641): when the last retry fails, the AP payment goes GL_POST_FAILED"
            + " (GL_POST_RETRIES_EXHAUSTED) so gl-posting-retry can post it; earlier failures leave it pending")
    void apPaymentRetriesExhausted() throws Exception {
        UUID paymentId = UUID.fromString("00000000-0000-0000-0000-000000000044");
        testOutbox.setAggregateId(paymentId);
        APPaymentPostingService postingService = org.mockito.Mockito.mock(APPaymentPostingService.class);
        when(postingService.postPending(paymentId))
                .thenThrow(new org.springframework.dao.CannotAcquireLockException("lock timeout"));
        pendingApPayment(paymentId);
        OutboxProcessor delivering =
                processorDeliveringTo(new com.positivity.accounting.internal.handler.APPaymentGLPostingEventHandler(
                        postingService, apPaymentFailures));

        testOutbox.setRetryCount(3);
        delivering.processPendingEvents();
        verify(apPaymentFailures, never()).persistGLPostRefusal(any(), any());

        testOutbox.setRetryCount(4); // this failure is the fifth: the row goes FAILED
        delivering.processPendingEvents();
        verify(apPaymentFailures).persistGLPostRefusal(paymentId, "GL_POST_RETRIES_EXHAUSTED");
        verify(outboxService, org.mockito.Mockito.times(2)).markAsFailed(eq(outboxId), any(String.class), eq(5));
    }
}

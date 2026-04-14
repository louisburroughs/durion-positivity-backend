package com.positivity.accounting.service;

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
import com.positivity.accounting.internal.service.OutboxProcessor;
import com.positivity.accounting.internal.service.OutboxServiceImpl;
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

    private ObjectMapper objectMapper;

    @InjectMocks
    private OutboxProcessor processor;

    private UUID outboxId;
    private UUID eventId;
    private EventOutbox testOutbox;

    @BeforeEach
    void setUp() throws Exception {
        outboxId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        eventId = UUID.fromString("00000000-0000-0000-0000-000000000002");

        objectMapper = new ObjectMapper();
        processor = new OutboxProcessor(clock, outboxRepository, outboxService, eventPublisher, objectMapper);

        testOutbox = new EventOutbox();
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

        // Should not propagate
        processor.cleanupOldEvents();
    }
}

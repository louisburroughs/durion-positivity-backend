package com.positivity.accounting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.positivity.accounting.internal.entity.EventOutbox;
import com.positivity.accounting.internal.entity.EventOutbox.OutboxStatus;
import com.positivity.accounting.internal.repository.EventOutboxRepository;
import com.positivity.accounting.internal.service.OutboxServiceImpl;

/**
 * Unit tests for OutboxServiceImpl.
 *
 * Tests outbox CRUD operations: save, mark-published, mark-failed, cleanup.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OutboxService Unit Tests")
class OutboxServiceImplTest {

    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC);

    @Spy
    Clock clock = TEST_CLOCK;

    @Mock
    private EventOutboxRepository outboxRepository;

    @Mock
    private ObjectMapper objectMapper;

    @InjectMocks
    private OutboxServiceImpl service;

    private UUID eventId;
    private UUID outboxId;
    private UUID aggregateId;
    private EventOutbox testOutbox;

    @BeforeEach
    void setUp() {
        eventId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        outboxId = UUID.fromString("00000000-0000-0000-0000-000000000002");
        aggregateId = UUID.fromString("00000000-0000-0000-0000-000000000003");

        testOutbox = EventOutbox.create(eventId, "APPayment", aggregateId,
                "com.positivity.accounting.internal.dto.APPaymentGLPostingEvent",
                "{\"paymentId\":\"test\"}");
        testOutbox.setOutboxId(outboxId);
        testOutbox.setRetryCount(0);
    }

    // ===== SAVE TO OUTBOX =====

    @Nested
    @DisplayName("saveToOutbox")
    class SaveToOutbox {

        @Test
        @DisplayName("saves event successfully when serialization succeeds")
        void success() throws JsonProcessingException {
            Object event = new Object();
            when(objectMapper.writeValueAsString(event)).thenReturn("{\"test\":true}");
            when(outboxRepository.save(any(EventOutbox.class))).thenReturn(testOutbox);

            EventOutbox result = service.saveToOutbox(eventId, "APPayment", aggregateId,
                    "com.positivity.accounting.internal.dto.APPaymentGLPostingEvent", event);

            assertThat(result).isNotNull();
            assertThat(result.getEventId()).isEqualTo(eventId);
            verify(outboxRepository).save(any(EventOutbox.class));
        }

        @Test
        @DisplayName("throws IllegalArgumentException when serialization fails")
        void serializationFailure() throws JsonProcessingException {
            Object event = new Object();
            when(objectMapper.writeValueAsString(event))
                    .thenThrow(new com.fasterxml.jackson.core.JsonGenerationException(
                            "Serialization failed", (com.fasterxml.jackson.core.JsonGenerator) null));

            assertThatThrownBy(() -> service.saveToOutbox(eventId, "APPayment", aggregateId,
                    "SomeEventType", event))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Failed to serialize");

            verify(outboxRepository, never()).save(any());
        }
    }

    // ===== MARK AS PUBLISHED =====

    @Nested
    @DisplayName("markAsPublished")
    class MarkAsPublished {

        @Test
        @DisplayName("marks outbox entry as PUBLISHED when found")
        void success() {
            when(outboxRepository.findById(outboxId)).thenReturn(Optional.of(testOutbox));
            when(outboxRepository.save(any(EventOutbox.class))).thenReturn(testOutbox);

            service.markAsPublished(outboxId);

            assertThat(testOutbox.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
            assertThat(testOutbox.getPublishedAt()).isNotNull();
            verify(outboxRepository).save(testOutbox);
        }

        @Test
        @DisplayName("does nothing when outbox entry not found")
        void notFound() {
            when(outboxRepository.findById(outboxId)).thenReturn(Optional.empty());

            service.markAsPublished(outboxId);

            verify(outboxRepository, never()).save(any());
        }
    }

    // ===== MARK AS FAILED =====

    @Nested
    @DisplayName("markAsFailed")
    class MarkAsFailed {

        @Test
        @DisplayName("increments retry count and stays PENDING when below max retries")
        void belowMaxRetries() {
            testOutbox.setRetryCount(1);
            when(outboxRepository.findById(outboxId)).thenReturn(Optional.of(testOutbox));
            when(outboxRepository.save(any(EventOutbox.class))).thenReturn(testOutbox);

            service.markAsFailed(outboxId, "Connection timeout", 5);

            assertThat(testOutbox.getRetryCount()).isEqualTo(2);
            assertThat(testOutbox.getStatus()).isEqualTo(OutboxStatus.PENDING);
            assertThat(testOutbox.getLastError()).isEqualTo("Connection timeout");
            assertThat(testOutbox.getLastAttemptAt()).isNotNull();
        }

        @Test
        @DisplayName("marks as FAILED when retry count reaches max")
        void atMaxRetries() {
            testOutbox.setRetryCount(4);
            when(outboxRepository.findById(outboxId)).thenReturn(Optional.of(testOutbox));
            when(outboxRepository.save(any(EventOutbox.class))).thenReturn(testOutbox);

            service.markAsFailed(outboxId, "Connection timeout", 5);

            assertThat(testOutbox.getRetryCount()).isEqualTo(5);
            assertThat(testOutbox.getStatus()).isEqualTo(OutboxStatus.FAILED);
        }

        @Test
        @DisplayName("does nothing when outbox entry not found")
        void notFound() {
            when(outboxRepository.findById(outboxId)).thenReturn(Optional.empty());

            service.markAsFailed(outboxId, "Error", 5);

            verify(outboxRepository, never()).save(any());
        }
    }

    // ===== CLEANUP =====

    @Test
    @DisplayName("cleanupOldEvents - returns count of deleted events")
    void cleanupOldEvents_success() {
        Instant beforeDate = Instant.parse("2024-01-01T00:00:00Z");
        when(outboxRepository.deleteOldPublishedEvents(eq(OutboxStatus.PUBLISHED), eq(beforeDate)))
                .thenReturn(5);

        int result = service.cleanupOldEvents(beforeDate);

        assertThat(result).isEqualTo(5);
    }

    @Test
    @DisplayName("cleanupOldEvents - returns 0 when nothing to delete")
    void cleanupOldEvents_nothingToDelete() {
        Instant beforeDate = Instant.parse("2024-01-01T00:00:00Z");
        when(outboxRepository.deleteOldPublishedEvents(any(), any())).thenReturn(0);

        int result = service.cleanupOldEvents(beforeDate);

        assertThat(result).isEqualTo(0);
    }
}

package com.positivity.accounting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import com.positivity.accounting.internal.dto.AccountingEventResponse;
import com.positivity.accounting.internal.entity.AccountingEvent;
import com.positivity.accounting.internal.enums.AccountingEventStatus;
import com.positivity.accounting.internal.exception.EventNotFoundException;
import com.positivity.accounting.internal.repository.AccountingEventRepository;

/**
 * Unit tests for EventIngestionService
 * 
 * Tests event ingestion, retrieval, and pagination functionality.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EventIngestionService Unit Tests")
class EventIngestionServiceTest {

        @Mock
        private AccountingEventRepository accountingEventRepository;

        @Mock
        private com.positivity.accounting.internal.repository.ReprocessingAttemptHistoryRepository reprocessingAttemptHistoryRepository;

        @Mock
        private IdempotencyService idempotencyService;

        @Mock
        private com.positivity.accounting.internal.audit.repository.AuditTrailEntryRepository auditTrailEntryRepository;

        @Mock
        private PostingEngineOrchestrator postingEngineOrchestrator;

        @InjectMocks
        private EventIngestionService service;

        private UUID testOrganizationId;
        private UUID testEventId;
        private AccountingEvent testEvent;
        private Map<String, Object> testEventMap;

        @BeforeEach
        void setUp() {
                testOrganizationId = UUID.randomUUID();
                testEventId = UUID.randomUUID();

                testEvent = new AccountingEvent();
                testEvent.setEventId(testEventId);
                testEvent.setOrganizationId(testOrganizationId);
                testEvent.setEventType("INVOICE_RECEIVED");
                testEvent.setTransactionDate(LocalDateTime.now());
                testEvent.setPayload(Map.of("amount", "1500.00"));
                testEvent.setStatus(AccountingEventStatus.RECEIVED);
                testEvent.setReceivedAt(Instant.now());

                testEventMap = Map.of(
                                "eventId", testEventId,
                                "organizationId", testOrganizationId,
                                "eventType", "INVOICE_RECEIVED",
                                "sourceSystem", "MYOB",
                                "transactionDate", LocalDateTime.now(),
                                "payload", Map.of("amount", "1500.00"));
        }

        @Test
        @DisplayName("listEvents should return paginated events for organization")
        void testListEvents_WithOrganizationId() {
                // Arrange
                List<AccountingEvent> events = List.of(testEvent);
                Page<AccountingEvent> eventPage = new PageImpl<>(events, PageRequest.of(0, 20), 1);
                Pageable pageable = PageRequest.of(0, 20);

                when(accountingEventRepository.findByOrganizationId(testOrganizationId, pageable))
                                .thenReturn(eventPage);

                // Act
                Page<AccountingEventResponse> result = service.listEvents(testOrganizationId, null, pageable);

                // Assert
                assertThat(result).isNotNull();
                assertThat(result.getContent()).hasSize(1);
                assertThat(result.getContent().get(0).getEventId()).isEqualTo(testEventId);
                assertThat(result.getContent().get(0).getEventType()).isEqualTo("INVOICE_RECEIVED");
                assertThat(result.getContent().get(0).getStatus()).isEqualTo(AccountingEventStatus.RECEIVED);

                verify(accountingEventRepository).findByOrganizationId(testOrganizationId, pageable);
        }

        @Test
        @DisplayName("listEvents should filter by status when provided")
        void testListEvents_WithStatusFilter() {
                // Arrange
                List<AccountingEvent> events = List.of(testEvent);
                Page<AccountingEvent> eventPage = new PageImpl<>(events, PageRequest.of(0, 20), 1);
                Pageable pageable = PageRequest.of(0, 20);

                when(accountingEventRepository.findByOrganizationIdAndStatus(
                                testOrganizationId, AccountingEventStatus.RECEIVED, pageable))
                                .thenReturn(eventPage);

                // Act
                Page<AccountingEventResponse> result = service.listEvents(testOrganizationId, "RECEIVED", pageable);

                // Assert
                assertThat(result).isNotNull();
                assertThat(result.getContent()).hasSize(1);
                assertThat(result.getContent().get(0).getStatus()).isEqualTo(AccountingEventStatus.RECEIVED);

                verify(accountingEventRepository).findByOrganizationIdAndStatus(
                                testOrganizationId, AccountingEventStatus.RECEIVED, pageable);
        }

        @Test
        @DisplayName("listEvents should handle invalid status filter gracefully")
        void testListEvents_WithInvalidStatusFilter() {
                // Arrange
                List<AccountingEvent> events = List.of(testEvent);
                Page<AccountingEvent> eventPage = new PageImpl<>(events, PageRequest.of(0, 20), 1);
                Pageable pageable = PageRequest.of(0, 20);

                when(accountingEventRepository.findByOrganizationId(testOrganizationId, pageable))
                                .thenReturn(eventPage);

                // Act
                Page<AccountingEventResponse> result = service.listEvents(testOrganizationId, "INVALID_STATUS",
                                pageable);

                // Assert
                assertThat(result).isNotNull();
                assertThat(result.getContent()).hasSize(1);

                // Should fall back to listing all events without status filter
                verify(accountingEventRepository).findByOrganizationId(testOrganizationId, pageable);
        }

        @Test
        @DisplayName("listEvents should return empty page when no events found")
        void testListEvents_NoEventsFound() {
                // Arrange
                Page<AccountingEvent> emptyPage = new PageImpl<>(List.of(), PageRequest.of(0, 20), 0);
                Pageable pageable = PageRequest.of(0, 20);

                when(accountingEventRepository.findByOrganizationId(testOrganizationId, pageable))
                                .thenReturn(emptyPage);

                // Act
                Page<AccountingEventResponse> result = service.listEvents(testOrganizationId, null, pageable);

                // Assert
                assertThat(result).isNotNull();
                assertThat(result.getContent()).isEmpty();
                assertThat(result.getTotalElements()).isEqualTo(0);
        }

        @Test
        @DisplayName("getEventById should return event when found")
        void testGetEventById_Found() {
                // Arrange
                when(accountingEventRepository.findById(testEventId))
                                .thenReturn(Optional.of(testEvent));

                // Act
                AccountingEventResponse result = service.getEventById(testEventId);

                // Assert
                assertThat(result).isNotNull();
                assertThat(result.getEventId()).isEqualTo(testEventId);
                assertThat(result.getEventType()).isEqualTo("INVOICE_RECEIVED");
                assertThat(result.getStatus()).isEqualTo(AccountingEventStatus.RECEIVED);

                verify(accountingEventRepository).findById(testEventId);
        }

        @Test
        @DisplayName("getEventById should throw exception when event not found")
        void testGetEventById_NotFound() {
                // Arrange
                when(accountingEventRepository.findById(testEventId))
                                .thenReturn(Optional.empty());

                // Act & Assert
                assertThatThrownBy(() -> service.getEventById(testEventId))
                                .isInstanceOf(EventNotFoundException.class)
                                .hasMessageContaining("Event not found");

                verify(accountingEventRepository).findById(testEventId);
        }

        @Test
        @DisplayName("getEvent should return event payload when found")
        void testGetEvent_Found() {
                // Arrange
                when(accountingEventRepository.findById(testEventId))
                                .thenReturn(Optional.of(testEvent));

                // Act
                Map<String, Object> result = service.getEvent(testEventId);

                // Assert
                assertThat(result).isNotNull();
                assertThat(result).containsEntry("amount", "1500.00");

                verify(accountingEventRepository).findById(testEventId);
        }

        @Test
        @DisplayName("getEvent should return empty map when event not found")
        void testGetEvent_NotFound() {
                // Arrange
                when(accountingEventRepository.findById(testEventId))
                                .thenReturn(Optional.empty());

                // Act
                Map<String, Object> result = service.getEvent(testEventId);

                // Assert
                assertThat(result).isNotNull();
                assertThat(result).isEmpty();

                verify(accountingEventRepository).findById(testEventId);
        }

        @Test
        @DisplayName("submitEvent should persist event to database")
        void testSubmitEvent_PersistsEvent() {
                // Arrange
                AccountingEvent savedEvent = new AccountingEvent();
                savedEvent.setEventId(testEventId);
                savedEvent.setOrganizationId(testOrganizationId);
                savedEvent.setEventType("INVOICE_RECEIVED");
                savedEvent.setStatus(AccountingEventStatus.RECEIVED);
                savedEvent.setReceivedAt(Instant.now());

                when(idempotencyService.isKeyProcessed(any(String.class)))
                                .thenReturn(false); // Not a duplicate
                when(accountingEventRepository.save(any(AccountingEvent.class)))
                                .thenReturn(savedEvent);

                // Act
                AccountingEventResponse result = service.submitEvent(testEventMap);

                // Assert
                assertThat(result).isNotNull();
                assertThat(result.getEventId()).isEqualTo(testEventId);
                assertThat(result.getStatus()).isEqualTo(AccountingEventStatus.RECEIVED);

                verify(idempotencyService).isKeyProcessed(any(String.class));
                verify(accountingEventRepository).save(any(AccountingEvent.class));
                verify(idempotencyService).registerKey(any(String.class), any(UUID.class));
        }
}

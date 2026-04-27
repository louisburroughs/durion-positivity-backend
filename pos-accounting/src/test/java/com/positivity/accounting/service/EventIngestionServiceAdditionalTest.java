package com.positivity.accounting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.audit.entity.AuditTrailEntry;
import com.positivity.accounting.internal.audit.entity.ExceptionType;
import com.positivity.accounting.internal.audit.repository.AuditTrailEntryRepository;
import com.positivity.accounting.internal.dto.AccountingEventResponse;
import com.positivity.accounting.internal.dto.DuplicateEventException;
import com.positivity.accounting.internal.dto.EventProcessingLogEntry;
import com.positivity.accounting.internal.dto.PostingResult;
import com.positivity.accounting.internal.dto.ReprocessEventRequest;
import com.positivity.accounting.internal.dto.ReprocessingAttemptHistoryResponse;
import com.positivity.accounting.internal.entity.AccountingEvent;
import com.positivity.accounting.internal.entity.ReprocessingAttemptHistory;
import com.positivity.accounting.internal.enums.AccountingEventStatus;
import com.positivity.accounting.internal.enums.PostingFailureReason;
import com.positivity.accounting.internal.exception.EventNotFoundException;
import com.positivity.accounting.internal.repository.AccountingEventRepository;
import com.positivity.accounting.internal.repository.ReprocessingAttemptHistoryRepository;
import com.positivity.accounting.internal.service.EventIngestionServiceImpl;
import com.positivity.accounting.internal.service.IdempotencyServiceImpl;
import com.positivity.accounting.internal.service.PostingEngineOrchestrator;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
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
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

/**
 * Additional unit tests for EventIngestionServiceImpl.
 * Covers retryEventProcessing, reprocessEvent, getReprocessingHistory,
 * getEventProcessingLog, findBySourceSystem, processFailed, validateEvent,
 * and submitEvent edge cases.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("EventIngestionService Additional Unit Tests")
class EventIngestionServiceAdditionalTest {

    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC);

    @Spy
    Clock clock = TEST_CLOCK;

    @Mock
    private AccountingEventRepository accountingEventRepository;

    @Mock
    private ReprocessingAttemptHistoryRepository reprocessingAttemptHistoryRepository;

    @Mock
    private IdempotencyServiceImpl idempotencyService;

    @Mock
    private AuditTrailEntryRepository auditTrailEntryRepository;

    @Mock
    private PostingEngineOrchestrator postingEngineOrchestrator;

    @InjectMocks
    private EventIngestionServiceImpl service;

    private UUID testEventId;
    private UUID testOrganizationId;

    @BeforeEach
    void setUp() {
        testEventId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        testOrganizationId = UUID.fromString("00000000-0000-0000-0000-000000000002");
    }

    // ========================================
    // retryEventProcessing Tests
    // ========================================

    @Test
    @DisplayName("retryEventProcessing should reset status to RECEIVED and return response")
    void retryEventProcessing_Success() {
        AccountingEvent event = buildEvent(testEventId, AccountingEventStatus.FAILED);
        AccountingEvent savedEvent = buildEvent(testEventId, AccountingEventStatus.RECEIVED);

        when(accountingEventRepository.findById(testEventId)).thenReturn(Optional.of(event));
        when(accountingEventRepository.save(event)).thenReturn(savedEvent);

        AccountingEventResponse result = service.retryEventProcessing(testEventId);

        assertThat(result).isNotNull();
        assertThat(result.getEventId()).isEqualTo(testEventId);
        verify(accountingEventRepository).save(event);
    }

    @Test
    @DisplayName("retryEventProcessing should throw EventNotFoundException when event not found")
    void retryEventProcessing_NotFound() {
        when(accountingEventRepository.findById(testEventId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.retryEventProcessing(testEventId))
                .isInstanceOf(EventNotFoundException.class)
                .hasMessageContaining("Event not found");
    }

    @Test
    @DisplayName("retryEvent should delegate to retryEventProcessing")
    void retryEvent_DelegatesToRetryEventProcessing() {
        AccountingEvent event = buildEvent(testEventId, AccountingEventStatus.FAILED);
        AccountingEvent savedEvent = buildEvent(testEventId, AccountingEventStatus.RECEIVED);

        when(accountingEventRepository.findById(testEventId)).thenReturn(Optional.of(event));
        when(accountingEventRepository.save(event)).thenReturn(savedEvent);

        AccountingEventResponse result = service.retryEvent(testEventId);

        assertThat(result).isNotNull();
        assertThat(result.getEventId()).isEqualTo(testEventId);
    }

    // ========================================
    // reprocessEvent Tests
    // ========================================

    @Test
    @DisplayName("reprocessEvent should throw EventNotFoundException when event not found")
    void reprocessEvent_EventNotFound() {
        when(accountingEventRepository.findById(testEventId)).thenReturn(Optional.empty());

        ReprocessEventRequest request = buildReprocessRequest("test-user", null);

        assertThatThrownBy(() -> service.reprocessEvent(testEventId, request))
                .isInstanceOf(EventNotFoundException.class)
                .hasMessageContaining("Event not found");
    }

    @Test
    @DisplayName("reprocessEvent should throw IllegalStateException when event is already PROCESSED")
    void reprocessEvent_AlreadyProcessed() {
        AccountingEvent event = buildEvent(testEventId, AccountingEventStatus.PROCESSED);
        when(accountingEventRepository.findById(testEventId)).thenReturn(Optional.of(event));

        ReprocessEventRequest request = buildReprocessRequest("test-user", null);

        assertThatThrownBy(() -> service.reprocessEvent(testEventId, request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already PROCESSED");
    }

    @Test
    @DisplayName("reprocessEvent should throw IllegalStateException when event is RECEIVED (not eligible)")
    void reprocessEvent_NotEligibleStatus() {
        AccountingEvent event = buildEvent(testEventId, AccountingEventStatus.RECEIVED);
        when(accountingEventRepository.findById(testEventId)).thenReturn(Optional.of(event));

        ReprocessEventRequest request = buildReprocessRequest("test-user", null);

        assertThatThrownBy(() -> service.reprocessEvent(testEventId, request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cannot be reprocessed");
    }

    @Test
    @DisplayName("reprocessEvent should throw IllegalStateException when mappingVersionToUse is invalid UUID")
    void reprocessEvent_InvalidMappingVersionUUID() {
        AccountingEvent suspendedEvent = buildEvent(testEventId, AccountingEventStatus.SUSPENDED);
        AccountingEvent processingEvent = buildEvent(testEventId, AccountingEventStatus.PROCESSING);
        when(accountingEventRepository.findById(testEventId)).thenReturn(Optional.of(suspendedEvent));
        when(accountingEventRepository.save(any(AccountingEvent.class))).thenReturn(processingEvent);

        ReprocessEventRequest request = buildReprocessRequest("test-user", "not-a-valid-uuid");

        assertThatThrownBy(() -> service.reprocessEvent(testEventId, request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to reprocess event");
    }

    @Test
    @DisplayName("reprocessEvent should succeed when orchestrator returns success")
    void reprocessEvent_OrchestratorSucceeds() {
        AccountingEvent suspendedEvent = buildEvent(testEventId, AccountingEventStatus.SUSPENDED);
        AccountingEvent processedEvent = buildEvent(testEventId, AccountingEventStatus.PROCESSED);
        when(accountingEventRepository.findById(testEventId))
                .thenReturn(Optional.of(suspendedEvent))
                .thenReturn(Optional.of(processedEvent));
        when(accountingEventRepository.save(any(AccountingEvent.class))).thenReturn(suspendedEvent);

        PostingResult successResult = PostingResult.success(null, null);
        when(postingEngineOrchestrator.processEvent(any(), any(), anyString(), eq(true)))
                .thenReturn(successResult);

        ReprocessEventRequest request = buildReprocessRequest("test-user", null);

        AccountingEventResponse response = service.reprocessEvent(testEventId, request);

        assertThat(response).isNotNull();
        assertThat(response.getStatus()).isEqualTo(AccountingEventStatus.PROCESSED);
        verify(postingEngineOrchestrator).processEvent(any(), any(), eq("test-user"), eq(true));
    }

    @Test
    @DisplayName("reprocessEvent should succeed when orchestrator returns failure result gracefully")
    void reprocessEvent_OrchestratorFails_GracefulReturn() {
        AccountingEvent suspendedEvent = buildEvent(testEventId, AccountingEventStatus.SUSPENDED);
        AccountingEvent failedEvent = buildEvent(testEventId, AccountingEventStatus.FAILED);
        when(accountingEventRepository.findById(testEventId))
                .thenReturn(Optional.of(suspendedEvent))
                .thenReturn(Optional.of(failedEvent));
        when(accountingEventRepository.save(any(AccountingEvent.class))).thenReturn(suspendedEvent);

        PostingResult failedResult = PostingResult.builder()
                .success(false)
                .failureReason(PostingFailureReason.NO_RULE_VERSION)
                .failureDetails("No rule found")
                .evaluationDetails(new HashMap<>())
                .build();
        when(postingEngineOrchestrator.processEvent(any(), any(), anyString(), eq(true)))
                .thenReturn(failedResult);

        ReprocessEventRequest request = buildReprocessRequest("test-user", null);
        AccountingEventResponse response = service.reprocessEvent(testEventId, request);

        assertThat(response).isNotNull();
        assertThat(response.getStatus()).isEqualTo(AccountingEventStatus.FAILED);
    }

    @Test
    @DisplayName("reprocessEvent should throw IllegalStateException on OptimisticLockingFailureException")
    void reprocessEvent_OptimisticLockingFailure() {
        AccountingEvent suspendedEvent = buildEvent(testEventId, AccountingEventStatus.SUSPENDED);
        when(accountingEventRepository.findById(testEventId)).thenReturn(Optional.of(suspendedEvent));
        when(accountingEventRepository.save(any(AccountingEvent.class)))
                .thenThrow(new OptimisticLockingFailureException("Concurrent modification"));

        ReprocessEventRequest request = buildReprocessRequest("test-user", null);

        assertThatThrownBy(() -> service.reprocessEvent(testEventId, request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Concurrent reprocessing detected");
    }

    @Test
    @DisplayName("reprocessEvent should throw IllegalStateException on unexpected exception")
    void reprocessEvent_UnexpectedException() {
        AccountingEvent suspendedEvent = buildEvent(testEventId, AccountingEventStatus.SUSPENDED);
        AccountingEvent processingEvent = buildEvent(testEventId, AccountingEventStatus.PROCESSING);
        when(accountingEventRepository.findById(testEventId)).thenReturn(Optional.of(suspendedEvent));
        when(accountingEventRepository.save(any(AccountingEvent.class))).thenReturn(processingEvent);
        when(postingEngineOrchestrator.processEvent(any(), any(), anyString(), eq(true)))
                .thenThrow(new RuntimeException("Unexpected DB error"));

        ReprocessEventRequest request = buildReprocessRequest("test-user", null);

        assertThatThrownBy(() -> service.reprocessEvent(testEventId, request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to reprocess event");
    }

    @Test
    @DisplayName("reprocessEvent should parse valid mappingVersionToUse UUID")
    void reprocessEvent_ValidMappingVersionUUID() {
        UUID mappingVersion = UUID.fromString("00000000-0000-0000-0000-000000000010");
        AccountingEvent suspendedEvent = buildEvent(testEventId, AccountingEventStatus.SUSPENDED);
        AccountingEvent processedEvent = buildEvent(testEventId, AccountingEventStatus.PROCESSED);
        when(accountingEventRepository.findById(testEventId))
                .thenReturn(Optional.of(suspendedEvent))
                .thenReturn(Optional.of(processedEvent));
        when(accountingEventRepository.save(any(AccountingEvent.class))).thenReturn(suspendedEvent);

        PostingResult successResult = PostingResult.success(null, mappingVersion);
        when(postingEngineOrchestrator.processEvent(any(), eq(mappingVersion), anyString(), eq(true)))
                .thenReturn(successResult);

        ReprocessEventRequest request = buildReprocessRequest("test-user", mappingVersion.toString());

        AccountingEventResponse response = service.reprocessEvent(testEventId, request);
        assertThat(response).isNotNull();
        verify(postingEngineOrchestrator).processEvent(any(), eq(mappingVersion), anyString(), eq(true));
    }

    // ========================================
    // getReprocessingHistory Tests
    // ========================================

    @Test
    @DisplayName("getReprocessingHistory should return empty list when no history found")
    void getReprocessingHistory_Empty() {
        when(reprocessingAttemptHistoryRepository.findByAccountingEvent_EventIdOrderByAttemptedAtDesc(testEventId))
                .thenReturn(List.of());

        List<ReprocessingAttemptHistoryResponse> result = service.getReprocessingHistory(testEventId);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("getReprocessingHistory should return mapped responses when history exists")
    void getReprocessingHistory_WithEntries() {
        AccountingEvent event = buildEvent(testEventId, AccountingEventStatus.PROCESSED);
        ReprocessingAttemptHistory history = new ReprocessingAttemptHistory();
        history.setAccountingEvent(event);
        history.setAttemptedAt(Instant.now(TEST_CLOCK));
        history.setTriggeredByUserId("operator-1");

        when(reprocessingAttemptHistoryRepository.findByAccountingEvent_EventIdOrderByAttemptedAtDesc(testEventId))
                .thenReturn(List.of(history));

        List<ReprocessingAttemptHistoryResponse> result = service.getReprocessingHistory(testEventId);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getEventId()).isEqualTo(testEventId);
        assertThat(result.get(0).getTriggeredByUserId()).isEqualTo("operator-1");
    }

    // ========================================
    // getEventProcessingLog Tests
    // ========================================

    @Test
    @DisplayName("getEventProcessingLog should return empty list when no entries found")
    void getEventProcessingLog_NoEntries() {
        when(auditTrailEntryRepository.findBySourceEventId(testEventId)).thenReturn(List.of());

        List<EventProcessingLogEntry> result = service.getEventProcessingLog(testEventId);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("getEventProcessingLog should format and return audit entries")
    void getEventProcessingLog_WithEntries() {
        AuditTrailEntry entry = AuditTrailEntry.builder()
                .timestamp(Instant.now(TEST_CLOCK))
                .exceptionType(ExceptionType.PRICE_OVERRIDE)
                .actorId("actor-1")
                .actorRole("MANAGER")
                .accountingStatus(com.positivity.accounting.internal.enums.AccountingStatus.POSTED)
                .reason("Manual override")
                .build();

        when(auditTrailEntryRepository.findBySourceEventId(testEventId)).thenReturn(List.of(entry));

        List<EventProcessingLogEntry> result = service.getEventProcessingLog(testEventId);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getMessage()).isEqualTo("Manual override");
        assertThat(result.get(0).getSeverity()).isEqualTo("INFO");
        assertThat(result.get(0).getContextJson()).isNull();
    }

    // ========================================
    // findBySourceSystem Test
    // ========================================

    @Test
    @DisplayName("findBySourceSystem should return paginated results")
    void findBySourceSystem_ReturnsPaginatedPage() {
        AccountingEvent event = buildEvent(testEventId, AccountingEventStatus.RECEIVED);
        Page<AccountingEvent> page = new PageImpl<>(List.of(event), PageRequest.of(0, 10), 1);
        when(accountingEventRepository.findBySourceSystem("MYOB", PageRequest.of(0, 10)))
                .thenReturn(page);

        Page<AccountingEventResponse> result = service.findBySourceSystem("MYOB", PageRequest.of(0, 10));

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).getEventId()).isEqualTo(testEventId);
        verify(accountingEventRepository).findBySourceSystem("MYOB", PageRequest.of(0, 10));
    }

    // ========================================
    // processFailed Tests
    // ========================================

    @Test
    @DisplayName("processFailed should return 0 when no events are eligible")
    void processFailed_EmptyList() {
        when(accountingEventRepository.findAll()).thenReturn(List.of());

        int result = service.processFailed(3);

        assertThat(result).isEqualTo(0);
    }

    @Test
    @DisplayName("processFailed should return 0 and log errors when reprocessEvent throws")
    void processFailed_ExceptionInLoop() {
        UUID failedEventId = UUID.fromString("00000000-0000-0000-0000-000000000099");
        AccountingEvent failedEvent = buildEvent(failedEventId, AccountingEventStatus.FAILED);
        failedEvent.setAttemptCount(0);

        when(accountingEventRepository.findAll()).thenReturn(List.of(failedEvent));
        // reprocessEvent will call findById → throw RuntimeException to simulate
        // failure
        when(accountingEventRepository.findById(failedEventId)).thenThrow(new RuntimeException("DB unavailable"));

        int result = service.processFailed(3);

        assertThat(result).isEqualTo(0);
    }

    @Test
    @DisplayName("processFailed should skip events that already reached maxRetries")
    void processFailed_SkipsEventsAtMaxRetries() {
        UUID eventId = UUID.fromString("00000000-0000-0000-0000-000000000088");
        AccountingEvent eventAtMax = buildEvent(eventId, AccountingEventStatus.FAILED);
        eventAtMax.setAttemptCount(3); // at maxRetries

        when(accountingEventRepository.findAll()).thenReturn(List.of(eventAtMax));

        int result = service.processFailed(3);

        assertThat(result).isEqualTo(0);
        verify(accountingEventRepository, never()).findById(eventId);
    }

    // ========================================
    // validateEvent Tests
    // ========================================

    @Test
    @DisplayName("validateEvent should return error when organizationId is missing")
    void validateEvent_MissingOrganizationId() {
        Map<String, Object> event = new HashMap<>();
        event.put("eventType", "INVOICE_RECEIVED");
        event.put("payload", Map.of("amount", "100.00"));

        List<String> errors = service.validateEvent(event);

        assertThat(errors).anyMatch(e -> e.contains("organizationId"));
    }

    @Test
    @DisplayName("validateEvent should return error when eventType is missing")
    void validateEvent_MissingEventType() {
        Map<String, Object> event = new HashMap<>();
        event.put("organizationId", testOrganizationId);
        event.put("payload", Map.of("amount", "100.00"));

        List<String> errors = service.validateEvent(event);

        assertThat(errors).anyMatch(e -> e.contains("eventType"));
    }

    @Test
    @DisplayName("validateEvent should return error when payload is missing")
    void validateEvent_MissingPayload() {
        Map<String, Object> event = new HashMap<>();
        event.put("organizationId", testOrganizationId);
        event.put("eventType", "INVOICE_RECEIVED");

        List<String> errors = service.validateEvent(event);

        assertThat(errors).anyMatch(e -> e.contains("payload"));
    }

    @Test
    @DisplayName("validateEvent should return empty list when all required fields are present")
    void validateEvent_Valid() {
        Map<String, Object> event = new HashMap<>();
        event.put("organizationId", testOrganizationId);
        event.put("eventType", "INVOICE_RECEIVED");
        event.put("payload", Map.of("amount", "100.00"));

        List<String> errors = service.validateEvent(event);

        assertThat(errors).isEmpty();
    }

    // ========================================
    // submitEvent Edge Cases
    // ========================================

    @Test
    @DisplayName("submitEvent should apply default sourceSystem when blank")
    void submitEvent_BlankSourceSystem_UsesDefault() {
        AccountingEvent savedEvent = buildEvent(testEventId, AccountingEventStatus.RECEIVED);
        Map<String, Object> eventMap = buildEventMap(testOrganizationId, "INVOICE_RECEIVED", "  ",
                LocalDateTime.now(TEST_CLOCK));

        when(idempotencyService.isKeyProcessed(any())).thenReturn(false);
        when(accountingEventRepository.save(any(AccountingEvent.class))).thenReturn(savedEvent);

        AccountingEventResponse result = service.submitEvent(eventMap);

        assertThat(result).isNotNull();
        assertThat(result.getStatus()).isEqualTo(AccountingEventStatus.RECEIVED);
    }

    @Test
    @DisplayName("submitEvent should apply current time when transactionDate is null")
    void submitEvent_NullTransactionDate_UsesNow() {
        AccountingEvent savedEvent = buildEvent(testEventId, AccountingEventStatus.RECEIVED);
        Map<String, Object> eventMap = buildEventMap(testOrganizationId, "INVOICE_RECEIVED", "MYOB", null);

        when(idempotencyService.isKeyProcessed(any())).thenReturn(false);
        when(accountingEventRepository.save(any(AccountingEvent.class))).thenReturn(savedEvent);

        AccountingEventResponse result = service.submitEvent(eventMap);

        assertThat(result).isNotNull();
        assertThat(result.getStatus()).isEqualTo(AccountingEventStatus.RECEIVED);
    }

    @Test
    @DisplayName("submitEvent should throw DuplicateEventException when duplicate event detected")
    void submitEvent_DuplicateEvent() {
        Map<String, Object> eventMap = buildEventMap(testOrganizationId, "INVOICE_RECEIVED", "MYOB",
                LocalDateTime.now(TEST_CLOCK));

        when(idempotencyService.isKeyProcessed(any())).thenReturn(true);

        assertThatThrownBy(() -> service.submitEvent(eventMap))
                .isInstanceOf(DuplicateEventException.class)
                .hasMessageContaining("Duplicate event detected");
    }

    @Test
    @DisplayName("submitEvent should accept String transactionDate in ISO-8601 format")
    void submitEvent_StringTransactionDate_Parsed() {
        AccountingEvent savedEvent = buildEvent(testEventId, AccountingEventStatus.RECEIVED);
        Map<String, Object> eventMap = buildEventMap(testOrganizationId, "INVOICE_RECEIVED", "MYOB", null);
        eventMap.put("transactionDate", "2024-01-15T10:30:00"); // String form

        when(idempotencyService.isKeyProcessed(any())).thenReturn(false);
        when(accountingEventRepository.save(any(AccountingEvent.class))).thenReturn(savedEvent);

        AccountingEventResponse result = service.submitEvent(eventMap);

        assertThat(result).isNotNull();
    }

    @Test
    @DisplayName("submitEvent should throw IllegalArgumentException when event fails validation")
    void submitEvent_ValidationFailure_Throws() {
        Map<String, Object> eventMap = new HashMap<>();
        eventMap.put("organizationId", testOrganizationId);
        // Missing eventType and payload

        assertThatThrownBy(() -> service.submitEvent(eventMap))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Event validation failed");

        verify(accountingEventRepository, never()).save(any());
    }

    // ========================================
    // Helper Methods
    // ========================================

    private AccountingEvent buildEvent(UUID eventId, AccountingEventStatus status) {
        AccountingEvent event = new AccountingEvent();
        event.setEventId(eventId);
        event.setOrganizationId(testOrganizationId);
        event.setEventType("INVOICE_RECEIVED");
        event.setStatus(status);
        event.setTransactionDate(LocalDateTime.now(TEST_CLOCK));
        event.setReceivedAt(Instant.now(TEST_CLOCK));
        return event;
    }

    private ReprocessEventRequest buildReprocessRequest(String userId, String mappingVersion) {
        ReprocessEventRequest request = new ReprocessEventRequest();
        request.setTriggeredByUserId(userId);
        request.setMappingVersionToUse(mappingVersion);
        return request;
    }

    private Map<String, Object> buildEventMap(
            UUID orgId, String eventType, String sourceSystem, LocalDateTime transactionDate) {
        Map<String, Object> map = new HashMap<>();
        map.put("organizationId", orgId);
        map.put("eventType", eventType);
        map.put("sourceSystem", sourceSystem);
        map.put("transactionDate", transactionDate);
        map.put("payload", Map.of("amount", "1500.00"));
        return map;
    }
}

package com.positivity.accounting.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.audit.repository.AuditTrailEntryRepository;
import com.positivity.accounting.internal.config.AccountingEventTypeRegistry;
import com.positivity.accounting.internal.dto.AccountingEventFilter;
import com.positivity.accounting.internal.dto.AccountingEventResponse;
import com.positivity.accounting.internal.dto.AccountingEventTypeResponse;
import com.positivity.accounting.internal.dto.EventEnvelopeContract;
import com.positivity.accounting.internal.dto.FactConsumptionIdempotency;
import com.positivity.accounting.internal.dto.FactPostingKeyDescriptor;
import com.positivity.accounting.internal.dto.IdempotencyOutcomeDescriptor;
import com.positivity.accounting.internal.dto.ProcessingStatusDescriptor;
import com.positivity.accounting.internal.dto.TraceabilityIdDescriptor;
import com.positivity.accounting.internal.entity.AccountingEvent;
import com.positivity.accounting.internal.entity.AccountingSequence;
import com.positivity.accounting.internal.enums.AccountingEventStatus;
import com.positivity.accounting.internal.enums.IdempotencyOutcome;
import com.positivity.accounting.internal.exception.EventNotFoundException;
import com.positivity.accounting.internal.repository.AccountingEventRepository;
import com.positivity.accounting.internal.repository.ReprocessingAttemptHistoryRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

/**
 * Unit tests for EventIngestionService
 *
 * Tests event ingestion, retrieval, and pagination functionality.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EventIngestionService Unit Tests")
class EventIngestionServiceTest {
    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC);

    @Spy
    Clock clock = TEST_CLOCK;

    @Spy
    AccountingCalendarZoneResolver zoneResolver = TestZoneResolvers.utc(TEST_CLOCK);

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

    @Mock
    private AccountingSequenceLocker sequenceLocker;

    /** Issue #1778: payload display projection, attached to the detail response only. */
    @Mock
    private EventPayloadReferenceProjector eventPayloadReferenceProjector;

    @InjectMocks
    private EventIngestionServiceImpl service;

    private UUID testOrganizationId;
    private UUID testEventId;
    private AccountingEvent testEvent;
    private Map<String, Object> testEventMap;

    @BeforeEach
    void setUp() {
        testOrganizationId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        testEventId = UUID.fromString("00000000-0000-0000-0000-000000000001");

        testEvent = new AccountingEvent();
        testEvent.setEventId(testEventId);
        testEvent.setOrganizationId(testOrganizationId);
        testEvent.setEventType("INVOICE_RECEIVED");
        testEvent.setTransactionDate(LocalDateTime.now(TEST_CLOCK));
        testEvent.setPayload(Map.of("amount", "1500.00"));
        testEvent.setStatus(AccountingEventStatus.RECEIVED);
        testEvent.setReceivedAt(Instant.now(TEST_CLOCK));

        testEventMap = Map.of(
                "eventId",
                testEventId,
                "organizationId",
                testOrganizationId,
                "eventType",
                "INVOICE_RECEIVED",
                "sourceSystem",
                "MYOB",
                "transactionDate",
                LocalDateTime.now(TEST_CLOCK),
                "payload",
                Map.of("amount", "1500.00"));
    }

    @Test
    @DisplayName("listEvents should return paginated events for a filtered query")
    void testListEvents_WithOrganizationId() {
        // Arrange
        List<AccountingEvent> events = List.of(testEvent);
        Page<AccountingEvent> eventPage = new PageImpl<>(events, PageRequest.of(0, 20), 1);
        Pageable pageable = PageRequest.of(0, 20);

        when(accountingEventRepository.findAll(
                        org.mockito.ArgumentMatchers.<Specification<AccountingEvent>>any(), eq(pageable)))
                .thenReturn(eventPage);

        // Act
        AccountingEventFilter filter =
                AccountingEventFilter.builder().eventType("INVOICE_RECEIVED").build();
        Page<AccountingEventResponse> result = service.listEvents(filter, pageable);

        // Assert
        assertThat(result).isNotNull();
        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).getEventId()).isEqualTo(testEventId);
        assertThat(result.getContent().get(0).getEventType()).isEqualTo("INVOICE_RECEIVED");
        assertThat(result.getContent().get(0).getStatus()).isEqualTo(AccountingEventStatus.RECEIVED);

        verify(accountingEventRepository)
                .findAll(org.mockito.ArgumentMatchers.<Specification<AccountingEvent>>any(), eq(pageable));
    }

    @Test
    @DisplayName("listEvents should filter by status when provided")
    void testListEvents_WithStatusFilter() {
        // Arrange
        List<AccountingEvent> events = List.of(testEvent);
        Page<AccountingEvent> eventPage = new PageImpl<>(events, PageRequest.of(0, 20), 1);
        Pageable pageable = PageRequest.of(0, 20);

        when(accountingEventRepository.findAll(
                        org.mockito.ArgumentMatchers.<Specification<AccountingEvent>>any(), eq(pageable)))
                .thenReturn(eventPage);

        // Act
        AccountingEventFilter filter = AccountingEventFilter.builder()
                .status(AccountingEventStatus.RECEIVED)
                .build();
        Page<AccountingEventResponse> result = service.listEvents(filter, pageable);

        // Assert
        assertThat(result).isNotNull();
        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).getStatus()).isEqualTo(AccountingEventStatus.RECEIVED);

        verify(accountingEventRepository)
                .findAll(org.mockito.ArgumentMatchers.<Specification<AccountingEvent>>any(), eq(pageable));
    }

    @Test
    @DisplayName("listEvents should return all events when no filter is supplied")
    void testListEvents_WithoutFilter() {
        // Arrange
        List<AccountingEvent> events = List.of(testEvent);
        Page<AccountingEvent> eventPage = new PageImpl<>(events, PageRequest.of(0, 20), 1);
        Pageable pageable = PageRequest.of(0, 20);

        when(accountingEventRepository.findAll(
                        org.mockito.ArgumentMatchers.<Specification<AccountingEvent>>any(), eq(pageable)))
                .thenReturn(eventPage);

        // Act
        AccountingEventFilter filter = AccountingEventFilter.builder().build();
        Page<AccountingEventResponse> result = service.listEvents(filter, pageable);

        // Assert
        assertThat(result).isNotNull();
        assertThat(result.getContent()).hasSize(1);

        verify(accountingEventRepository)
                .findAll(org.mockito.ArgumentMatchers.<Specification<AccountingEvent>>any(), eq(pageable));
    }

    @Test
    @DisplayName("listEvents should return empty page when no events found")
    void testListEvents_NoEventsFound() {
        // Arrange
        Page<AccountingEvent> emptyPage = new PageImpl<>(List.of(), PageRequest.of(0, 20), 0);
        Pageable pageable = PageRequest.of(0, 20);

        when(accountingEventRepository.findAll(
                        org.mockito.ArgumentMatchers.<Specification<AccountingEvent>>any(), eq(pageable)))
                .thenReturn(emptyPage);

        // Act
        AccountingEventFilter filter = AccountingEventFilter.builder().build();
        Page<AccountingEventResponse> result = service.listEvents(filter, pageable);

        // Assert
        assertThat(result).isNotNull();
        assertThat(result.getContent()).isEmpty();
        assertThat(result.getTotalElements()).isEqualTo(0);
    }

    @Test
    @DisplayName("getEventById should return event when found")
    void testGetEventById_Found() {
        // Arrange
        when(accountingEventRepository.findById(testEventId)).thenReturn(Optional.of(testEvent));

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
        when(accountingEventRepository.findById(testEventId)).thenReturn(Optional.empty());

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
        when(accountingEventRepository.findById(testEventId)).thenReturn(Optional.of(testEvent));

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
        when(accountingEventRepository.findById(testEventId)).thenReturn(Optional.empty());

        // Act
        Map<String, Object> result = service.getEvent(testEventId);

        // Assert
        assertThat(result).isNotNull();
        assertThat(result).isEmpty();

        verify(accountingEventRepository).findById(testEventId);
    }

    @Test
    @DisplayName("#2558: an event submitted without a transactionDate is dated now in the tenant's Chicago calendar"
            + " (2026-01-31T23:30), not the UTC clock's")
    void submitEvent_defaultTransactionDate_isTenantCalendarNow() {
        Clock utc = Clock.fixed(TestZoneResolvers.JAN_31_2330_CHICAGO, ZoneOffset.UTC);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "clock", utc);
        org.springframework.test.util.ReflectionTestUtils.setField(
                service, "zoneResolver", TestZoneResolvers.fixed(TestZoneResolvers.CHICAGO, utc));
        java.util.Map<String, Object> undated = new java.util.HashMap<>(testEventMap);
        undated.remove("transactionDate");
        when(idempotencyService.isKeyProcessed(any(String.class))).thenReturn(false);
        when(accountingEventRepository.save(any(AccountingEvent.class))).thenAnswer(inv -> {
            AccountingEvent event = inv.getArgument(0);
            event.setReceivedAt(TestZoneResolvers.JAN_31_2330_CHICAGO);
            return event;
        });
        AccountingSequence sequence = new AccountingSequence();
        sequence.setNextValue(1L);
        when(sequenceLocker.lockOrProvision(any(String.class))).thenReturn(sequence);

        service.submitEvent(undated);

        org.mockito.ArgumentCaptor<AccountingEvent> saved = org.mockito.ArgumentCaptor.forClass(AccountingEvent.class);
        verify(accountingEventRepository, org.mockito.Mockito.atLeastOnce()).save(saved.capture());
        assertThat(saved.getAllValues().getFirst().getTransactionDate())
                .isEqualTo(LocalDateTime.of(2026, 1, 31, 23, 30));
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
        savedEvent.setReceivedAt(Instant.now(TEST_CLOCK));

        when(idempotencyService.isKeyProcessed(any(String.class))).thenReturn(false); // Not a duplicate
        when(accountingEventRepository.save(any(AccountingEvent.class))).thenReturn(savedEvent);

        AccountingSequence sequence = new AccountingSequence();
        sequence.setScopeKey("AE-202401");
        sequence.setNextValue(1L);
        when(sequenceLocker.lockOrProvision("AE-202401")).thenReturn(sequence);

        // Act
        AccountingEventResponse result = service.submitEvent(testEventMap);

        // Assert
        assertThat(result).isNotNull();
        assertThat(result.getEventId()).isEqualTo(testEventId);
        assertThat(result.getStatus()).isEqualTo(AccountingEventStatus.RECEIVED);
        assertThat(result.getEventReference()).isEqualTo("AE-202401-1");

        verify(idempotencyService).isKeyProcessed(any(String.class));
        verify(accountingEventRepository).save(any(AccountingEvent.class));
        verify(idempotencyService).registerKey(any(String.class), any(UUID.class));
    }

    // ========== Issue #2207: additive EventEnvelopeContract sections ==========

    @Test
    @DisplayName("listEventTypes should publish the registry, including the code-submitted API GL posting types")
    void listEventTypes_publishesRegistryWithApiGlPostingTypes() {
        List<AccountingEventTypeResponse> types = service.listEventTypes();

        assertThat(types).hasSize(AccountingEventTypeRegistry.entries().size());
        assertThat(types)
                .filteredOn(t -> t.ingestion() == AccountingEventTypeRegistry.Ingestion.API)
                .extracting(
                        AccountingEventTypeResponse::code,
                        AccountingEventTypeResponse::sourceDomain,
                        AccountingEventTypeResponse::postsToGl)
                .containsExactlyInAnyOrder(
                        tuple("INVOICE_PAYMENT", "payment", false),
                        tuple("VENDOR_BILL_GL_POSTING", "accounting", true),
                        tuple("AP_PAYMENT_GL_POSTING", "accounting", true));
    }

    @Test
    @DisplayName("getEventContract should publish every AccountingEventStatus constant, derived from the enum")
    void testGetEventContract_PublishesEveryProcessingStatus() {
        EventEnvelopeContract contract = service.getEventContract();

        assertThat(contract.getProcessingStatuses()).isNotNull();
        assertThat(contract.getProcessingStatuses().getStatuses())
                .extracting(ProcessingStatusDescriptor::getStatus)
                .containsExactlyInAnyOrder(AccountingEventStatus.values());
        assertThat(contract.getProcessingStatuses().getStatuses())
                .allSatisfy(descriptor -> assertThat(descriptor.getMeaning()).isNotBlank());
        assertThat(contract.getProcessingStatuses().getRestSubmissionLifecycle())
                .containsExactly("RECEIVED", "PROCESSING", "PROCESSED|FAILED|SUSPENDED");
        assertThat(contract.getProcessingStatuses().getKafkaFactLifecycle())
                .containsExactly("PROCESSED|SKIPPED|SUSPENDED");
    }

    @Test
    @DisplayName("getEventContract should publish every IdempotencyOutcome constant under factConsumption, "
            + "and the REST submission mechanism separately")
    void testGetEventContract_PublishesEveryIdempotencyOutcome() {
        EventEnvelopeContract contract = service.getEventContract();

        assertThat(contract.getIdempotencyOutcomes()).isNotNull();
        assertThat(contract.getIdempotencyOutcomes().getFactConsumption().getOutcomes())
                .extracting(IdempotencyOutcomeDescriptor::getOutcome)
                .containsExactlyInAnyOrder(IdempotencyOutcome.values());
        assertThat(contract.getIdempotencyOutcomes().getFactConsumption().getOutcomes())
                .allSatisfy(
                        descriptor -> assertThat(descriptor.getDescription()).isNotBlank());

        // #2439: the two fact-consumption dedup layers are published separately.
        FactConsumptionIdempotency factConsumption =
                contract.getIdempotencyOutcomes().getFactConsumption();
        assertThat(factConsumption.getEnvelopeDeduplication()).isEqualTo("PROCESSED_EVENTS_BY_EVENT_ID");
        assertThat(factConsumption.getPostingDeduplication())
                .extracting(FactPostingKeyDescriptor::getSourceSystem)
                .contains("pos-inventory", "pos-invoice", "pos-order", "pos-supplier", "pos-warranty");
        assertThat(factConsumption.getPostingDeduplication()).allSatisfy(key -> {
            assertThat(key.getEventTypes()).isNotEmpty();
            assertThat(key.getPostingKey()).isNotBlank();
            assertThat(key.getOnDuplicate()).isNotBlank();
        });
        assertThat(factConsumption.getPostingDeduplication())
                .filteredOn(key -> key.getSourceSystem().equals("pos-supplier"))
                .singleElement()
                .satisfies(key -> {
                    assertThat(key.isPostsJournalEntry()).isFalse();
                    assertThat(key.getDuplicateOutcome()).isEqualTo(IdempotencyOutcome.DUPLICATE_IGNORED);
                });
        assertThat(factConsumption.getPostingDeduplication())
                .filteredOn(key -> key.getSourceSystem().equals("pos-warranty"))
                .singleElement()
                .satisfies(key -> {
                    assertThat(key.isPostsJournalEntry()).isFalse();
                    assertThat(key.getDuplicateOutcome()).isNull();
                });
        assertThat(factConsumption.getPostingDeduplication())
                .filteredOn(key -> key.getSourceSystem().equals("pos-inventory"))
                .singleElement()
                .satisfies(key -> assertThat(key.isPostsJournalEntry()).isTrue());

        assertThat(contract.getIdempotencyOutcomes().getRestSubmission().getOnDuplicateHttpStatus())
                .isEqualTo(409);
        assertThat(contract.getIdempotencyOutcomes().getRestSubmission().getOnDuplicateErrorCode())
                .isEqualTo("DUPLICATE_EVENT");
    }

    @Test
    @DisplayName("getEventContract should publish identifierStrategy and traceabilityIds, "
            + "leaving version/fields/examples unchanged")
    void testGetEventContract_PublishesIdentifierStrategyAndTraceabilityIds() {
        EventEnvelopeContract contract = service.getEventContract();

        assertThat(contract.getIdentifierStrategy()).isNotNull();
        assertThat(contract.getIdentifierStrategy().getIdFormat()).isEqualTo("UUIDv7");
        assertThat(contract.getIdentifierStrategy().getEventIdMintedBy()).isEqualTo("SERVER_UNLESS_SUPPLIED");
        assertThat(contract.getIdentifierStrategy().getDomainKeyIdFormat()).isEqualTo("OPAQUE_STRING");

        assertThat(contract.getTraceabilityIds())
                .extracting(TraceabilityIdDescriptor::getName)
                .containsExactlyInAnyOrder(
                        "traceparent",
                        "X-Correlation-Id",
                        "eventId",
                        "eventReference",
                        "ingestionId",
                        "journalEntryId",
                        "domainKeyId",
                        "invoiceId");

        assertThat(contract.getVersion()).isEqualTo("1.0");
        assertThat(contract.getFields()).isNotEmpty();
        assertThat(contract.getExamples()).isEmpty();
    }
}

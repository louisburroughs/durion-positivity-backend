package com.positivity.accounting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.positivity.accounting.internal.dto.PostingResult;
import com.positivity.accounting.internal.entity.AccountingEvent;
import com.positivity.accounting.internal.entity.JournalEntry;
import com.positivity.accounting.internal.entity.JournalEntryLine;
import com.positivity.accounting.internal.entity.ReprocessingAttemptHistory;
import com.positivity.accounting.internal.enums.AccountingEventStatus;
import com.positivity.accounting.internal.enums.JournalEntryStatus;
import com.positivity.accounting.internal.enums.PostingFailureReason;
import com.positivity.accounting.internal.enums.ReprocessingOutcome;
import com.positivity.accounting.internal.exception.AccountingPeriodClosedException;
import com.positivity.accounting.internal.exception.AccountingPeriodHardLockedException;
import com.positivity.accounting.internal.repository.AccountingEventRepository;
import com.positivity.accounting.internal.repository.ReprocessingAttemptHistoryRepository;
import com.positivity.accounting.internal.service.AccountingPeriodGate;
import com.positivity.accounting.internal.service.IdempotencyServiceImpl;
import com.positivity.accounting.internal.service.JournalEntryServiceImpl;
import com.positivity.accounting.internal.service.PostingEngineOrchestrator;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for PostingEngineOrchestrator
 *
 * Tests posting engine orchestration including:
 * - Idempotency checks
 * - Rule evaluation
 * - Journal entry creation and posting
 * - Status transitions
 * - Error handling
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PostingEngineOrchestrator Unit Tests")
class PostingEngineOrchestratorTest {
    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC);

    @Mock
    private PostingRuleEvaluator postingRuleEvaluator;

    @Mock
    private JournalEntryServiceImpl journalEntryService;

    @Mock
    private IdempotencyServiceImpl idempotencyService;

    @Mock
    private AccountingEventRepository accountingEventRepository;

    @Mock
    private ReprocessingAttemptHistoryRepository reprocessingAttemptHistoryRepository;

    @Mock
    private AccountingPeriodGate accountingPeriodGate;

    private ObjectMapper objectMapper;
    private PostingEngineOrchestrator orchestrator;

    @Captor
    private ArgumentCaptor<AccountingEvent> eventCaptor;

    @Captor
    private ArgumentCaptor<ReprocessingAttemptHistory> attemptHistoryCaptor;

    private UUID testOrganizationId;
    private UUID testEventId;
    private UUID testMappingVersion;
    private UUID testJournalEntryId;
    private String testUserId;
    private AccountingEvent testEvent;
    private JournalEntry testJournalEntry;
    private Map<String, Object> testPayload;

    @BeforeEach
    void setUp() {
        // Create orchestrator with all dependencies
        objectMapper = new ObjectMapper();
        orchestrator = new PostingEngineOrchestrator(
                TEST_CLOCK,
                postingRuleEvaluator,
                journalEntryService,
                idempotencyService,
                accountingEventRepository,
                reprocessingAttemptHistoryRepository,
                objectMapper,
                accountingPeriodGate);

        // B2 period gate defaults to "open" so pre-B2 scenarios are
        // unaffected; PeriodGate tests override this stub explicitly.
        lenient().when(accountingPeriodGate.isPostingBlocked(any())).thenReturn(false);
        lenient().when(accountingPeriodGate.isHardLocked(any())).thenReturn(false);

        testOrganizationId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        testEventId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        testMappingVersion = UUID.fromString("00000000-0000-0000-0000-000000000001");
        testJournalEntryId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        testUserId = "test-user-123";

        // Setup test payload
        testPayload = new HashMap<>();
        testPayload.put("amount", "1000.00");
        testPayload.put("invoiceId", "INV-001");

        // Setup test event
        testEvent = new AccountingEvent();
        testEvent.setEventId(testEventId);
        testEvent.setOrganizationId(testOrganizationId);
        testEvent.setEventType("INVOICE_RECEIVED");
        testEvent.setTransactionDate(LocalDateTime.now(TEST_CLOCK));
        testEvent.setPayload(testPayload);
        testEvent.setStatus(AccountingEventStatus.RECEIVED);
        testEvent.setReceivedAt(Instant.now(TEST_CLOCK));
        testEvent.setSourceSystem("TEST_SYSTEM");

        // Setup test journal entry
        testJournalEntry = new JournalEntry();
        testJournalEntry.setJournalEntryId(testJournalEntryId);
        testJournalEntry.setTransactionDate(LocalDateTime.now(TEST_CLOCK));
        testJournalEntry.setDescription("Test journal entry");
        testJournalEntry.setStatus(JournalEntryStatus.DRAFT);
        testJournalEntry.setLines(createBalancedLines());
    }

    private List<JournalEntryLine> createBalancedLines() {
        List<JournalEntryLine> lines = new ArrayList<>();

        JournalEntryLine debitLine = new JournalEntryLine();
        debitLine.setGlAccountId(UUID.fromString("00000000-0000-0000-0000-000000000001"));
        debitLine.setAccountCode("1000");
        debitLine.setAccountName("Cash");
        debitLine.setDebitAmount(new BigDecimal("1000.00"));
        debitLine.setCreditAmount(BigDecimal.ZERO);
        lines.add(debitLine);

        JournalEntryLine creditLine = new JournalEntryLine();
        creditLine.setGlAccountId(UUID.fromString("00000000-0000-0000-0000-000000000001"));
        creditLine.setAccountCode("2000");
        creditLine.setAccountName("Accounts Payable");
        creditLine.setDebitAmount(BigDecimal.ZERO);
        creditLine.setCreditAmount(new BigDecimal("1000.00"));
        lines.add(creditLine);

        return lines;
    }

    @Nested
    @DisplayName("Idempotency Tests")
    class IdempotencyTests {

        @Test
        @DisplayName("Should return existing result when event already processed with posting reference")
        void shouldReturnExistingResultWhenEventAlreadyProcessed() {
            // Given
            String existingPostingRef = "existing-journal-entry-id";
            testEvent.setFinalPostingReferenceId(existingPostingRef);

            when(idempotencyService.isKeyProcessed(anyString())).thenReturn(true);

            // When
            PostingResult result = orchestrator.processEvent(testEvent, testMappingVersion, testUserId, true);

            // Then
            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getEvaluationDetails())
                    .containsEntry("postingReference", existingPostingRef)
                    .containsEntry("idempotent", true);

            verify(postingRuleEvaluator, never()).evaluateEvent(any(), any());
            verify(journalEntryService, never()).createJournalEntry(any());
        }

        @Test
        @DisplayName("Should proceed with processing when idempotency key not found")
        void shouldProceedWhenIdempotencyKeyNotFound() {
            // Given
            when(idempotencyService.isKeyProcessed(anyString())).thenReturn(false);

            PostingResult evaluationResult = PostingResult.success(testJournalEntry, testMappingVersion);
            when(postingRuleEvaluator.evaluateEvent(testEvent, testMappingVersion))
                    .thenReturn(evaluationResult);

            JournalEntry createdEntry = new JournalEntry();
            createdEntry.setJournalEntryId(testJournalEntryId);
            when(journalEntryService.createJournalEntry(any())).thenReturn(createdEntry);

            // When
            PostingResult result = orchestrator.processEvent(testEvent, testMappingVersion, testUserId, false);

            // Then
            assertThat(result.isSuccess()).isTrue();
            verify(postingRuleEvaluator).evaluateEvent(testEvent, testMappingVersion);
            verify(journalEntryService).createJournalEntry(testJournalEntry);
            verify(idempotencyService).registerKey(anyString(), eq(testEventId));
        }
    }

    @Nested
    @DisplayName("Evaluation Failure Tests")
    class EvaluationFailureTests {

        @Test
        @DisplayName("Should suspend event when evaluation fails")
        void shouldSuspendEventWhenEvaluationFails() {
            // Given
            when(idempotencyService.isKeyProcessed(anyString())).thenReturn(false);

            PostingResult failureResult = PostingResult.failure(
                    PostingFailureReason.UNMAPPED_EVENT_TYPE, "No mapping found for event type INVOICE_RECEIVED");
            when(postingRuleEvaluator.evaluateEvent(testEvent, testMappingVersion))
                    .thenReturn(failureResult);

            when(accountingEventRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
            when(reprocessingAttemptHistoryRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

            // When
            PostingResult result = orchestrator.processEvent(testEvent, testMappingVersion, testUserId, true);

            // Then
            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getFailureReason()).isEqualTo(PostingFailureReason.UNMAPPED_EVENT_TYPE);

            verify(accountingEventRepository).save(eventCaptor.capture());
            AccountingEvent savedEvent = eventCaptor.getValue();
            assertThat(savedEvent.getStatus()).isEqualTo(AccountingEventStatus.SUSPENDED);
            assertThat(savedEvent.getFailureReasonCode()).isEqualTo("UNMAPPED_EVENT_TYPE");
            assertThat(savedEvent.getFailureDetails()).contains("No mapping found");

            verify(reprocessingAttemptHistoryRepository).save(attemptHistoryCaptor.capture());
            ReprocessingAttemptHistory savedHistory = attemptHistoryCaptor.getValue();
            assertThat(savedHistory.getOutcome()).isEqualTo(ReprocessingOutcome.FAILURE);
            assertThat(savedHistory.getOutcomeDetails()).contains("Evaluation failed");

            verify(journalEntryService, never()).createJournalEntry(any());
            verify(idempotencyService, never()).registerKey(anyString(), any());
        }

        @Test
        @DisplayName("Should include mapping version in attempt history when evaluation fails")
        void shouldIncludeMappingVersionInAttemptHistory() {
            // Given
            when(idempotencyService.isKeyProcessed(anyString())).thenReturn(false);

            PostingResult failureResult =
                    PostingResult.failure(PostingFailureReason.VALIDATION_ERROR, "Required field missing");
            when(postingRuleEvaluator.evaluateEvent(testEvent, testMappingVersion))
                    .thenReturn(failureResult);

            when(accountingEventRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
            when(reprocessingAttemptHistoryRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

            // When
            orchestrator.processEvent(testEvent, testMappingVersion, testUserId, true);

            // Then
            verify(reprocessingAttemptHistoryRepository).save(attemptHistoryCaptor.capture());
            ReprocessingAttemptHistory savedHistory = attemptHistoryCaptor.getValue();
            assertThat(savedHistory.getMappingVersionUsed()).isEqualTo(testMappingVersion.toString());
            assertThat(savedHistory.getTriggeredByUserId()).isEqualTo(testUserId);
        }
    }

    @Nested
    @DisplayName("Auto-Post Success Tests")
    class AutoPostSuccessTests {

        @Test
        @DisplayName("Should create and post journal entry when autoPost is true")
        void shouldCreateAndPostJournalEntryWhenAutoPostTrue() {
            // Given
            when(idempotencyService.isKeyProcessed(anyString())).thenReturn(false);

            PostingResult evaluationResult = PostingResult.success(testJournalEntry, testMappingVersion);
            when(postingRuleEvaluator.evaluateEvent(testEvent, testMappingVersion))
                    .thenReturn(evaluationResult);

            JournalEntry createdEntry = new JournalEntry();
            createdEntry.setJournalEntryId(testJournalEntryId);
            createdEntry.setStatus(JournalEntryStatus.DRAFT);
            when(journalEntryService.createJournalEntry(testJournalEntry)).thenReturn(createdEntry);

            JournalEntry postedEntry = new JournalEntry();
            postedEntry.setJournalEntryId(testJournalEntryId);
            postedEntry.setStatus(JournalEntryStatus.POSTED);
            when(journalEntryService.postJournalEntry(testJournalEntryId)).thenReturn(postedEntry);

            when(accountingEventRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
            when(reprocessingAttemptHistoryRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

            // When
            PostingResult result = orchestrator.processEvent(testEvent, testMappingVersion, testUserId, true);

            // Then
            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getEvaluationDetails())
                    .containsEntry("postingReference", testJournalEntryId.toString())
                    .containsEntry("autoPosted", true);

            verify(journalEntryService).createJournalEntry(testJournalEntry);
            verify(journalEntryService).postJournalEntry(testJournalEntryId);

            verify(accountingEventRepository).save(eventCaptor.capture());
            AccountingEvent savedEvent = eventCaptor.getValue();
            assertThat(savedEvent.getStatus()).isEqualTo(AccountingEventStatus.PROCESSED);
            assertThat(savedEvent.getFinalPostingReferenceId()).isEqualTo(testJournalEntryId.toString());
            assertThat(savedEvent.getProcessedAt()).isNotNull();
            assertThat(savedEvent.getResolvedByUserId()).isEqualTo(testUserId);

            verify(reprocessingAttemptHistoryRepository).save(attemptHistoryCaptor.capture());
            ReprocessingAttemptHistory savedHistory = attemptHistoryCaptor.getValue();
            assertThat(savedHistory.getOutcome()).isEqualTo(ReprocessingOutcome.SUCCESS);
            assertThat(savedHistory.getOutcomeDetails()).contains("posted");

            verify(idempotencyService).registerKey(anyString(), eq(testEventId));
        }

        @Test
        @DisplayName("Should preserve evaluation details in result when auto-posting")
        void shouldPreserveEvaluationDetailsWhenAutoPosting() {
            // Given
            Map<String, Object> evaluationDetails = new HashMap<>();
            evaluationDetails.put("mappingKey", "INVOICE_RECEIVED");
            evaluationDetails.put("ruleVersion", "v1.0");

            when(idempotencyService.isKeyProcessed(anyString())).thenReturn(false);

            PostingResult evaluationResult = PostingResult.builder()
                    .success(true)
                    .journalEntryDraft(testJournalEntry)
                    .mappingVersionUsed(testMappingVersion)
                    .evaluationDetails(evaluationDetails)
                    .build();
            when(postingRuleEvaluator.evaluateEvent(testEvent, testMappingVersion))
                    .thenReturn(evaluationResult);

            JournalEntry createdEntry = new JournalEntry();
            createdEntry.setJournalEntryId(testJournalEntryId);
            when(journalEntryService.createJournalEntry(testJournalEntry)).thenReturn(createdEntry);

            JournalEntry postedEntry = new JournalEntry();
            postedEntry.setJournalEntryId(testJournalEntryId);
            when(journalEntryService.postJournalEntry(testJournalEntryId)).thenReturn(postedEntry);

            when(accountingEventRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
            when(reprocessingAttemptHistoryRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

            // When
            PostingResult result = orchestrator.processEvent(testEvent, testMappingVersion, testUserId, true);

            // Then
            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getEvaluationDetails())
                    .containsEntry("mappingKey", "INVOICE_RECEIVED")
                    .containsEntry("ruleVersion", "v1.0")
                    .containsEntry("postingReference", testJournalEntryId.toString())
                    .containsEntry("autoPosted", true);
        }
    }

    @Nested
    @DisplayName("Draft-Only Success Tests")
    class DraftOnlySuccessTests {

        @Test
        @DisplayName("Should create draft journal entry when autoPost is false")
        void shouldCreateDraftJournalEntryWhenAutoPostFalse() {
            // Given
            when(idempotencyService.isKeyProcessed(anyString())).thenReturn(false);

            PostingResult evaluationResult = PostingResult.success(testJournalEntry, testMappingVersion);
            when(postingRuleEvaluator.evaluateEvent(testEvent, testMappingVersion))
                    .thenReturn(evaluationResult);

            JournalEntry createdDraft = new JournalEntry();
            createdDraft.setJournalEntryId(testJournalEntryId);
            createdDraft.setStatus(JournalEntryStatus.DRAFT);
            when(journalEntryService.createJournalEntry(testJournalEntry)).thenReturn(createdDraft);

            when(accountingEventRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
            when(reprocessingAttemptHistoryRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

            // When
            PostingResult result = orchestrator.processEvent(testEvent, testMappingVersion, testUserId, false);

            // Then
            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getEvaluationDetails())
                    .containsEntry("postingReference", testJournalEntryId.toString())
                    .containsEntry("autoPosted", false);

            verify(journalEntryService).createJournalEntry(testJournalEntry);
            verify(journalEntryService, never()).postJournalEntry(any());

            verify(accountingEventRepository).save(eventCaptor.capture());
            AccountingEvent savedEvent = eventCaptor.getValue();
            assertThat(savedEvent.getStatus()).isEqualTo(AccountingEventStatus.PROCESSED);
            assertThat(savedEvent.getFinalPostingReferenceId()).isEqualTo(testJournalEntryId.toString());

            verify(reprocessingAttemptHistoryRepository).save(attemptHistoryCaptor.capture());
            ReprocessingAttemptHistory savedHistory = attemptHistoryCaptor.getValue();
            assertThat(savedHistory.getOutcome()).isEqualTo(ReprocessingOutcome.SUCCESS);
            assertThat(savedHistory.getOutcomeDetails()).contains("created draft");

            verify(idempotencyService).registerKey(anyString(), eq(testEventId));
        }
    }

    @Nested
    @DisplayName("Error Handling Tests")
    class ErrorHandlingTests {

        @Test
        @DisplayName("Should handle exception during rule evaluation and mark event as FAILED")
        void shouldHandleExceptionDuringEvaluation() {
            // Given
            when(idempotencyService.isKeyProcessed(anyString())).thenReturn(false);
            when(postingRuleEvaluator.evaluateEvent(testEvent, testMappingVersion))
                    .thenThrow(new RuntimeException("Database connection error"));

            when(accountingEventRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
            when(reprocessingAttemptHistoryRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

            // When
            PostingResult result = orchestrator.processEvent(testEvent, testMappingVersion, testUserId, true);

            // Then
            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getFailureReason()).isEqualTo(PostingFailureReason.INTERNAL_ERROR);
            assertThat(result.getFailureDetails()).contains("Database connection error");

            verify(accountingEventRepository).save(eventCaptor.capture());
            AccountingEvent savedEvent = eventCaptor.getValue();
            assertThat(savedEvent.getStatus()).isEqualTo(AccountingEventStatus.FAILED);
            assertThat(savedEvent.getFailureDetails()).contains("Posting engine error");
            assertThat(savedEvent.getErrorMessage()).contains("Database connection error");

            verify(reprocessingAttemptHistoryRepository).save(attemptHistoryCaptor.capture());
            ReprocessingAttemptHistory savedHistory = attemptHistoryCaptor.getValue();
            assertThat(savedHistory.getOutcome()).isEqualTo(ReprocessingOutcome.FAILURE);
            assertThat(savedHistory.getOutcomeDetails()).contains("Exception during processing");

            verify(journalEntryService, never()).createJournalEntry(any());
            verify(idempotencyService, never()).registerKey(anyString(), any());
        }

        @Test
        @DisplayName("Should handle exception during journal entry creation")
        void shouldHandleExceptionDuringJournalEntryCreation() {
            // Given
            when(idempotencyService.isKeyProcessed(anyString())).thenReturn(false);

            PostingResult evaluationResult = PostingResult.success(testJournalEntry, testMappingVersion);
            when(postingRuleEvaluator.evaluateEvent(testEvent, testMappingVersion))
                    .thenReturn(evaluationResult);

            when(journalEntryService.createJournalEntry(testJournalEntry))
                    .thenThrow(new RuntimeException("Failed to save journal entry"));

            when(accountingEventRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
            when(reprocessingAttemptHistoryRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

            // When
            PostingResult result = orchestrator.processEvent(testEvent, testMappingVersion, testUserId, false);

            // Then
            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getFailureReason()).isEqualTo(PostingFailureReason.INTERNAL_ERROR);

            verify(accountingEventRepository).save(eventCaptor.capture());
            AccountingEvent savedEvent = eventCaptor.getValue();
            assertThat(savedEvent.getStatus()).isEqualTo(AccountingEventStatus.FAILED);

            verify(reprocessingAttemptHistoryRepository).save(attemptHistoryCaptor.capture());
            ReprocessingAttemptHistory savedHistory = attemptHistoryCaptor.getValue();
            assertThat(savedHistory.getOutcome()).isEqualTo(ReprocessingOutcome.FAILURE);

            verify(idempotencyService, never()).registerKey(anyString(), any());
        }

        @Test
        @DisplayName("Should throw IllegalStateException when evaluation succeeds but no journal entry draft present")
        void shouldThrowWhenNoJournalEntryDraft() {
            // Given
            when(idempotencyService.isKeyProcessed(anyString())).thenReturn(false);

            // Create success result without journal entry draft
            PostingResult evaluationResult = PostingResult.builder()
                    .success(true)
                    .journalEntryDraft(null) // Missing draft!
                    .mappingVersionUsed(testMappingVersion)
                    .build();
            when(postingRuleEvaluator.evaluateEvent(testEvent, testMappingVersion))
                    .thenReturn(evaluationResult);

            when(accountingEventRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
            when(reprocessingAttemptHistoryRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

            // When
            PostingResult result = orchestrator.processEvent(testEvent, testMappingVersion, testUserId, false);

            // Then - should handle the IllegalStateException
            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getFailureReason()).isEqualTo(PostingFailureReason.INTERNAL_ERROR);

            verify(accountingEventRepository).save(eventCaptor.capture());
            AccountingEvent savedEvent = eventCaptor.getValue();
            assertThat(savedEvent.getStatus()).isEqualTo(AccountingEventStatus.FAILED);

            verify(reprocessingAttemptHistoryRepository).save(attemptHistoryCaptor.capture());
            ReprocessingAttemptHistory savedHistory = attemptHistoryCaptor.getValue();
            assertThat(savedHistory.getOutcome()).isEqualTo(ReprocessingOutcome.FAILURE);
        }
    }

    @Nested
    @DisplayName("Null Mapping Version Tests")
    class NullMappingVersionTests {

        @Test
        @DisplayName("Should handle null mapping version and use AUTO in idempotency key")
        void shouldHandleNullMappingVersion() {
            // Given
            when(idempotencyService.isKeyProcessed(anyString())).thenReturn(false);

            PostingResult evaluationResult = PostingResult.success(testJournalEntry, null);
            when(postingRuleEvaluator.evaluateEvent(testEvent, null)).thenReturn(evaluationResult);

            JournalEntry createdEntry = new JournalEntry();
            createdEntry.setJournalEntryId(testJournalEntryId);
            when(journalEntryService.createJournalEntry(testJournalEntry)).thenReturn(createdEntry);

            when(accountingEventRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
            when(reprocessingAttemptHistoryRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

            // When
            PostingResult result = orchestrator.processEvent(testEvent, null, testUserId, false);

            // Then
            assertThat(result.isSuccess()).isTrue();

            verify(reprocessingAttemptHistoryRepository).save(attemptHistoryCaptor.capture());
            ReprocessingAttemptHistory savedHistory = attemptHistoryCaptor.getValue();
            assertThat(savedHistory.getMappingVersionUsed()).isNull();

            verify(idempotencyService).registerKey(anyString(), eq(testEventId));
        }
    }

    @Nested
    @DisplayName("Attempt History Tests")
    class AttemptHistoryTests {

        @Test
        @DisplayName("Should create attempt history with correct fields for successful processing")
        void shouldCreateAttemptHistoryForSuccess() {
            // Given
            when(idempotencyService.isKeyProcessed(anyString())).thenReturn(false);

            PostingResult evaluationResult = PostingResult.success(testJournalEntry, testMappingVersion);
            when(postingRuleEvaluator.evaluateEvent(testEvent, testMappingVersion))
                    .thenReturn(evaluationResult);

            JournalEntry createdEntry = new JournalEntry();
            createdEntry.setJournalEntryId(testJournalEntryId);
            when(journalEntryService.createJournalEntry(testJournalEntry)).thenReturn(createdEntry);

            when(accountingEventRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
            when(reprocessingAttemptHistoryRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

            // When
            orchestrator.processEvent(testEvent, testMappingVersion, testUserId, false);

            // Then
            verify(reprocessingAttemptHistoryRepository, times(1)).save(attemptHistoryCaptor.capture());
            ReprocessingAttemptHistory savedHistory = attemptHistoryCaptor.getValue();

            assertThat(savedHistory.getAccountingEvent()).isEqualTo(testEvent);
            assertThat(savedHistory.getTriggeredByUserId()).isEqualTo(testUserId);
            assertThat(savedHistory.getMappingVersionUsed()).isEqualTo(testMappingVersion.toString());
            assertThat(savedHistory.getOutcome()).isEqualTo(ReprocessingOutcome.SUCCESS);
            assertThat(savedHistory.getOutcomeDetails()).contains("created draft");
        }

        @Test
        @DisplayName("Should save attempt history exactly once for success case")
        void shouldSaveAttemptHistoryOnceForSuccess() {
            // Given
            when(idempotencyService.isKeyProcessed(anyString())).thenReturn(false);

            PostingResult evaluationResult = PostingResult.success(testJournalEntry, testMappingVersion);
            when(postingRuleEvaluator.evaluateEvent(testEvent, testMappingVersion))
                    .thenReturn(evaluationResult);

            JournalEntry createdEntry = new JournalEntry();
            createdEntry.setJournalEntryId(testJournalEntryId);
            when(journalEntryService.createJournalEntry(testJournalEntry)).thenReturn(createdEntry);

            when(accountingEventRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
            when(reprocessingAttemptHistoryRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

            // When
            orchestrator.processEvent(testEvent, testMappingVersion, testUserId, false);

            // Then - verify both repository saves happened exactly once
            verify(accountingEventRepository, times(1)).save(any(AccountingEvent.class));
            verify(reprocessingAttemptHistoryRepository, times(1)).save(any(ReprocessingAttemptHistory.class));
        }
    }

    @Nested
    @DisplayName("Transaction Flow Integration Tests")
    class TransactionFlowTests {

        @Test
        @DisplayName("Should complete full transaction flow for auto-post scenario")
        void shouldCompleteFullFlowForAutoPost() {
            // Given
            when(idempotencyService.isKeyProcessed(anyString())).thenReturn(false);

            PostingResult evaluationResult = PostingResult.success(testJournalEntry, testMappingVersion);
            when(postingRuleEvaluator.evaluateEvent(testEvent, testMappingVersion))
                    .thenReturn(evaluationResult);

            JournalEntry createdEntry = new JournalEntry();
            createdEntry.setJournalEntryId(testJournalEntryId);
            when(journalEntryService.createJournalEntry(testJournalEntry)).thenReturn(createdEntry);

            JournalEntry postedEntry = new JournalEntry();
            postedEntry.setJournalEntryId(testJournalEntryId);
            when(journalEntryService.postJournalEntry(testJournalEntryId)).thenReturn(postedEntry);

            when(accountingEventRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
            when(reprocessingAttemptHistoryRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

            // When
            PostingResult result = orchestrator.processEvent(testEvent, testMappingVersion, testUserId, true);

            // Then - verify the complete flow
            // 1. Idempotency check
            verify(idempotencyService).isKeyProcessed(anyString());

            // 2. Rule evaluation
            verify(postingRuleEvaluator).evaluateEvent(testEvent, testMappingVersion);

            // 3. Journal entry creation and posting
            verify(journalEntryService).createJournalEntry(testJournalEntry);
            verify(journalEntryService).postJournalEntry(testJournalEntryId);

            // 4. Event status update
            verify(accountingEventRepository).save(eventCaptor.capture());
            assertThat(eventCaptor.getValue().getStatus()).isEqualTo(AccountingEventStatus.PROCESSED);

            // 5. Attempt history creation
            verify(reprocessingAttemptHistoryRepository).save(attemptHistoryCaptor.capture());
            assertThat(attemptHistoryCaptor.getValue().getOutcome()).isEqualTo(ReprocessingOutcome.SUCCESS);

            // 6. Idempotency registration
            verify(idempotencyService).registerKey(anyString(), eq(testEventId));

            // 7. Result validation
            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getJournalEntryDraft()).isEqualTo(testJournalEntry);
            assertThat(result.getMappingVersionUsed()).isEqualTo(testMappingVersion);
        }
    }

    @Nested
    @DisplayName("Period Gate Pre-Check Tests (B2)")
    class PeriodGatePreCheckTests {

        @Test
        @DisplayName("autoPost into a closed period suspends the event with PERIOD_CLOSED before evaluation")
        void autoPostClosedPeriod_suspendsWithPeriodClosed() {
            // Given
            when(idempotencyService.isKeyProcessed(anyString())).thenReturn(false);
            when(accountingPeriodGate.isPostingBlocked(
                            testEvent.getTransactionDate().toLocalDate()))
                    .thenReturn(true);
            when(accountingEventRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
            when(reprocessingAttemptHistoryRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

            // When
            PostingResult result = orchestrator.processEvent(testEvent, testMappingVersion, testUserId, true);

            // Then
            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getFailureReason()).isEqualTo(PostingFailureReason.PERIOD_CLOSED);

            verify(accountingEventRepository).save(eventCaptor.capture());
            AccountingEvent savedEvent = eventCaptor.getValue();
            assertThat(savedEvent.getStatus()).isEqualTo(AccountingEventStatus.SUSPENDED);
            assertThat(savedEvent.getFailureReasonCode()).isEqualTo("PERIOD_CLOSED");
            assertThat(savedEvent.getFailureDetails())
                    .contains("falls in CLOSED accounting period")
                    .contains("reprocess after the period is reopened")
                    .doesNotContain("hard-lock");

            verify(reprocessingAttemptHistoryRepository).save(attemptHistoryCaptor.capture());
            assertThat(attemptHistoryCaptor.getValue().getOutcome()).isEqualTo(ReprocessingOutcome.FAILURE);

            // No rule evaluation, no journal entry, no idempotency key burned
            verify(postingRuleEvaluator, never()).evaluateEvent(any(), any());
            verify(journalEntryService, never()).createJournalEntry(any());
            verify(idempotencyService, never()).registerKey(anyString(), any());
        }

        @Test
        @DisplayName(
                "autoPost before the hard-lock date suspends with the permanent-block message, not the reopen remedy")
        void autoPostHardLocked_suspendsWithPermanentBlockMessage() {
            // Given - the gate blocks because of the org hard lock, not a CLOSED period
            when(idempotencyService.isKeyProcessed(anyString())).thenReturn(false);
            when(accountingPeriodGate.isPostingBlocked(
                            testEvent.getTransactionDate().toLocalDate()))
                    .thenReturn(true);
            when(accountingPeriodGate.isHardLocked(
                            testEvent.getTransactionDate().toLocalDate()))
                    .thenReturn(true);
            when(accountingEventRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
            when(reprocessingAttemptHistoryRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

            // When
            PostingResult result = orchestrator.processEvent(testEvent, testMappingVersion, testUserId, true);

            // Then - same SUSPENDED / PERIOD_CLOSED labeling, but the details must
            // say the block is permanent instead of pointing at a reopen remedy
            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getFailureReason()).isEqualTo(PostingFailureReason.PERIOD_CLOSED);

            verify(accountingEventRepository).save(eventCaptor.capture());
            AccountingEvent savedEvent = eventCaptor.getValue();
            assertThat(savedEvent.getStatus()).isEqualTo(AccountingEventStatus.SUSPENDED);
            assertThat(savedEvent.getFailureReasonCode()).isEqualTo("PERIOD_CLOSED");
            assertThat(savedEvent.getFailureDetails())
                    .contains("is before the organization hard-lock date")
                    .contains("posting is permanently blocked and cannot be reprocessed"
                            + " (the hard lock is never reopened)")
                    .doesNotContain("reprocess after the period is reopened");

            // No rule evaluation, no journal entry, no idempotency key burned
            verify(postingRuleEvaluator, never()).evaluateEvent(any(), any());
            verify(journalEntryService, never()).createJournalEntry(any());
            verify(idempotencyService, never()).registerKey(anyString(), any());
        }

        @Test
        @DisplayName("draft-only processing (autoPost=false) never consults the period gate")
        void draftOnly_periodGateNotConsulted() {
            // Given
            when(idempotencyService.isKeyProcessed(anyString())).thenReturn(false);
            when(postingRuleEvaluator.evaluateEvent(testEvent, testMappingVersion))
                    .thenReturn(PostingResult.success(testJournalEntry, testMappingVersion));

            JournalEntry createdEntry = new JournalEntry();
            createdEntry.setJournalEntryId(testJournalEntryId);
            when(journalEntryService.createJournalEntry(any())).thenReturn(createdEntry);

            // When
            PostingResult result = orchestrator.processEvent(testEvent, testMappingVersion, testUserId, false);

            // Then - drafts may be created into any period (gate at post time)
            assertThat(result.isSuccess()).isTrue();
            verify(accountingPeriodGate, never()).isPostingBlocked(any());
            verify(journalEntryService).createJournalEntry(testJournalEntry);
            verify(journalEntryService, never()).postJournalEntry(any());
        }

        @Test
        @DisplayName("suspended PERIOD_CLOSED event reprocesses cleanly once the period is open again")
        void suspendedEvent_reprocessesCleanlyAfterReopen() {
            // Given - first pass: closed period suspends the event
            when(idempotencyService.isKeyProcessed(anyString())).thenReturn(false);
            when(accountingPeriodGate.isPostingBlocked(
                            testEvent.getTransactionDate().toLocalDate()))
                    .thenReturn(true);
            when(accountingEventRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
            when(reprocessingAttemptHistoryRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

            PostingResult suspended = orchestrator.processEvent(testEvent, testMappingVersion, testUserId, true);
            assertThat(suspended.isSuccess()).isFalse();
            assertThat(testEvent.getStatus()).isEqualTo(AccountingEventStatus.SUSPENDED);
            assertThat(testEvent.getFailureReasonCode()).isEqualTo("PERIOD_CLOSED");

            // Given - period reopened: gate no longer blocks, evaluation succeeds
            when(accountingPeriodGate.isPostingBlocked(
                            testEvent.getTransactionDate().toLocalDate()))
                    .thenReturn(false);
            when(postingRuleEvaluator.evaluateEvent(testEvent, testMappingVersion))
                    .thenReturn(PostingResult.success(testJournalEntry, testMappingVersion));

            JournalEntry createdEntry = new JournalEntry();
            createdEntry.setJournalEntryId(testJournalEntryId);
            when(journalEntryService.createJournalEntry(any())).thenReturn(createdEntry);

            JournalEntry postedEntry = new JournalEntry();
            postedEntry.setJournalEntryId(testJournalEntryId);
            postedEntry.setStatus(JournalEntryStatus.POSTED);
            when(journalEntryService.postJournalEntry(testJournalEntryId)).thenReturn(postedEntry);

            // When - reprocess (same event, autoPost like the reprocess flow)
            PostingResult reprocessed = orchestrator.processEvent(testEvent, testMappingVersion, testUserId, true);

            // Then - clean success: PROCESSED with stale failure metadata cleared
            assertThat(reprocessed.isSuccess()).isTrue();
            assertThat(testEvent.getStatus()).isEqualTo(AccountingEventStatus.PROCESSED);
            assertThat(testEvent.getFailureReasonCode()).isNull();
            assertThat(testEvent.getFailureDetails()).isNull();
            assertThat(testEvent.getErrorMessage()).isNull();
            assertThat(testEvent.getFinalPostingReferenceId()).isEqualTo(testJournalEntryId.toString());
            verify(idempotencyService).registerKey(anyString(), eq(testEventId));
        }

        @Test
        @DisplayName("mid-flight period close (gate throws during posting) suspends with PERIOD_CLOSED, not FAILED")
        void midFlightPeriodClose_suspendsWithPeriodClosed() {
            // Given - the pre-check passes (period still open) but the period
            // closes before the gated postJournalEntry call, which throws.
            when(idempotencyService.isKeyProcessed(anyString())).thenReturn(false);
            when(postingRuleEvaluator.evaluateEvent(testEvent, testMappingVersion))
                    .thenReturn(PostingResult.success(testJournalEntry, testMappingVersion));

            JournalEntry createdEntry = new JournalEntry();
            createdEntry.setJournalEntryId(testJournalEntryId);
            when(journalEntryService.createJournalEntry(any())).thenReturn(createdEntry);
            when(journalEntryService.postJournalEntry(testJournalEntryId))
                    .thenThrow(new AccountingPeriodClosedException(
                            "2024-01", "Transaction date falls in CLOSED accounting period 2024-01"));

            when(accountingEventRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
            when(reprocessingAttemptHistoryRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

            // When
            PostingResult result = orchestrator.processEvent(testEvent, testMappingVersion, testUserId, true);

            // Then - labeled like the pre-check path, not INTERNAL_ERROR/FAILED
            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getFailureReason()).isEqualTo(PostingFailureReason.PERIOD_CLOSED);
            assertThat(result.getFailureDetails()).contains("reprocess after the period is reopened");

            verify(accountingEventRepository).save(eventCaptor.capture());
            AccountingEvent savedEvent = eventCaptor.getValue();
            assertThat(savedEvent.getStatus()).isEqualTo(AccountingEventStatus.SUSPENDED);
            assertThat(savedEvent.getFailureReasonCode()).isEqualTo("PERIOD_CLOSED");
            assertThat(savedEvent.getFailureDetails()).contains("2024-01");

            verify(reprocessingAttemptHistoryRepository).save(attemptHistoryCaptor.capture());
            assertThat(attemptHistoryCaptor.getValue().getOutcome()).isEqualTo(ReprocessingOutcome.FAILURE);

            verify(idempotencyService, never()).registerKey(anyString(), any());
        }

        @Test
        @DisplayName("mid-flight hard-lock advance (gate throws during posting) suspends with PERIOD_CLOSED")
        void midFlightHardLock_suspendsWithPeriodClosed() {
            // Given
            when(idempotencyService.isKeyProcessed(anyString())).thenReturn(false);
            when(postingRuleEvaluator.evaluateEvent(testEvent, testMappingVersion))
                    .thenReturn(PostingResult.success(testJournalEntry, testMappingVersion));

            JournalEntry createdEntry = new JournalEntry();
            createdEntry.setJournalEntryId(testJournalEntryId);
            when(journalEntryService.createJournalEntry(any())).thenReturn(createdEntry);
            when(journalEntryService.postJournalEntry(testJournalEntryId))
                    .thenThrow(new AccountingPeriodHardLockedException(
                            LocalDate.of(2024, 2, 1), "Transaction date is before the hard-lock date 2024-02-01"));

            when(accountingEventRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
            when(reprocessingAttemptHistoryRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

            // When
            PostingResult result = orchestrator.processEvent(testEvent, testMappingVersion, testUserId, true);

            // Then - permanent-block wording, not the reopen-then-reprocess remedy
            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getFailureReason()).isEqualTo(PostingFailureReason.PERIOD_CLOSED);
            assertThat(result.getFailureDetails())
                    .contains("Posting blocked by the period gate:")
                    .contains("Transaction date is before the hard-lock date 2024-02-01")
                    .contains("posting is permanently blocked and cannot be reprocessed"
                            + " (the hard lock is never reopened)")
                    .doesNotContain("reprocess after the period is reopened");

            verify(accountingEventRepository).save(eventCaptor.capture());
            AccountingEvent savedEvent = eventCaptor.getValue();
            assertThat(savedEvent.getStatus()).isEqualTo(AccountingEventStatus.SUSPENDED);
            assertThat(savedEvent.getFailureReasonCode()).isEqualTo("PERIOD_CLOSED");
            assertThat(savedEvent.getFailureDetails())
                    .contains("posting is permanently blocked and cannot be reprocessed"
                            + " (the hard lock is never reopened)");
        }
    }
}

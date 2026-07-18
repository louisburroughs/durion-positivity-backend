package com.positivity.accounting.internal.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.positivity.accounting.internal.dto.PostingResult;
import com.positivity.accounting.internal.entity.AccountingEvent;
import com.positivity.accounting.internal.entity.JournalEntry;
import com.positivity.accounting.internal.entity.ReprocessingAttemptHistory;
import com.positivity.accounting.internal.enums.AccountingEventStatus;
import com.positivity.accounting.internal.enums.PostingFailureReason;
import com.positivity.accounting.internal.enums.ReprocessingOutcome;
import com.positivity.accounting.internal.exception.AccountingPeriodClosedException;
import com.positivity.accounting.internal.exception.AccountingPeriodHardLockedException;
import com.positivity.accounting.internal.repository.AccountingEventRepository;
import com.positivity.accounting.internal.repository.ReprocessingAttemptHistoryRepository;
import com.positivity.accounting.service.IdempotencyService;
import com.positivity.accounting.service.JournalEntryService;
import com.positivity.accounting.service.PostingRuleEvaluator;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestrates the posting rule engine flow including evaluation, idempotency,
 * journal entry creation/posting, and event status updates.
 *
 * Key Responsibilities:
 * - Idempotency checks before posting
 * - Coordinate posting rule evaluation
 * - Handle autoPost vs. draft creation
 * - Update AccountingEvent with results
 * - Create ReprocessingAttemptHistory records
 * - Emit metrics
 *
 * Transaction Boundaries:
 * - Each invocation runs in a transaction
 * - Optimistic locking on AccountingEvent prevents duplicate postings
 * - Idempotency checks provide additional deduplication
 *
 * @see PostingRuleEvaluator
 * @see JournalEntryServiceImpl
 * @see IdempotencyServiceImpl
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class PostingEngineOrchestrator {

    private final Clock clock;
    private final PostingRuleEvaluator postingRuleEvaluator;
    private final JournalEntryService journalEntryService;
    private final IdempotencyService idempotencyService;
    private final AccountingEventRepository accountingEventRepository;
    private final ReprocessingAttemptHistoryRepository reprocessingAttemptHistoryRepository;
    private final ObjectMapper objectMapper;
    private final AccountingPeriodGate accountingPeriodGate;

    /**
     * Processes an accounting event through the posting engine.
     * Handles evaluation, idempotency, posting, and status updates.
     *
     * Flow:
     * 1. Create attempt history record (default to FAILURE)
     * 2. Check idempotency key
     * 3. Period gate pre-check for autoPost (B2): closed/hard-locked
     * transaction date suspends the event with PERIOD_CLOSED
     * 4. Evaluate posting rules
     * 5. On success: create/post journal entry
     * 6. Update event status and attempt history
     * 7. Return result
     *
     * @param event               the accounting event to process
     * @param mappingVersionToUse optional specific mapping version
     * @param triggeredByUserId   user ID for audit trail
     * @param autoPost            whether to auto-post or create draft only
     * @return posting result with outcome
     */
    @NonNull
    public PostingResult processEvent(
            @NonNull AccountingEvent event,
            @Nullable UUID mappingVersionToUse,
            @NonNull String triggeredByUserId,
            boolean autoPost) {

        log.info("Processing event {} with autoPost={}", event.getEventId(), autoPost);

        // Create attempt history record (default to FAILURE, update on success)
        ReprocessingAttemptHistory attemptHistory = new ReprocessingAttemptHistory();
        attemptHistory.setAccountingEvent(event);
        attemptHistory.setTriggeredByUserId(triggeredByUserId);
        attemptHistory.setMappingVersionUsed(mappingVersionToUse != null ? mappingVersionToUse.toString() : null);
        attemptHistory.setOutcome(ReprocessingOutcome.FAILURE);

        try {
            // 1. Idempotency check
            String idempotencyKey = computePostingIdempotencyKey(event, mappingVersionToUse);
            if (idempotencyService.isKeyProcessed(idempotencyKey)) {
                log.info("Event {} already processed (idempotency key: {})", event.getEventId(), idempotencyKey);
                String existingRef = event.getFinalPostingReferenceId();
                if (existingRef != null) {
                    // Return success with existing reference info
                    return PostingResult.builder()
                            .success(true)
                            .mappingVersionUsed(mappingVersionToUse)
                            .evaluationDetails(Map.of("postingReference", existingRef, "idempotent", true))
                            .build();
                }
            }

            // 2. Period gate pre-check (story B2, issue #944): an autoPost
            // event whose transaction date falls in a CLOSED (or hard-locked)
            // period is routed to SUSPENDED with failureReasonCode
            // PERIOD_CLOSED instead of failing mid-posting. The engine has no
            // interactive caller, so no override applies here — the remedy is
            // reopening the period and reprocessing (manual reprocess;
            // the scheduled auto-retry loop skips PERIOD_CLOSED suspensions).
            // Draft-only processing (autoPost=false) is unaffected: drafts
            // may be created into any period and gate at posting time.
            if (autoPost) {
                LocalDate transactionDate = event.getTransactionDate().toLocalDate();
                if (accountingPeriodGate.isPostingBlocked(transactionDate)) {
                    String periodCode = YearMonth.from(transactionDate).toString();
                    String details = "Transaction date " + transactionDate
                            + " falls in CLOSED or hard-locked accounting period " + periodCode
                            + "; event suspended — reprocess after the period is reopened";
                    log.warn("Suspending event {}: {}", event.getEventId(), details);

                    event.setStatus(AccountingEventStatus.SUSPENDED);
                    event.setFailureReasonCode(PostingFailureReason.PERIOD_CLOSED.name());
                    event.setFailureDetails(details);

                    attemptHistory.setOutcome(ReprocessingOutcome.FAILURE);
                    attemptHistory.setOutcomeDetails(details);

                    accountingEventRepository.save(event);
                    reprocessingAttemptHistoryRepository.save(attemptHistory);

                    return PostingResult.failure(PostingFailureReason.PERIOD_CLOSED, details);
                }
            }

            // 3. Evaluate posting rules
            log.debug("Evaluating posting rules for event {}", event.getEventId());
            PostingResult evaluationResult = postingRuleEvaluator.evaluateEvent(event, mappingVersionToUse);

            if (!evaluationResult.isSuccess()) {
                // Evaluation failed - update event status to SUSPENDED
                log.warn(
                        "Posting rule evaluation failed for event {}: {} - {}",
                        event.getEventId(),
                        evaluationResult.getFailureReason(),
                        evaluationResult.getFailureDetails());

                event.setStatus(AccountingEventStatus.SUSPENDED);
                event.setFailureReasonCode(evaluationResult.getFailureReason().name());
                event.setFailureDetails(evaluationResult.getFailureDetails());

                attemptHistory.setOutcome(ReprocessingOutcome.FAILURE);
                attemptHistory.setOutcomeDetails(String.format(
                        "Evaluation failed: %s - %s",
                        evaluationResult.getFailureReason(), evaluationResult.getFailureDetails()));

                accountingEventRepository.save(event);
                reprocessingAttemptHistoryRepository.save(attemptHistory);

                return evaluationResult;
            }

            // 4. Evaluation succeeded - create/post journal entry
            JournalEntry journalEntry = evaluationResult.getJournalEntryDraft();
            if (journalEntry == null) {
                throw new IllegalStateException("Evaluation succeeded but no journal entry draft present");
            }

            String postingReference;
            if (autoPost) {
                // Auto-post: create and immediately post the journal entry
                log.info("Auto-posting journal entry for event {}", event.getEventId());
                JournalEntry createdEntry = journalEntryService.createJournalEntry(journalEntry);
                JournalEntry postedEntry = journalEntryService.postJournalEntry(createdEntry.getJournalEntryId());
                postingReference = postedEntry.getJournalEntryId().toString();
            } else {
                // Draft only: create journal entry in DRAFT status
                log.info("Creating draft journal entry for event {}", event.getEventId());
                JournalEntry draftEntry = journalEntryService.createJournalEntry(journalEntry);
                postingReference = draftEntry.getJournalEntryId().toString();
            }

            // 5. Update event status to PROCESSED, clearing failure metadata
            // left over from an earlier suspension (e.g. PERIOD_CLOSED before
            // a reopen-then-reprocess) so the record reads clean.
            event.setStatus(AccountingEventStatus.PROCESSED);
            event.setFinalPostingReferenceId(postingReference);
            event.setProcessedAt(java.time.Instant.now(clock));
            event.setResolvedByUserId(triggeredByUserId);
            event.setFailureReasonCode(null);
            event.setFailureDetails(null);
            event.setErrorMessage(null);

            attemptHistory.setOutcome(ReprocessingOutcome.SUCCESS);
            attemptHistory.setOutcomeDetails(String.format(
                    "Successfully %s journal entry with reference: %s",
                    autoPost ? "posted" : "created draft", postingReference));

            // Register idempotency key
            idempotencyService.registerKey(idempotencyKey, event.getEventId());

            accountingEventRepository.save(event);
            reprocessingAttemptHistoryRepository.save(attemptHistory);

            log.info("Successfully processed event {} with posting reference {}", event.getEventId(), postingReference);

            // Build evaluation details with posting reference
            Map<String, Object> detailsWithRef = new HashMap<>(evaluationResult.getEvaluationDetails());
            detailsWithRef.put("postingReference", postingReference);
            detailsWithRef.put("autoPosted", autoPost);

            return PostingResult.builder()
                    .success(true)
                    .journalEntryDraft(journalEntry)
                    .mappingVersionUsed(evaluationResult.getMappingVersionUsed())
                    .evaluationDetails(detailsWithRef)
                    .build();

        } catch (AccountingPeriodClosedException | AccountingPeriodHardLockedException e) {
            // A period closed (or the hard lock advanced) mid-flight, between
            // the step-2 pre-check and the gated posting itself. Label it the
            // same way as the pre-check path — SUSPENDED / PERIOD_CLOSED —
            // so the first pass reads correctly and the remedy
            // (reopen-then-reprocess) is evident, instead of a misleading
            // FAILED / INTERNAL_ERROR.
            String details = "Posting blocked by the period gate: " + e.getMessage()
                    + "; event suspended — reprocess after the period is reopened";
            log.warn("Suspending event {}: {}", event.getEventId(), details);

            event.setStatus(AccountingEventStatus.SUSPENDED);
            event.setFailureReasonCode(PostingFailureReason.PERIOD_CLOSED.name());
            event.setFailureDetails(details);

            attemptHistory.setOutcome(ReprocessingOutcome.FAILURE);
            attemptHistory.setOutcomeDetails(details);

            accountingEventRepository.save(event);
            reprocessingAttemptHistoryRepository.save(attemptHistory);

            return PostingResult.failure(PostingFailureReason.PERIOD_CLOSED, details);
        } catch (Exception e) {
            log.error("Error processing event {} through posting engine", event.getEventId(), e);

            event.setStatus(AccountingEventStatus.FAILED);
            event.setFailureDetails("Posting engine error: " + e.getMessage());
            event.setErrorMessage(e.getMessage());

            attemptHistory.setOutcome(ReprocessingOutcome.FAILURE);
            attemptHistory.setOutcomeDetails("Exception during processing: " + e.getMessage());

            accountingEventRepository.save(event);
            reprocessingAttemptHistoryRepository.save(attemptHistory);

            return PostingResult.failure(PostingFailureReason.INTERNAL_ERROR, "Internal error: " + e.getMessage());
        }
    }

    /**
     * Computes idempotency key for posting operations.
     * Key format: SHA-256(eventPayload + mappingVersion + orgId + sourceSystem)
     *
     * Uses JSON serialization with sorted keys for deterministic payload
     * representation.
     *
     * @param event               the accounting event
     * @param mappingVersionToUse the mapping version (null = "AUTO")
     * @return idempotency key (hex string)
     */
    @NonNull
    private String computePostingIdempotencyKey(@NonNull AccountingEvent event, @Nullable UUID mappingVersionToUse) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");

            // Include event payload - use JSON serialization with sorted keys for
            // consistency
            String payloadStr = "";
            if (event.getPayload() != null) {
                try {
                    // Create ObjectMapper with sorted keys for deterministic output
                    ObjectMapper sortedMapper = objectMapper.copy();
                    sortedMapper.configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
                    payloadStr = sortedMapper.writeValueAsString(event.getPayload());
                } catch (JsonProcessingException e) {
                    log.warn("Failed to serialize payload for event {}, using empty string", event.getEventId(), e);
                    payloadStr = "";
                }
            }
            digest.update(payloadStr.getBytes(StandardCharsets.UTF_8));

            // Include mapping version (or "AUTO" if not specified)
            String versionStr = mappingVersionToUse != null ? mappingVersionToUse.toString() : "AUTO";
            digest.update(versionStr.getBytes(StandardCharsets.UTF_8));

            // Include org ID
            digest.update(event.getOrganizationId().toString().getBytes(StandardCharsets.UTF_8));

            // Include source system
            digest.update(event.getSourceSystem().getBytes(StandardCharsets.UTF_8));

            byte[] hash = digest.digest();
            return HexFormat.of().formatHex(hash);

        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not available", e);
        }
    }
}

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
import java.util.Optional;
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
            Optional<PostingResult> idempotentResult = checkIdempotency(event, idempotencyKey, mappingVersionToUse);
            if (idempotentResult.isPresent()) {
                return idempotentResult.get();
            }

            // 2. Period gate pre-check
            Optional<PostingResult> periodBlocked = checkPeriodGate(event, attemptHistory, autoPost);
            if (periodBlocked.isPresent()) {
                return periodBlocked.get();
            }

            // 3. Evaluate posting rules
            log.debug("Evaluating posting rules for event {}", event.getEventId());
            PostingResult evaluationResult = postingRuleEvaluator.evaluateEvent(event, mappingVersionToUse);
            if (!evaluationResult.isSuccess()) {
                return handleEvaluationFailure(event, attemptHistory, evaluationResult);
            }

            // 4. Evaluation succeeded - create/post journal entry
            JournalEntry journalEntry = evaluationResult.getJournalEntryDraft();
            if (journalEntry == null) {
                throw new IllegalStateException("Evaluation succeeded but no journal entry draft present");
            }
            String postingReference = createOrPostJournalEntry(journalEntry, autoPost, event.getEventId());

            // 5. Update event status, persist, and build the success result
            return recordSuccessfulProcessing(
                    event,
                    attemptHistory,
                    evaluationResult,
                    postingReference,
                    idempotencyKey,
                    triggeredByUserId,
                    autoPost);

        } catch (AccountingPeriodClosedException e) {
            // The period closed mid-flight, between the step-2 pre-check and
            // the gated posting itself. Label it the same way as the
            // pre-check path — SUSPENDED / PERIOD_CLOSED — so the first pass
            // reads correctly and the remedy (reopen-then-reprocess) is
            // evident, instead of a misleading FAILED / INTERNAL_ERROR.
            String details = "Posting blocked by the period gate: " + e.getMessage()
                    + "; event suspended — reprocess after the period is reopened";
            return suspendPeriodBlocked(event, attemptHistory, details);
        } catch (AccountingPeriodHardLockedException e) {
            // The hard lock advanced mid-flight. Same SUSPENDED /
            // PERIOD_CLOSED labeling as above, but the hard lock is
            // monotonic-forward and never reopened, so there is no
            // reopen-then-reprocess remedy — say so instead of pointing at
            // one.
            String details = "Posting blocked by the period gate: " + e.getMessage()
                    + "; event suspended — posting is permanently blocked and cannot be"
                    + " reprocessed (the hard lock is never reopened)";
            return suspendPeriodBlocked(event, attemptHistory, details);
        } catch (Exception e) {
            return handleUnexpectedFailure(event, attemptHistory, e);
        }
    }

    /**
     * Idempotency short-circuit (step 1): when the key was already processed AND a final posting
     * reference was persisted, returns that prior success result untouched. A key marked processed
     * but missing its reference — an earlier attempt that was interrupted before it saved — is
     * treated as unseen and falls through to a fresh evaluation, rather than returning a broken
     * success with a null reference.
     */
    @NonNull
    private Optional<PostingResult> checkIdempotency(
            @NonNull AccountingEvent event, @NonNull String idempotencyKey, @Nullable UUID mappingVersionToUse) {
        if (!idempotencyService.isKeyProcessed(idempotencyKey)) {
            return Optional.empty();
        }
        log.info("Event {} already processed (idempotency key: {})", event.getEventId(), idempotencyKey);
        String existingRef = event.getFinalPostingReferenceId();
        if (existingRef == null) {
            return Optional.empty();
        }
        return Optional.of(PostingResult.builder()
                .success(true)
                .mappingVersionUsed(mappingVersionToUse)
                .evaluationDetails(Map.of("postingReference", existingRef, "idempotent", true))
                .build());
    }

    /**
     * Period gate pre-check (step 2, story B2, issue #944): an autoPost event whose transaction
     * date falls in a CLOSED (or hard-locked) period is routed to SUSPENDED with failureReasonCode
     * PERIOD_CLOSED instead of failing mid-posting. The engine has no interactive caller, so no
     * override applies here — the remedy is reopening the period and reprocessing (manual
     * reprocess; the scheduled auto-retry loop skips PERIOD_CLOSED suspensions). Draft-only
     * processing (autoPost=false) is unaffected: drafts may be created into any period and gate at
     * posting time.
     */
    @NonNull
    private Optional<PostingResult> checkPeriodGate(
            @NonNull AccountingEvent event, @NonNull ReprocessingAttemptHistory attemptHistory, boolean autoPost) {
        if (!autoPost) {
            return Optional.empty();
        }
        LocalDate transactionDate = event.getTransactionDate().toLocalDate();
        if (!accountingPeriodGate.isPostingBlocked(transactionDate)) {
            return Optional.empty();
        }
        return Optional.of(suspendPeriodBlocked(event, attemptHistory, periodBlockedDetails(transactionDate)));
    }

    /**
     * Distinguishes the two period-gate block causes: a hard lock is monotonic-forward and never
     * reopened (no remedy), while a CLOSED period can be reopened and the event reprocessed.
     */
    @NonNull
    private String periodBlockedDetails(@NonNull LocalDate transactionDate) {
        if (accountingPeriodGate.isHardLocked(transactionDate)) {
            return "Transaction date " + transactionDate
                    + " is before the organization hard-lock date"
                    + "; event suspended — posting is permanently blocked and cannot be"
                    + " reprocessed (the hard lock is never reopened)";
        }
        String periodCode = YearMonth.from(transactionDate).toString();
        return "Transaction date " + transactionDate
                + " falls in CLOSED accounting period " + periodCode
                + "; event suspended — reprocess after the period is reopened";
    }

    /**
     * Evaluation failure handling (step 3): the posting rules rejected the event (e.g. an
     * unbalanced journal or an unresolvable mapping) — suspend it with the evaluator's own failure
     * reason rather than a generic error, so the reprocessing UI shows what actually needs fixing.
     */
    @NonNull
    private PostingResult handleEvaluationFailure(
            @NonNull AccountingEvent event,
            @NonNull ReprocessingAttemptHistory attemptHistory,
            @NonNull PostingResult evaluationResult) {
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

    /**
     * Creates the journal entry from the evaluated draft (step 4) and, for autoPost, posts it
     * immediately; otherwise leaves it in DRAFT. Returns the resulting entry's id as the posting
     * reference.
     */
    @NonNull
    private String createOrPostJournalEntry(
            @NonNull JournalEntry journalEntry, boolean autoPost, @NonNull UUID eventId) {
        if (autoPost) {
            log.info("Auto-posting journal entry for event {}", eventId);
            JournalEntry createdEntry = journalEntryService.createJournalEntry(journalEntry);
            JournalEntry postedEntry = journalEntryService.postJournalEntry(createdEntry.getJournalEntryId());
            return postedEntry.getJournalEntryId().toString();
        }
        log.info("Creating draft journal entry for event {}", eventId);
        JournalEntry draftEntry = journalEntryService.createJournalEntry(journalEntry);
        return draftEntry.getJournalEntryId().toString();
    }

    /**
     * Finalizes a successful posting (step 5): marks the event PROCESSED, clearing any failure
     * metadata left over from an earlier suspension (e.g. PERIOD_CLOSED before a
     * reopen-then-reprocess) so the record reads clean, persists the attempt history, burns the
     * idempotency key, and builds the caller-facing result.
     */
    @NonNull
    private PostingResult recordSuccessfulProcessing(
            @NonNull AccountingEvent event,
            @NonNull ReprocessingAttemptHistory attemptHistory,
            @NonNull PostingResult evaluationResult,
            @NonNull String postingReference,
            @NonNull String idempotencyKey,
            @NonNull String triggeredByUserId,
            boolean autoPost) {
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

        idempotencyService.registerKey(idempotencyKey, event.getEventId());

        accountingEventRepository.save(event);
        reprocessingAttemptHistoryRepository.save(attemptHistory);

        log.info("Successfully processed event {} with posting reference {}", event.getEventId(), postingReference);

        Map<String, Object> detailsWithRef = new HashMap<>(evaluationResult.getEvaluationDetails());
        detailsWithRef.put("postingReference", postingReference);
        detailsWithRef.put("autoPosted", autoPost);

        return PostingResult.builder()
                .success(true)
                .journalEntryDraft(evaluationResult.getJournalEntryDraft())
                .mappingVersionUsed(evaluationResult.getMappingVersionUsed())
                .evaluationDetails(detailsWithRef)
                .build();
    }

    /**
     * Unexpected-exception path: mid-flow errors other than the two period-gate exceptions mark the
     * event FAILED rather than SUSPENDED, since — unlike a rejected rule or a closed period —
     * there is no known remedy to point the operator at.
     */
    @NonNull
    private PostingResult handleUnexpectedFailure(
            @NonNull AccountingEvent event, @NonNull ReprocessingAttemptHistory attemptHistory, @NonNull Exception e) {
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

    /**
     * Suspends an event blocked by the accounting-period gate (pre-check or
     * mid-flight): persists SUSPENDED / PERIOD_CLOSED with the given failure
     * details, records the failed attempt, and returns the failure result.
     * {@code details} carries the cause-specific remedy wording (CLOSED
     * period: reopen-then-reprocess; hard lock: permanently blocked).
     */
    @NonNull
    private PostingResult suspendPeriodBlocked(
            @NonNull AccountingEvent event,
            @NonNull ReprocessingAttemptHistory attemptHistory,
            @NonNull String details) {
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

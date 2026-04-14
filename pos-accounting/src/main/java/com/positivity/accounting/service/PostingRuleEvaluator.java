package com.positivity.accounting.service;

import com.positivity.accounting.internal.dto.PostingResult;
import com.positivity.accounting.internal.entity.AccountingEvent;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Core service for evaluating posting rules against accounting events.
 * Produces deterministic mapping from domain events to journal entries.
 *
 * Evaluation Process:
 * 1. Load applicable PostingRuleVersion for org + transaction date
 * 2. Evaluate mapping keys in order: exact → fallback → category default
 * 3. Generate balanced JournalEntry draft from mapping
 * 4. Return PostingResult with either success (journal entry) or failure
 * (reason)
 *
 * Business Rules:
 * - Only PUBLISHED rule versions are evaluated
 * - Rule versions must be effective on the transaction date
 * - Mapping selection is deterministic and repeatable
 * - All generated journal entries must be balanced (debits = credits)
 * - Evaluation is idempotent for identical input + mapping version
 *
 * @see PostingResult
 * @see com.positivity.accounting.internal.entity.PostingRuleVersion
 */
public interface PostingRuleEvaluator {

    /**
     * Evaluates posting rules for an accounting event and produces a posting
     * result.
     *
     * Loads the active or specified PostingRuleVersion, evaluates mapping keys
     * against the event payload, and generates a balanced JournalEntry draft.
     *
     * On success, returns PostingResult with:
     * - journalEntryDraft: balanced journal entry ready for posting/draft creation
     * - mappingVersionUsed: UUID of the rule version that was evaluated
     * - evaluationDetails: metadata about mapping keys evaluated
     *
     * On failure, returns PostingResult with:
     * - failureReason: enumerated reason (NO_RULE_VERSION, UNMAPPED_EVENT_TYPE,
     * etc.)
     * - failureDetails: human-readable description
     * - evaluationDetails: what was attempted (keys evaluated, fallbacks tried)
     *
     * @param event               the accounting event to evaluate (must have org,
     *                            date, event type)
     * @param mappingVersionToUse optional specific mapping version UUID (null = use
     *                            active)
     * @return posting result with either journal entry or failure reason
     * @throws IllegalArgumentException if event is null or missing required fields
     */
    @NonNull
    PostingResult evaluateEvent(@NonNull AccountingEvent event, @Nullable UUID mappingVersionToUse);

    /**
     * Evaluates posting rules using the active rule version for the event's org and
     * date.
     * Convenience method that delegates to evaluateEvent(event, null).
     *
     * @param event the accounting event to evaluate
     * @return posting result
     */
    @NonNull
    default PostingResult evaluateEvent(@NonNull AccountingEvent event) {
        return evaluateEvent(event, null);
    }
}

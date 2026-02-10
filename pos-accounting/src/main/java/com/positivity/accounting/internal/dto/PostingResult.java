package com.positivity.accounting.internal.dto;

import com.positivity.accounting.internal.entity.JournalEntry;
import com.positivity.accounting.internal.enums.PostingFailureReason;
import lombok.Builder;
import lombok.Value;
import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Result of evaluating a posting rule against an accounting event.
 * Contains either a successful journal entry draft or failure details.
 * 
 * Immutable value object following result pattern for posting engine
 * operations.
 */
@Value
@Builder
public class PostingResult {
    /** Whether the posting rule evaluation succeeded */
    boolean success;

    /** Generated journal entry draft (present when success=true) */
    @Nullable
    JournalEntry journalEntryDraft;

    /** Reason for failure (present when success=false) */
    @Nullable
    PostingFailureReason failureReason;

    /** Detailed failure message (present when success=false) */
    @Nullable
    String failureDetails;

    /** Mapping version UUID that was used for evaluation */
    @Nullable
    UUID mappingVersionUsed;

    /**
     * Additional evaluation metadata (mapping keys evaluated, fallbacks tried,
     * etc.)
     */
    @Nullable
    @Builder.Default
    Map<String, Object> evaluationDetails = new HashMap<>();

    /**
     * Creates a successful result with a journal entry draft.
     * 
     * @param journalEntry   the generated journal entry (must be balanced)
     * @param mappingVersion the mapping version UUID that was used
     * @return success result
     */
    public static PostingResult success(JournalEntry journalEntry, UUID mappingVersion) {
        return PostingResult.builder()
                .success(true)
                .journalEntryDraft(journalEntry)
                .mappingVersionUsed(mappingVersion)
                .build();
    }

    /**
     * Creates a successful result with evaluation details.
     * 
     * @param journalEntry      the generated journal entry
     * @param mappingVersion    the mapping version used
     * @param evaluationDetails metadata about the evaluation process
     * @return success result with metadata
     */
    public static PostingResult success(JournalEntry journalEntry, UUID mappingVersion,
            Map<String, Object> evaluationDetails) {
        return PostingResult.builder()
                .success(true)
                .journalEntryDraft(journalEntry)
                .mappingVersionUsed(mappingVersion)
                .evaluationDetails(evaluationDetails)
                .build();
    }

    /**
     * Creates a failure result with reason and details.
     * 
     * @param reason  the enumerated failure reason
     * @param details human-readable failure description
     * @return failure result
     */
    public static PostingResult failure(PostingFailureReason reason, String details) {
        return PostingResult.builder()
                .success(false)
                .failureReason(reason)
                .failureDetails(details)
                .build();
    }

    /**
     * Creates a failure result with evaluation metadata.
     * 
     * @param reason            the failure reason
     * @param details           failure description
     * @param evaluationDetails metadata about what was attempted
     * @return failure result with metadata
     */
    public static PostingResult failure(PostingFailureReason reason, String details,
            Map<String, Object> evaluationDetails) {
        return PostingResult.builder()
                .success(false)
                .failureReason(reason)
                .failureDetails(details)
                .evaluationDetails(evaluationDetails)
                .build();
    }
}

package com.positivity.accounting.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.accounting.internal.entity.JournalEntry;
import com.positivity.accounting.internal.enums.PostingFailureReason;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import lombok.Builder;
import lombok.Value;
import org.jspecify.annotations.Nullable;

/**
 * Result of evaluating a posting rule against an accounting event.
 * Contains either a successful journal entry draft or failure details.
 *
 * Immutable value object following result pattern for posting engine
 * operations.
 */
@Value
@Builder
@Schema(description = "Result of evaluating a posting rule against an accounting event")
public class PostingResult {
    /** Whether the posting rule evaluation succeeded */
    @Schema(description = "Whether the posting rule evaluation succeeded", example = "true", requiredMode = REQUIRED)
    boolean success;

    /** Generated journal entry draft (present when success=true) */
    @Schema(
            description = "Generated journal entry draft, present when evaluation succeeded",
            requiredMode = NOT_REQUIRED)
    @Nullable
    JournalEntry journalEntryDraft;

    /** Reason for failure (present when success=false) */
    @Schema(
            description = "Reason for failure, present when evaluation failed",
            example = "UNMAPPED_EVENT_TYPE",
            requiredMode = NOT_REQUIRED)
    @Nullable
    PostingFailureReason failureReason;

    /** Detailed failure message (present when success=false) */
    @Schema(
            description = "Detailed failure message, present when evaluation failed",
            example = "Event type does not match any rule mapping",
            requiredMode = NOT_REQUIRED)
    @Nullable
    String failureDetails;

    /** Mapping version UUID that was used for evaluation */
    @Schema(
            description = "Mapping version identifier used for evaluation",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = NOT_REQUIRED)
    @Nullable
    UUID mappingVersionUsed;

    /**
     * Additional evaluation metadata (mapping keys evaluated, fallbacks tried,
     * etc.)
     */
    @Schema(
            description = "Additional evaluation metadata such as mapping keys evaluated and fallbacks tried",
            requiredMode = NOT_REQUIRED)
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
    public static PostingResult success(
            JournalEntry journalEntry, UUID mappingVersion, Map<String, Object> evaluationDetails) {
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
    public static PostingResult failure(
            PostingFailureReason reason, String details, Map<String, Object> evaluationDetails) {
        return PostingResult.builder()
                .success(false)
                .failureReason(reason)
                .failureDetails(details)
                .evaluationDetails(evaluationDetails)
                .build();
    }
}

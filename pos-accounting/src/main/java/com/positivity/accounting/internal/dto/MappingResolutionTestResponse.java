package com.positivity.accounting.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import lombok.Builder;
import lombok.Value;
import org.jspecify.annotations.Nullable;

/**
 * Result of a dry-run posting rule / mapping resolution (story E3, issue
 * #957).
 *
 * Mirrors exactly what the posting rule evaluator would produce for the
 * supplied hypothetical event — matched rule identity, mapping details,
 * resolved journal entry lines (including E1 proportional split shares), and
 * per-predicate evaluation outcomes (E2 grammar) — without persisting
 * anything.
 *
 * A no-match outcome is a normal 200 response with {@code matched=false} and a
 * populated {@code noMatchReason}/{@code noMatchDetail}; it is not an error.
 */
@Value
@Builder
@Schema(
        description = "Dry-run mapping/rule resolution result. A no-match outcome is represented as "
                + "matched=false with a reason, not as an error. Nothing is persisted.")
public class MappingResolutionTestResponse {

    /** Whether a published posting rule matched the supplied event. */
    @Schema(
            description = "Whether a published posting rule matched the supplied event. "
                    + "false means no rule matched; inspect noMatchReason/noMatchDetail.",
            example = "true",
            requiredMode = REQUIRED)
    boolean matched;

    /** Identity of the rule that matched (present when matched=true). */
    @Nullable
    @Schema(
            description = "Identity of the posting rule that matched, present when matched=true",
            requiredMode = NOT_REQUIRED)
    MatchedRule matchedRule;

    /** Mapping evaluation details (present when matched=true). */
    @Nullable
    @Schema(description = "Mapping evaluation details, present when matched=true", requiredMode = NOT_REQUIRED)
    MatchedMapping matchedMapping;

    /**
     * Journal entry lines the evaluator would post, including E1
     * split-line shares (present when matched=true).
     */
    @Nullable
    @Schema(
            description = "Journal entry lines the evaluator would post for this event, including "
                    + "E1 proportional split-line shares and residual distribution. Present when matched=true.",
            requiredMode = NOT_REQUIRED)
    List<ResolvedLine> resolvedLines;

    /** Per-predicate evaluation outcomes (E2 grammar). */
    @Nullable
    @Schema(
            description = "Per-predicate evaluation outcomes against the sample payload (E2 condition grammar). "
                    + "Populated for every candidate rule condition that was evaluated.",
            requiredMode = NOT_REQUIRED)
    List<PredicateEvaluation> predicateEvaluations;

    /** Machine-readable no-match reason (present when matched=false). */
    @Nullable
    @Schema(
            description = "Machine-readable no-match reason, present when matched=false",
            example = "UNMAPPED_EVENT_TYPE",
            requiredMode = NOT_REQUIRED)
    String noMatchReason;

    /** Human-readable no-match detail (present when matched=false). */
    @Nullable
    @Schema(
            description = "Human-readable no-match detail, present when matched=false",
            example = "No published rule version maps event type INVOICE_FINALIZED on 2026-01-15",
            requiredMode = NOT_REQUIRED)
    String noMatchDetail;

    /** Identity of the posting rule that matched the dry-run event. */
    @Value
    @Builder
    @Schema(description = "Identity of the posting rule that matched the dry-run event")
    public static class MatchedRule {

        @Schema(
                description = "Posting rule set identifier",
                example = "01960003-0000-7000-8000-000000000001",
                requiredMode = REQUIRED)
        UUID postingRuleSetId;

        @Schema(
                description = "Posting rule set name",
                example = "Invoice finalization postings",
                requiredMode = REQUIRED)
        String ruleSetName;

        @Schema(
                description = "Posting rule version identifier that was evaluated",
                example = "01960003-0000-7000-8000-000000000002",
                requiredMode = REQUIRED)
        UUID ruleVersionId;

        @Schema(description = "Posting rule version number that was evaluated", example = "3", requiredMode = REQUIRED)
        Integer versionNumber;
    }

    /** Mapping evaluation details for the matched rule. */
    @Value
    @Builder
    @Schema(description = "Mapping evaluation details for the matched rule")
    public static class MatchedMapping {

        @Schema(
                description = "Type of mapping that was matched (exact, fallback, category_default)",
                example = "exact",
                requiredMode = NOT_REQUIRED)
        @Nullable
        String mappingType;

        @Schema(
                description = "Mapping key that was matched",
                example = "INVENTORY:SKU-1234",
                requiredMode = NOT_REQUIRED)
        @Nullable
        String mappingKey;

        @Schema(
                description = "All mapping keys evaluated during the search, in evaluation order",
                requiredMode = NOT_REQUIRED)
        @Nullable
        List<String> keysEvaluated;
    }

    /** One journal entry line the evaluator would post. */
    @Value
    @Builder
    @Schema(description = "One journal entry line the dry-run evaluator would post")
    public static class ResolvedLine {

        @Schema(
                description = "Resolved GL account identifier",
                example = "01960003-0000-7000-8000-000000000003",
                requiredMode = REQUIRED)
        UUID glAccountId;

        @Schema(description = "Resolved GL account code", example = "4000", requiredMode = NOT_REQUIRED)
        @Nullable
        String accountCode;

        @Schema(description = "Resolved GL account name", example = "Sales Revenue", requiredMode = NOT_REQUIRED)
        @Nullable
        String accountName;

        @Schema(description = "Debit amount for this line", example = "75.00", requiredMode = REQUIRED)
        BigDecimal debitAmount;

        @Schema(description = "Credit amount for this line", example = "0.00", requiredMode = REQUIRED)
        BigDecimal creditAmount;

        @Schema(
                description = "Line description",
                example = "Recognize revenue (60% share)",
                requiredMode = NOT_REQUIRED)
        @Nullable
        String description;

        @Schema(
                description = "E1 split group this line belongs to; null for non-split lines",
                example = "revenue-split",
                requiredMode = NOT_REQUIRED)
        @Nullable
        String splitGroup;

        @Schema(
                description = "E1 split factor percentage applied to this line; null for non-split lines",
                example = "60.0",
                requiredMode = NOT_REQUIRED)
        @Nullable
        BigDecimal factorPercent;
    }

    /** Evaluation outcome of one rule condition predicate (E2 grammar). */
    @Value
    @Builder
    @Schema(description = "Evaluation outcome of one rule condition predicate (E2 grammar)")
    public static class PredicateEvaluation {

        @Schema(
                description = "Predicate expression as configured on the rule condition",
                example = "payload.channel == 'POS' && payload.totalAmount > 100",
                requiredMode = REQUIRED)
        String predicate;

        @Schema(
                description = "Whether the predicate matched the supplied event type and sample payload",
                example = "true",
                requiredMode = REQUIRED)
        boolean matched;

        @Schema(
                description = "Optional evaluation detail (e.g. which clause failed)",
                example = "clause payload.totalAmount > 100 evaluated false (actual: 42.00)",
                requiredMode = NOT_REQUIRED)
        @Nullable
        String detail;
    }
}

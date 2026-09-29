package com.positivity.accounting.internal.bankrec.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Review of one or more {@code POSSIBLE_DUPLICATE} bank transactions (SPEC §4.5; story S2, #2301).
 * The single-row path takes the id from the URL and ignores {@link #ids}; the bulk path requires them.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Duplicate review: DISTINCT returns the row to UNMATCHED, DUPLICATE excludes it")
public class DuplicateReviewRequest {

    @Schema(description = "Rows to review (bulk path only)")
    private List<UUID> ids;

    @Schema(description = "The decision", example = "DISTINCT", requiredMode = REQUIRED)
    private DuplicateReviewDecision decision;

    @Schema(description = "For DUPLICATE: the original; defaults to the row the intake flagged it against")
    private UUID duplicateOfBankTransactionId;

    @Schema(
            description = "Why (at least 10 characters)",
            example = "Two separate monthly fees charged on the same day",
            requiredMode = REQUIRED)
    private String justification;

    @Schema(description = "The row version the caller read (single-row path); a stale one answers 409 OPTIMISTIC_LOCK")
    private Long version;
}

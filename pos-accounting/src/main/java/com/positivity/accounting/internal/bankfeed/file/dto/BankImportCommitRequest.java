package com.positivity.accounting.internal.bankfeed.file.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Commit of an import (SPEC §4.4, §6.1; story S3, #2302). {@code duplicateDecisions} answer {@code
 * POSSIBLE_DUPLICATE} rows in bulk; an unanswered one is committed as a {@code POSSIBLE_DUPLICATE} bank
 * transaction for later review. Starting a reconciliation from the commit arrives with story S4.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Commit of an import, with optional decisions on its possible duplicates")
public class BankImportCommitRequest {

    @Schema(description = "Decisions on POSSIBLE_DUPLICATE rows, by row number")
    private List<DuplicateDecision> duplicateDecisions;

    @Schema(description = "The import version the commit was made against", example = "3")
    private Long version;

    /** A decision on one {@code POSSIBLE_DUPLICATE} row. */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(name = "BankImportDuplicateDecision", description = "A decision on one POSSIBLE_DUPLICATE row")
    public static class DuplicateDecision {

        @Schema(description = "The row's number in the file", example = "12", requiredMode = REQUIRED)
        private Integer rowNumber;

        @Schema(
                description = "DISTINCT commits it as its own transaction; DUPLICATE skips it",
                allowableValues = {"DISTINCT", "DUPLICATE"},
                requiredMode = REQUIRED)
        private String decision;
    }
}

package com.positivity.accounting.internal.bankfeed.file.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One row change (SPEC §4.4, §3.8; story S3, #2302) — exactly one of: {@code correctedValues}; {@code
 * skip = true} with a {@code reason}; or, on a {@code POSSIBLE_DUPLICATE} row, {@code duplicateDecision}
 * ({@code DISTINCT} returns it to {@code PARSED}, {@code DUPLICATE} with a {@code reason} skips it).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "A correction, a skip or a duplicate decision for one import row")
public class BankImportRowUpdateRequest {

    @Schema(
            description = "Corrected values: any of date (ISO), signedAmount (positive = cash in), description,"
                    + " reference, checkNumber, sourceTransactionId; the raw values are kept",
            example = "{\"date\":\"2026-09-14\"}")
    private Map<String, Object> correctedValues;

    @Schema(description = "Skip the row; requires a reason")
    private Boolean skip;

    @Schema(
            description = "Why the row is skipped (at least 10 characters)",
            example = "Pending item already on the August statement")
    private String reason;

    @Schema(
            description = "On a POSSIBLE_DUPLICATE row: DISTINCT (keep it) or DUPLICATE (skip it, with a reason)",
            allowableValues = {"DISTINCT", "DUPLICATE"})
    private String duplicateDecision;

    @Schema(description = "The import version the change was made against", example = "3")
    private Long version;
}

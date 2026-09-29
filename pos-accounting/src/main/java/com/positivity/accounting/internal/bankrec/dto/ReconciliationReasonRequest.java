package com.positivity.accounting.internal.bankrec.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Return a submitted reconciliation to its preparer (SPEC §4.9; story S5, #2304). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Return a SUBMITTED reconciliation to the preparer, with a reason")
public class ReconciliationReasonRequest {

    @Size(max = 1000, message = "reason must not exceed 1000 characters")
    @Schema(
            description = "Why it goes back (at least 10 characters)",
            example = "Match the two card deposits of 2026-09-14 before I approve",
            requiredMode = REQUIRED)
    private String reason;

    @Schema(
            description = "The reconciliation version the caller read; a stale one answers 409 OPTIMISTIC_LOCK",
            example = "4")
    private Long version;
}

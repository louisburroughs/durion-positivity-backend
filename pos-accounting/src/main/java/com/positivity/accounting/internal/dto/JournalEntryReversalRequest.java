package com.positivity.accounting.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO for journal entry reversal request.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Request payload for reversing a journal entry")
public class JournalEntryReversalRequest {

    @NotBlank(message = "Reversal reason is required")
    @Schema(
            description = "Reason for reversing the journal entry",
            example = "Correcting duplicate posting",
            requiredMode = REQUIRED)
    private String reason;

    @Schema(
            description = "Optional transaction date for the reversal entry. When omitted or null, the date"
                    + " defaults to the original entry's transaction date if that date's accounting period is"
                    + " OPEN, otherwise to today's date. Whether explicit or defaulted, the resolved date must"
                    + " fall in an OPEN accounting period; a date in a CLOSED period is rejected with 422"
                    + " PERIOD_CLOSED.",
            example = "2026-07-15",
            requiredMode = NOT_REQUIRED)
    private LocalDate reversalDate;

    @Size(max = 500, message = "Override justification must not exceed 500 characters")
    @Schema(
            description = "Optional justification for reversing into a CLOSED accounting period (story B2)."
                    + " When the resolved reversal date falls in a CLOSED period, supplying a non-blank"
                    + " justification together with the accounting:period:override permission allows the"
                    + " reversal to post into that period (audit-logged); without it the reversal is rejected"
                    + " with 422 PERIOD_CLOSED. Has no effect for dates in OPEN periods and can never bypass"
                    + " the hard lock (422 PERIOD_HARD_LOCKED).",
            example = "Auditor-approved correction of June posting",
            requiredMode = NOT_REQUIRED)
    private String overrideJustification;
}

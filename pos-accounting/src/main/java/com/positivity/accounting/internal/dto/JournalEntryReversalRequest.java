package com.positivity.accounting.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
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
}

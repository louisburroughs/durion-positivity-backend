package com.positivity.accounting.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO for setting the org-level hard-lock date (story B2, issue #944).
 * The date is monotonic-forward-only and every change requires a
 * justification recorded in the audit trail.
 *
 * @see <a href=
 *      "domains/accounting/plan-odoo-parity-pos-accounting.md">Odoo Parity Plan -
 *      Story B2</a>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Request payload for setting the org-level hard-lock date")
public class HardLockDateUpdateRequest {

    @NotNull(message = "Hard-lock date is required")
    @Schema(
            description = "New hard-lock date. Must be on or after the currently stored hard-lock date"
                    + " (monotonic forward only — a backward move is rejected with 422"
                    + " HARD_LOCK_DATE_REGRESSION). Once set, journal entries dated strictly before this date"
                    + " are permanently rejected with no override path.",
            example = "2026-06-30",
            requiredMode = REQUIRED)
    private LocalDate hardLockDate;

    @NotBlank(message = "Hard-lock justification is required")
    @Size(max = 500, message = "Justification must not exceed 500 characters")
    @Schema(
            description = "Mandatory reason for moving the hard-lock date; recorded in the audit trail with"
                    + " the acting user",
            example = "FY2025 statutory filing complete; locking H1 2026",
            requiredMode = REQUIRED)
    private String justification;
}

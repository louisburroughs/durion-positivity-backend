package com.positivity.accounting.internal.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO exposing the org-level hard-lock date (story B2, issue #944).
 *
 * <p>Serialized with {@code Include.ALWAYS} (overriding the module-wide
 * {@code non_null} default) so an unset hard lock is returned as an explicit
 * {@code "hardLockDate": null} rather than an absent property — the frontend
 * distinguishes "no hard lock configured" from a partial payload.
 *
 * @see <a href=
 *      "domains/accounting/plan-odoo-parity-pos-accounting.md">Odoo Parity Plan -
 *      Story B2</a>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.ALWAYS)
@Schema(description = "Org-level hard-lock date payload")
public class HardLockDateResponse {

    @Schema(
            description = "The org-level hard-lock date. Journal entries dated strictly before this date are"
                    + " permanently rejected (422 PERIOD_HARD_LOCKED) with no override path. Null when no hard"
                    + " lock has been configured yet.",
            example = "2026-06-30",
            nullable = true)
    private LocalDate hardLockDate;
}

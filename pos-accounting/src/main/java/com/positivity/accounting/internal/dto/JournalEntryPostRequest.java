package com.positivity.accounting.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Optional request body for posting a journal entry (story B2, issue #944).
 *
 * <p>The post endpoint historically took no body; this DTO is bound with
 * {@code @RequestBody(required = false)} so body-less calls keep working —
 * a null body is equivalent to an empty request (no override).
 *
 * @see <a href=
 *      "domains/accounting/plan-odoo-parity-pos-accounting.md">Odoo Parity Plan -
 *      Story B2</a>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Optional request payload for posting a journal entry")
public class JournalEntryPostRequest {

    @Size(max = 500, message = "Override justification must not exceed 500 characters")
    @Schema(
            description = "Optional justification for posting into a CLOSED accounting period. When the"
                    + " entry's transaction date falls in a CLOSED period, supplying a non-blank justification"
                    + " together with the accounting:period:override permission allows the posting to proceed"
                    + " (audit-logged); without it the post is rejected with 422 PERIOD_CLOSED. Has no effect"
                    + " for dates in OPEN periods and can never bypass the hard lock (422"
                    + " PERIOD_HARD_LOCKED).",
            example = "Auditor-approved late accrual for June close",
            requiredMode = NOT_REQUIRED)
    private String overrideJustification;
}

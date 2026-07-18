package com.positivity.accounting.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO for reopening a CLOSED accounting period (Story B1, decision D-7):
 * reopening requires a mandatory justification recorded on the period and in
 * the audit trail.
 *
 * @see <a href=
 *      "domains/accounting/plan-odoo-parity-pos-accounting.md">Odoo Parity Plan -
 *      Story B1</a>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Request payload for reopening a closed accounting period")
public class AccountingPeriodReopenRequest {

    @NotBlank(message = "Reopen justification is required")
    @Size(max = 500, message = "Justification must not exceed 500 characters")
    @Schema(
            description = "Mandatory reason for reopening the period; recorded on the period and in the audit trail",
            example = "Late vendor bill for June must be posted before restatement",
            requiredMode = REQUIRED)
    private String justification;
}

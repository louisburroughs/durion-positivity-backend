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
 * Request payload for writing off an UNMATCHED settlement line below the
 * configured threshold (Story F1c, issue #963, decision D-14). The write-off
 * posts a reversible adjustment journal entry and requires a mandatory,
 * audited reason.
 *
 * @see <a href=
 *      "domains/accounting/plan-odoo-parity-pos-accounting.md">Odoo Parity Plan -
 *      Story F1c</a>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Request payload for writing off a small unmatched settlement line")
public class SettlementWriteOffRequest {

    @NotBlank(message = "Write-off reason is required")
    @Size(max = 500, message = "Reason must not exceed 500 characters")
    @Schema(
            description = "Mandatory reason for the write-off; recorded on the line and in the audit trail",
            example = "Unidentifiable micro-deposit below reconciliation threshold",
            requiredMode = REQUIRED)
    private String reason;
}

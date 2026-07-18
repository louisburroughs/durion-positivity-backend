package com.positivity.accounting.internal.dto;

import com.positivity.accounting.internal.enums.AccountingPeriodStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO for Accounting Period responses (list/close/reopen).
 *
 * @see <a href=
 *      "domains/accounting/plan-odoo-parity-pos-accounting.md">Odoo Parity Plan -
 *      Story B1</a>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Accounting period response payload")
public class AccountingPeriodResponse {

    @Schema(description = "Period UUID", example = "01936e5e-7890-7a3d-8b6e-4d5678901234")
    private UUID periodId;

    @Schema(description = "Period code (YYYY-MM)", example = "2026-07")
    private String periodCode;

    @Schema(description = "First day of the period", example = "2026-07-01")
    private LocalDate startDate;

    @Schema(description = "Last day of the period (inclusive)", example = "2026-07-31")
    private LocalDate endDate;

    @Schema(description = "Lifecycle status", example = "OPEN")
    private AccountingPeriodStatus status;

    @Schema(description = "When the period was closed (null while OPEN)")
    private Instant closedAt;

    @Schema(description = "Actor who closed the period")
    private String closedBy;

    @Schema(description = "When the period was last reopened")
    private Instant reopenedAt;

    @Schema(description = "Actor who last reopened the period")
    private String reopenedBy;

    @Schema(description = "Justification recorded for the last reopen")
    private String reopenJustification;
}

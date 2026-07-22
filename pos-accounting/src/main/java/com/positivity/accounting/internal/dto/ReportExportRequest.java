package com.positivity.accounting.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.accounting.internal.enums.ExportFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.UUID;
import lombok.*;

/**
 * Request to export a financial report asynchronously.
 *
 * Supports CSV and PDF formats for v1.0.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Request payload for exporting a financial report")
public class ReportExportRequest {

    /**
     * Export format (CSV or PDF).
     */
    @NotNull(message = "format is required")
    @Schema(description = "Export format", example = "CSV", requiredMode = REQUIRED)
    private ExportFormat format;

    /**
     * Report type to export.
     */
    @NotBlank(message = "reportType is required")
    @Size(max = 100, message = "reportType must not exceed 100 characters")
    @Schema(
            description = "Report type key. Renderable types: TAX_LIABILITY, INCOME_STATEMENT, BALANCE_SHEET, "
                    + "TRIAL_BALANCE, GENERAL_LEDGER, AGED_RECEIVABLES, AGED_PAYABLES.",
            example = "INCOME_STATEMENT",
            requiredMode = REQUIRED)
    private String reportType;

    /**
     * Period start date (inclusive).
     */
    @NotNull(message = "startDate is required")
    @Schema(description = "Period start date (inclusive, YYYY-MM-DD)", example = "2026-01-01", requiredMode = REQUIRED)
    private LocalDate startDate;

    /**
     * Period end date (inclusive). For as-of reports this date is the as-of date.
     */
    @NotNull(message = "endDate is required")
    @Schema(
            description = "Period end date (inclusive, YYYY-MM-DD). For the as-of reports (BALANCE_SHEET, "
                    + "TRIAL_BALANCE, AGED_RECEIVABLES, AGED_PAYABLES) this date is used as the as-of date.",
            example = "2026-03-31",
            requiredMode = REQUIRED)
    private LocalDate endDate;

    /**
     * Organization (tenant) scope for the export.
     */
    @NotNull(message = "organizationId is required")
    @Schema(
            description = "Organization UUID to scope the export",
            example = "d10217f9-3ec6-46b9-9c87-e7066c100c24",
            requiredMode = REQUIRED)
    private UUID organizationId;

    /**
     * Optional GL account filter for GENERAL_LEDGER exports.
     */
    @Schema(
            description = "Optional GL account UUID filter, honored by GENERAL_LEDGER exports only; "
                    + "null spans all accounts",
            example = "123e4567-e89b-12d3-a456-426614174000",
            requiredMode = NOT_REQUIRED)
    private UUID accountId;

    /**
     * Optional filename (without extension).
     */
    @Size(max = 150, message = "filename must not exceed 150 characters")
    @Schema(
            description = "Optional output filename without extension",
            example = "journal-lines-q1-2026",
            requiredMode = NOT_REQUIRED)
    private String filename;
}

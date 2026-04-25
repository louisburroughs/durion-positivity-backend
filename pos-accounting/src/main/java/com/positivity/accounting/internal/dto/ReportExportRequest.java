package com.positivity.accounting.internal.dto;

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
    @Schema(description = "Export format", example = "CSV", requiredMode = Schema.RequiredMode.REQUIRED)
    private ExportFormat format;

    /**
     * Report type to export (e.g. JOURNAL_LINES, INCOME_STATEMENT).
     */
    @NotBlank(message = "reportType is required")
    @Size(max = 100, message = "reportType must not exceed 100 characters")
    @Schema(description = "Report type key", example = "JOURNAL_LINES", requiredMode = Schema.RequiredMode.REQUIRED)
    private String reportType;

    /**
     * Period start date (inclusive).
     */
    @NotNull(message = "startDate is required")
    @Schema(description = "Period start date (inclusive, YYYY-MM-DD)", example = "2026-01-01", requiredMode = Schema.RequiredMode.REQUIRED)
    private LocalDate startDate;

    /**
     * Period end date (inclusive).
     */
    @NotNull(message = "endDate is required")
    @Schema(description = "Period end date (inclusive, YYYY-MM-DD)", example = "2026-03-31", requiredMode = Schema.RequiredMode.REQUIRED)
    private LocalDate endDate;

    /**
     * Organization (tenant) scope for the export.
     */
    @NotNull(message = "organizationId is required")
    @Schema(description = "Organization UUID to scope the export", example = "d10217f9-3ec6-46b9-9c87-e7066c100c24", requiredMode = Schema.RequiredMode.REQUIRED)
    private UUID organizationId;

    /**
     * Optional filename (without extension).
     */
    @Size(max = 150, message = "filename must not exceed 150 characters")
    @Schema(description = "Optional output filename without extension", example = "journal-lines-q1-2026")
    private String filename;
}

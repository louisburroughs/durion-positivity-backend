package com.positivity.accounting.internal.dto;

import com.positivity.accounting.internal.enums.ExportFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.*;

/**
 * Request to export a financial report.
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
     * Report ID or type to export.
     */
    @NotBlank(message = "reportId is required")
    @Size(max = 100, message = "reportId must not exceed 100 characters")
    @Schema(
            description = "Report identifier or type key",
            example = "income-statement",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String reportId;

    /**
     * Optional filename (without extension).
     * If null, generates default: "income-statement-2026-01-01-2026-01-31"
     */
    @Size(max = 150, message = "filename must not exceed 150 characters")
    @Schema(description = "Optional output filename without extension", example = "income-statement-2026-01")
    private String filename;
}

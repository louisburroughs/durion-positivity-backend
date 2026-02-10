package com.positivity.accounting.internal.dto;

import com.positivity.accounting.internal.enums.ExportFormat;
import lombok.*;
import org.jspecify.annotations.NonNull;

/**
 * Request to export a financial report.
 * 
 * Supports CSV and PDF formats for v1.0.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReportExportRequest {

    /**
     * Export format (CSV or PDF).
     */
    @NonNull
    private ExportFormat format;

    /**
     * Report ID or type to export.
     */
    @NonNull
    private String reportId;

    /**
     * Optional filename (without extension).
     * If null, generates default: "income-statement-2026-01-01-2026-01-31"
     */
    private String filename;
}

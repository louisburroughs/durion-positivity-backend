package com.positivity.securityservice.internal.dto;

import com.positivity.securityservice.internal.enums.AuditDeliveryMode;
import com.positivity.securityservice.internal.enums.AuditExportFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request body for POST /v1/audit/exports (B-4).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Audit export job submission request")
public class AuditExportRequest {

    @Schema(description = "Filter criteria to scope the export", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private AuditEventSearchFilter filters;

    @NotNull
    @Schema(description = "Output format for the export", example = "CSV")
    private AuditExportFormat format;

    @NotNull
    @Schema(description = "Delivery mode for the exported file", example = "DOWNLOAD")
    private AuditDeliveryMode deliveryMode;
}

package com.positivity.bulkloader.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.Map;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "A single corrected data record for a failed audit entry")
public class BulkCorrectionItem {

    @NotNull
    @Schema(
            description = "ID of the audit record being corrected",
            example = "00000000-0000-0000-0000-000000000001",
            requiredMode = io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED)
    private UUID auditRecordId;

    @NotNull
    @Schema(
            description = "Map of field names to their corrected values",
            example = "{\"sku\": \"PROD-001\", \"price\": \"19.99\"}",
            requiredMode = io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED)
    private Map<String, String> correctedData;
}

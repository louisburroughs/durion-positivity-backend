package com.positivity.workorder.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Recorded labor-performed response")
public class WorkexecLaborPerformedResponse {

    @JsonProperty("laborPerformedId")
    @Schema(
            description = "Labor performed record identifier",
            example = "550e8400-e29b-41d4-a716-446655440700",
            requiredMode = REQUIRED)
    private UUID laborPerformedId;

    @JsonProperty("workorderId")
    @Schema(
            description = "Workorder identifier",
            example = "550e8400-e29b-41d4-a716-446655440000",
            requiredMode = REQUIRED)
    private UUID workorderId;

    @JsonProperty("technicianId")
    @Schema(
            description = "Technician identifier",
            example = "550e8400-e29b-41d4-a716-446655440120",
            requiredMode = REQUIRED)
    private UUID technicianId;

    @JsonProperty("performedAt")
    @Schema(description = "Timestamp when labor was performed", example = "2026-01-15T09:30:00Z", requiredMode = REQUIRED)
    private Instant performedAt;

    @JsonProperty("quantity")
    @Schema(description = "Labor quantity", example = "1.5", requiredMode = REQUIRED)
    private BigDecimal quantity;

    @JsonProperty("unit")
    @Schema(description = "Labor quantity unit", example = "HOURS", requiredMode = REQUIRED)
    private String unit;

    @JsonProperty("sourceSystem")
    @Schema(description = "Source system identifier", example = "workexec", requiredMode = REQUIRED)
    private String sourceSystem;

    @JsonProperty("sourceReferenceId")
    @Schema(description = "Source reference identifier", example = "EXT-REF-12345", requiredMode = REQUIRED)
    private String sourceReferenceId;
}

package com.positivity.workorder.internal.dto;

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
    @Schema(description = "Labor performed record identifier")
    private UUID laborPerformedId;

    @JsonProperty("workorderId")
    @Schema(description = "Workorder identifier", example = "550e8400-e29b-41d4-a716-446655440000")
    private UUID workorderId;

    @JsonProperty("technicianId")
    @Schema(description = "Technician identifier", example = "550e8400-e29b-41d4-a716-446655440120")
    private UUID technicianId;

    @JsonProperty("performedAt")
    @Schema(description = "Timestamp when labor was performed")
    private Instant performedAt;

    @JsonProperty("quantity")
    @Schema(description = "Labor quantity", example = "1.5")
    private BigDecimal quantity;

    @JsonProperty("unit")
    @Schema(description = "Labor quantity unit", example = "HOURS")
    private String unit;

    @JsonProperty("sourceSystem")
    @Schema(description = "Source system identifier", example = "workexec")
    private String sourceSystem;

    @JsonProperty("sourceReferenceId")
    @Schema(description = "Source reference identifier", example = "EXT-REF-12345")
    private String sourceReferenceId;
}

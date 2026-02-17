package com.positivity.workorder.internal.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

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
    private UUID workorderId;

    @JsonProperty("technicianId")
    private UUID technicianId;

    @JsonProperty("performedAt")
    private Instant performedAt;

    @JsonProperty("quantity")
    private BigDecimal quantity;

    @JsonProperty("unit")
    private String unit;

    @JsonProperty("sourceSystem")
    private String sourceSystem;

    @JsonProperty("sourceReferenceId")
    private String sourceReferenceId;
}

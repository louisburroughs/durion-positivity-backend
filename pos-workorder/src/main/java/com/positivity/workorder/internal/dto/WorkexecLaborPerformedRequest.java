package com.positivity.workorder.internal.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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
@Schema(description = "Request payload for recording labor performed from external systems")
public class WorkexecLaborPerformedRequest {

    @NotNull
    @JsonProperty("workorderId")
    @Schema(description = "Workorder identifier", requiredMode = Schema.RequiredMode.REQUIRED)
    private UUID workorderId;

    @NotNull
    @JsonProperty("technicianId")
    @Schema(description = "Technician identifier", requiredMode = Schema.RequiredMode.REQUIRED)
    private UUID technicianId;

    @NotNull
    @JsonProperty("performedAt")
    @Schema(description = "Timestamp when labor was performed", requiredMode = Schema.RequiredMode.REQUIRED)
    private Instant performedAt;

    @NotNull
    @Valid
    @JsonProperty("labor")
    @Schema(description = "Labor quantity and unit", requiredMode = Schema.RequiredMode.REQUIRED)
    private LaborQuantity labor;

    @NotNull
    @Valid
    @JsonProperty("source")
    @Schema(description = "External source metadata", requiredMode = Schema.RequiredMode.REQUIRED)
    private SourceReference source;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class LaborQuantity {
        @NotNull
        @JsonProperty("quantity")
        private BigDecimal quantity;

        @NotBlank
        @JsonProperty("unit")
        private String unit;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class SourceReference {
        @NotBlank
        @JsonProperty("system")
        private String system;

        @NotBlank
        @JsonProperty("sourceReferenceId")
        private String sourceReferenceId;
    }
}

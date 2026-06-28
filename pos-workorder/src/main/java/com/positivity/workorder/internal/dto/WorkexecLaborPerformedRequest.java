package com.positivity.workorder.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

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
    @Schema(
            description = "Workorder identifier",
            example = "550e8400-e29b-41d4-a716-446655440000",
            requiredMode = REQUIRED)
    private UUID workorderId;

    @NotNull
    @JsonProperty("technicianId")
    @Schema(
            description = "Technician identifier",
            example = "550e8400-e29b-41d4-a716-446655440120",
            requiredMode = REQUIRED)
    private UUID technicianId;

    @NotNull
    @JsonProperty("performedAt")
    @Schema(
            description = "Timestamp when labor was performed",
            example = "2026-01-15T09:30:00Z",
            requiredMode = REQUIRED)
    private Instant performedAt;

    @NotNull
    @Valid
    @JsonProperty("labor")
    @Schema(description = "Labor quantity and unit", requiredMode = REQUIRED)
    private LaborQuantity labor;

    @NotNull
    @Valid
    @JsonProperty("source")
    @Schema(description = "External source metadata", requiredMode = REQUIRED)
    private SourceReference source;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @Schema(description = "Labor quantity and unit of measure")
    public static class LaborQuantity {
        @NotNull
        @JsonProperty("quantity")
        @Schema(description = "Amount of labor performed", example = "1.5", requiredMode = REQUIRED)
        private BigDecimal quantity;

        @NotBlank
        @JsonProperty("unit")
        @Schema(description = "Unit of measure for the labor quantity", example = "HOURS", requiredMode = REQUIRED)
        private String unit;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @Schema(description = "External source system reference metadata")
    public static class SourceReference {
        @NotBlank
        @JsonProperty("system")
        @Schema(
                description = "Identifier of the originating external system",
                example = "workexec",
                requiredMode = REQUIRED)
        private String system;

        @NotBlank
        @JsonProperty("sourceReferenceId")
        @Schema(
                description = "Reference identifier of the record in the source system",
                example = "EXT-REF-12345",
                requiredMode = REQUIRED)
        private String sourceReferenceId;
    }
}

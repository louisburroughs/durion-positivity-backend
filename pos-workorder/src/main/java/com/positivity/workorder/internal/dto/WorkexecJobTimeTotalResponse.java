package com.positivity.workorder.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Daily job-time total grouped by technician, location, and local date")
public class WorkexecJobTimeTotalResponse {

    @JsonProperty("technicianId")
    @Schema(
            description = "Technician identifier",
            example = "550e8400-e29b-41d4-a716-446655440120",
            requiredMode = REQUIRED)
    private UUID technicianId;

    @JsonProperty("locationId")
    @Schema(
            description = "Location identifier",
            example = "550e8400-e29b-41d4-a716-446655440300",
            requiredMode = REQUIRED)
    private UUID locationId;

    @JsonProperty("localDate")
    @Schema(description = "Local date in requested timezone", example = "2026-01-15", requiredMode = REQUIRED)
    private LocalDate localDate;

    @JsonProperty("totalJobMinutes")
    @Schema(description = "Total approved/finalized job minutes", example = "360", requiredMode = REQUIRED)
    private Integer totalJobMinutes;
}

package com.positivity.workorder.internal.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Daily job-time total grouped by technician, location, and local date")
public class WorkexecJobTimeTotalResponse {

    @JsonProperty("technicianId")
    @Schema(description = "Technician identifier", example = "550e8400-e29b-41d4-a716-446655440120")
    private UUID technicianId;

    @JsonProperty("locationId")
    @Schema(description = "Location identifier", example = "550e8400-e29b-41d4-a716-446655440300")
    private UUID locationId;

    @JsonProperty("localDate")
    @Schema(description = "Local date in requested timezone", example = "2026-01-15")
    private LocalDate localDate;

    @JsonProperty("totalJobMinutes")
    @Schema(description = "Total approved/finalized job minutes", example = "360")
    private Integer totalJobMinutes;
}

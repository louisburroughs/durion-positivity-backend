package com.positivity.people.internal.client.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.UUID;

@Schema(description = "Aggregated approved job time for a technician on a given local date, sourced from workexec")
public class WorkexecJobTimeTotal {

    @Schema(
            description = "Technician identifier",
            example = "01960011-0000-7000-8000-000000000001",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private UUID technicianId;

    @Schema(
            description = "Location identifier",
            example = "01960011-0000-7000-8000-000000000010",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private UUID locationId;

    @Schema(description = "Local calendar date for the aggregation", example = "2026-02-16", requiredMode = Schema.RequiredMode.REQUIRED)
    private LocalDate localDate;

    @Schema(description = "Total approved job minutes for the date", example = "480", requiredMode = Schema.RequiredMode.REQUIRED)
    private Integer totalJobMinutes;

    public UUID getTechnicianId() {
        return technicianId;
    }

    public void setTechnicianId(UUID technicianId) {
        this.technicianId = technicianId;
    }

    public UUID getLocationId() {
        return locationId;
    }

    public void setLocationId(UUID locationId) {
        this.locationId = locationId;
    }

    public LocalDate getLocalDate() {
        return localDate;
    }

    public void setLocalDate(LocalDate localDate) {
        this.localDate = localDate;
    }

    public Integer getTotalJobMinutes() {
        return totalJobMinutes;
    }

    public void setTotalJobMinutes(Integer totalJobMinutes) {
        this.totalJobMinutes = totalJobMinutes;
    }
}

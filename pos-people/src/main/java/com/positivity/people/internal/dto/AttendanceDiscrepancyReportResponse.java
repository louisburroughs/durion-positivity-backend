package com.positivity.people.internal.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import lombok.AllArgsConstructor;

@AllArgsConstructor
@Schema(description = "Attendance vs job time discrepancy row")
public class AttendanceDiscrepancyReportResponse {

    @Schema(
            description = "Technician identifier",
            example = "123e4567-e89b-12d3-a456-426614174000",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String technicianId;

    @Schema(description = "Technician display name", example = "Jane Doe", requiredMode = Schema.RequiredMode.REQUIRED)
    private String technicianName;

    @Schema(
            description = "Location identifier",
            example = "22222222-2222-2222-2222-222222222222",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String locationId;

    @Schema(description = "Report local date", example = "2026-02-16", requiredMode = Schema.RequiredMode.REQUIRED)
    private LocalDate reportDate;

    @Schema(
            description = "Total attendance time in decimal hours",
            example = "8.00",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private double totalAttendanceHours;

    @Schema(
            description = "Total approved job time in decimal hours",
            example = "6.00",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private double totalJobHours;

    @Schema(
            description = "Attendance minus job time in decimal hours",
            example = "2.00",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private double discrepancyHours;

    @JsonProperty("isFlagged")
    @Schema(
            description = "True when absolute discrepancy exceeds threshold",
            example = "true",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private boolean flagged;

    @Schema(
            description = "Threshold in minutes used for this row",
            example = "60",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private int thresholdApplied;

    public String getTechnicianId() {
        return technicianId;
    }

    public void setTechnicianId(String technicianId) {
        this.technicianId = technicianId;
    }

    public String getTechnicianName() {
        return technicianName;
    }

    public void setTechnicianName(String technicianName) {
        this.technicianName = technicianName;
    }

    public String getLocationId() {
        return locationId;
    }

    public void setLocationId(String locationId) {
        this.locationId = locationId;
    }

    public LocalDate getReportDate() {
        return reportDate;
    }

    public void setReportDate(LocalDate reportDate) {
        this.reportDate = reportDate;
    }

    public double getTotalAttendanceHours() {
        return totalAttendanceHours;
    }

    public void setTotalAttendanceHours(double totalAttendanceHours) {
        this.totalAttendanceHours = totalAttendanceHours;
    }

    public double getTotalJobHours() {
        return totalJobHours;
    }

    public void setTotalJobHours(double totalJobHours) {
        this.totalJobHours = totalJobHours;
    }

    public double getDiscrepancyHours() {
        return discrepancyHours;
    }

    public void setDiscrepancyHours(double discrepancyHours) {
        this.discrepancyHours = discrepancyHours;
    }

    @JsonProperty("isFlagged")
    public boolean isFlagged() {
        return flagged;
    }

    @JsonProperty("isFlagged")
    public void setFlagged(boolean flagged) {
        this.flagged = flagged;
    }

    public int getThresholdApplied() {
        return thresholdApplied;
    }

    public void setThresholdApplied(int thresholdApplied) {
        this.thresholdApplied = thresholdApplied;
    }
}

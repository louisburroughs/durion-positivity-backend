package com.positivity.workorder.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.positivity.workorder.internal.entity.WorkorderLaborEntry;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.NonNull;

/**
 * Response DTO for labor entry operations.
 *
 * <p>
 * Implements CAP-005 Story #159 - Record Labor Performed
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Labor entry response")
public class WorkorderLaborEntryResponse {

    @Schema(
            description = "Unique ID of the labor entry",
            example = "550e8400-e29b-41d4-a716-446655440100",
            requiredMode = REQUIRED)
    @NotNull
    @JsonProperty("id")
    private UUID id;

    @Schema(description = "Workorder ID", example = "550e8400-e29b-41d4-a716-446655440001", requiredMode = REQUIRED)
    @NotNull
    @JsonProperty("workorderId")
    private UUID workorderId;

    @Schema(
            description = "Service line item ID",
            example = "550e8400-e29b-41d4-a716-446655440010",
            requiredMode = NOT_REQUIRED)
    @JsonProperty("workorderServiceId")
    private UUID workorderServiceId;

    @Schema(
            description = "Technician ID who performed the work",
            example = "550e8400-e29b-41d4-a716-446655440050",
            requiredMode = NOT_REQUIRED)
    @JsonProperty("technicianId")
    private UUID technicianId;

    @Schema(description = "Labor session start time", example = "2024-01-27T14:30:00", requiredMode = NOT_REQUIRED)
    @JsonProperty("startTime")
    private LocalDateTime startTime;

    @Schema(
            description = "Labor session end time (null if still active)",
            example = "2024-01-27T16:30:00",
            requiredMode = NOT_REQUIRED)
    @JsonProperty("endTime")
    private LocalDateTime endTime;

    @Schema(description = "Hours worked", example = "2.00", requiredMode = NOT_REQUIRED)
    @JsonProperty("hoursWorked")
    private BigDecimal hoursWorked;

    @Schema(description = "Session notes", example = "Brake pads replaced successfully", requiredMode = NOT_REQUIRED)
    @JsonProperty("notes")
    private String notes;

    @Schema(
            description = "Adjustment reason (if hours were manually adjusted)",
            example = "Corrected for break time",
            requiredMode = NOT_REQUIRED)
    @JsonProperty("adjustmentReason")
    private String adjustmentReason;

    @Schema(
            description = "Whether this session is still active (not stopped)",
            example = "false",
            requiredMode = REQUIRED)
    @JsonProperty("active")
    private boolean active;

    @Schema(description = "User who created this entry", example = "system@syte.com", requiredMode = NOT_REQUIRED)
    @JsonProperty("createdBy")
    private String createdBy;

    @Schema(description = "Creation timestamp", example = "2024-01-27T14:30:00Z", requiredMode = NOT_REQUIRED)
    @JsonProperty("createdAt")
    private Instant createdAt;

    /**
     * Convert entity to response DTO.
     */
    @NonNull
    public static WorkorderLaborEntryResponse fromEntity(@NonNull WorkorderLaborEntry entry) {
        return WorkorderLaborEntryResponse.builder()
                .id(entry.getId())
                .workorderId(
                        entry.getWorkorder() == null
                                ? null
                                : entry.getWorkorder().getId())
                .workorderServiceId(entry.getWorkorderServiceId())
                .technicianId(entry.getTechnicianId())
                .startTime(entry.getStartTime())
                .endTime(entry.getEndTime())
                .hoursWorked(entry.getHoursWorked())
                .notes(entry.getNotes())
                .adjustmentReason(entry.getAdjustmentReason())
                .active(entry.isActive())
                .createdBy(entry.getCreatedBy())
                .createdAt(entry.getCreatedAt())
                .build();
    }
}

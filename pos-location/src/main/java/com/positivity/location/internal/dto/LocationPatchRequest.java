package com.positivity.location.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.PositiveOrZero;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Partial update request for location mutation endpoints.
 *
 * Issue: CAP-136 #78
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Partial update payload for a location; null fields are left unchanged")
public class LocationPatchRequest {

    @Schema(description = "Display name of the location", example = "Downtown Service Center", requiredMode = NOT_REQUIRED)
    private String name;

    @Schema(description = "Operational status of the location", example = "ACTIVE", requiredMode = NOT_REQUIRED)
    private String status;

    @Schema(description = "IANA timezone identifier for the location", example = "America/Chicago", requiredMode = NOT_REQUIRED)
    private String timezone;

    @Schema(description = "Weekly operating hours for the location", requiredMode = NOT_REQUIRED)
    @Valid
    private List<OperatingHoursRequest> operatingHours;

    @Schema(description = "Holiday closures for the location", requiredMode = NOT_REQUIRED)
    @Valid
    private List<HolidayClosureRequest> holidayClosures;

    @Schema(
            description = "Buffer minutes reserved before appointments for check-in",
            example = "15",
            requiredMode = NOT_REQUIRED)
    @PositiveOrZero
    private Integer checkInBufferMinutes;

    @Schema(
            description = "Buffer minutes reserved after appointments for cleanup",
            example = "10",
            requiredMode = NOT_REQUIRED)
    @PositiveOrZero
    private Integer cleanupBufferMinutes;
}

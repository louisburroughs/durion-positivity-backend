package com.positivity.location.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import java.time.LocalTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Operating-hours payload entry for a location.
 *
 * Issue: CAP-136 #78
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "A single operating-hours entry for a day of the week")
public class OperatingHoursRequest {

    @Schema(description = "Day of week the hours apply to", example = "MONDAY", requiredMode = REQUIRED)
    @NotBlank
    private String dayOfWeek;

    @Schema(description = "Opening time (local time)", example = "08:00", requiredMode = NOT_REQUIRED)
    private LocalTime openTime;

    @Schema(description = "Closing time (local time)", example = "17:00", requiredMode = NOT_REQUIRED)
    private LocalTime closeTime;
}

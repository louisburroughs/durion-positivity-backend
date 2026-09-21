package com.positivity.location.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One stored operating-hours entry as the location read returns it (issue #2139).
 *
 * <p>The write side's {@link OperatingHoursRequest} is not reused: a response carries no
 * {@code @NotBlank} request validation, and the two are free to diverge.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "A single stored operating-hours entry for a day of the week")
public class OperatingHoursResponse {

    @Schema(description = "Day of week the hours apply to", example = "MONDAY", requiredMode = REQUIRED)
    private String dayOfWeek;

    @Schema(
            description = "Opening time, in the location's own timezone",
            example = "08:00:00",
            requiredMode = NOT_REQUIRED)
    private LocalTime openTime;

    @Schema(
            description = "Closing time, in the location's own timezone",
            example = "17:00:00",
            requiredMode = NOT_REQUIRED)
    private LocalTime closeTime;
}

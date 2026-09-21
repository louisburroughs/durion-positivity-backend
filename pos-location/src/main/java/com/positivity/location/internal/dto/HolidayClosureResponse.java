package com.positivity.location.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One stored holiday closure as the location read returns it (issue #2139).
 *
 * <p>The write side's {@link HolidayClosureRequest} is not reused, for the reason given on
 * {@link OperatingHoursResponse}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "A single stored holiday closure for a location")
public class HolidayClosureResponse {

    @Schema(
            description = "Date on which the location is closed, in the location's own timezone",
            example = "2026-12-25",
            requiredMode = REQUIRED)
    private LocalDate date;

    @Schema(
            description = "Human-readable reason for the closure",
            example = "Christmas Day",
            requiredMode = NOT_REQUIRED)
    private String reason;
}

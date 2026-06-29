package com.positivity.location.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Holiday-closure payload entry for a location.
 *
 * Issue: CAP-136 #78
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "A single holiday closure entry for a location")
public class HolidayClosureRequest {

    @Schema(description = "Date on which the location is closed", example = "2026-12-25", requiredMode = REQUIRED)
    @NotNull
    private LocalDate date;

    @Schema(
            description = "Human-readable reason for the closure",
            example = "Christmas Day",
            requiredMode = NOT_REQUIRED)
    private String reason;
}

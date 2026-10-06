package com.positivity.accounting.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Request to set the tenant's accounting-calendar time zone (#2558). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Request payload for setting the tenant's accounting-calendar time zone")
public class AccountingTimeZoneUpdateRequest {

    @NotBlank(message = "timeZone is required")
    @Size(max = 64, message = "timeZone must not exceed 64 characters")
    @Schema(
            description = "IANA region id of the tenant's accounting calendar, such as America/Chicago. Every"
                    + " settlement, application and posting is dated, and every period is cut, in this zone."
                    + " Fixed offsets (+05:00, UTC+05:00, Etc/GMT+5) and SystemV ids are rejected; UTC is accepted.",
            example = "America/Chicago",
            requiredMode = REQUIRED)
    private String timeZone;
}

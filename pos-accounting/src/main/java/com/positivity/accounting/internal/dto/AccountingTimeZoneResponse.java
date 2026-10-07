package com.positivity.accounting.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** The tenant's accounting-calendar time zone (#2558). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "The tenant's accounting-calendar time zone")
public class AccountingTimeZoneResponse {

    @Schema(
            description = "IANA region id in which the tenant's postings are dated and its periods are cut",
            example = "America/Chicago",
            requiredMode = REQUIRED)
    private String timeZone;
}

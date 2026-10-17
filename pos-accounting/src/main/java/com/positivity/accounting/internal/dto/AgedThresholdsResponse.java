package com.positivity.accounting.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** The tenant's effective aged-item thresholds (CAP:550 S19, #2515); a default stands in for an unset value. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "The tenant's effective aged-item thresholds, in calendar days on the accounting date")
public class AgedThresholdsResponse {

    @Schema(description = "Days before an undeposited session is aged; 1 to 30", example = "3", requiredMode = REQUIRED)
    private int undepositedCashAgedDays;

    @Schema(
            description = "Days before an unapplied vendor cash-on-delivery payment is aged; 1 to 90",
            example = "14",
            requiredMode = REQUIRED)
    private int vendorCodUnappliedAgedDays;

    @Schema(
            description = "Days before a delivery with no approved bill is listed; 7 to 120",
            example = "30",
            requiredMode = REQUIRED)
    private int deliveriesNotBilledDays;
}

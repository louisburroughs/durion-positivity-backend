package com.positivity.accounting.internal.bankrec.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Reverse a reconciliation adjustment (SPEC §4.9 path 2; story S4, #2303). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Reverse a posted reconciliation adjustment")
public class AdjustmentReverseRequest {

    @Size(max = 1000, message = "reason must not exceed 1000 characters")
    @Schema(
            description = "Why the adjustment is reversed (at least 10 characters)",
            example = "Bank refunded the fee",
            requiredMode = REQUIRED)
    private String reason;

    @Schema(description = "Reversal date; defaults to the original's date when its period is open, else today")
    private LocalDate reversalDate;

    @Size(max = 1000, message = "overrideJustification must not exceed 1000 characters")
    @Schema(description = "Reverse into a CLOSED period; needs accounting:period:override")
    private String overrideJustification;
}

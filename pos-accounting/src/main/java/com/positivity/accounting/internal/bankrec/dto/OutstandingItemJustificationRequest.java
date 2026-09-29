package com.positivity.accounting.internal.bankrec.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Reaffirm an aged timing item, or clear an item in an acknowledged gap (SPEC §3.6; story S4, #2303). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "A justification of at least 10 characters")
public class OutstandingItemJustificationRequest {

    @Size(max = 1000, message = "justification must not exceed 1000 characters")
    @Schema(
            description = "At least 10 characters",
            example = "Vendor confirmed the refund posts next month",
            requiredMode = REQUIRED)
    private String justification;
}

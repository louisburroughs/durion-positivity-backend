package com.positivity.accounting.internal.bankrec.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Release an outstanding item, with the reason (SPEC §3.6; story S4, #2303). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Release an OPEN outstanding item")
public class OutstandingItemReasonRequest {

    @Size(max = 1000, message = "reason must not exceed 1000 characters")
    @Schema(
            description = "Why the item is released (at least 10 characters)",
            example = "Registered on the wrong line",
            requiredMode = REQUIRED)
    private String reason;
}

package com.positivity.accounting.internal.bankrec.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Unmatch an accepted match, with the reason (SPEC §4.6, G3; story S4, #2303). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Unmatch an ACCEPTED match; the reason is recorded on the match and in the audit trail")
public class ReconciliationUnmatchRequest {

    @Size(max = 1000, message = "reason must not exceed 1000 characters")
    @Schema(
            description = "Why the match is undone (at least 10 characters)",
            example = "Paired the wrong deposit",
            requiredMode = REQUIRED)
    private String reason;
}

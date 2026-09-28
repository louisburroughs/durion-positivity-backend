package com.positivity.accounting.internal.bankrec.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Accept or reject a proposed match (SPEC §3.4, §6.1; story S4, #2303). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Accept or reject a PROPOSED match, with an optional justification")
public class ReconciliationMatchDecisionRequest {

    @Size(max = 1000, message = "justification must not exceed 1000 characters")
    @Schema(
            description = "At least 10 characters; required to accept a proposal that uses the tolerance or spans"
                    + " dates beyond the window",
            example = "Same processor payout, one day late")
    private String justification;
}

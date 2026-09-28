package com.positivity.accounting.internal.bankrec.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** What an auto-match run proposed (SPEC §4.6, D12; story S4, #2303). Nothing is ever accepted by the system. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Proposals made by auto-match, and bank transactions left ambiguous")
public class AutoMatchResponse {

    @Schema(description = "PROPOSED one-to-one RULE matches created", example = "12")
    private int proposedCount;

    @Schema(description = "Bank transactions whose top two candidates were too close to propose", example = "2")
    private int ambiguousCount;
}

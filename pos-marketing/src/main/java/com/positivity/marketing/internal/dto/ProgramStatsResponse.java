package com.positivity.marketing.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;

/**
 * The commercial and individual arms of one initiative, side by side (Story #1152).
 *
 * <p>The comparison is the point: the same offer performs very differently for a fleet manager
 * and a retail customer, and a single blended number hides exactly the signal that would tell a
 * marketer which arm to keep funding.
 */
@Schema(description = "Campaign program rollup comparing commercial and individual arms")
public record ProgramStatsResponse(
        @Schema(description = "Program identifier", requiredMode = REQUIRED)
        UUID campaignProgramId,

        @Schema(description = "One entry per campaign in the program", requiredMode = REQUIRED)
        List<CampaignStatsResponse> arms) {}

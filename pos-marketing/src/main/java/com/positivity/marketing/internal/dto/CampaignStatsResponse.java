package com.positivity.marketing.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * A campaign's reach funnel and attributed conversions (Story #1152).
 *
 * <p>Reported as a funnel rather than a set of independent totals because each stage only
 * means something against the one above it: 200 delivered is good news against 220 sent and
 * bad news against 2,000.
 */
@Schema(description = "Campaign reach funnel and attribution")
public record CampaignStatsResponse(
        @Schema(description = "Campaign", requiredMode = REQUIRED)
        UUID campaignId,

        @Schema(description = "Campaign code", requiredMode = REQUIRED)
        String code,

        @Schema(
                description = "Targeted party kind",
                allowableValues = {"COMMERCIAL", "INDIVIDUAL"},
                requiredMode = REQUIRED)
        String audienceType,

        @Schema(description = "Program grouping this campaign's arm", requiredMode = NOT_REQUIRED)
        UUID campaignProgramId,

        @Schema(description = "Per-channel funnel", requiredMode = REQUIRED)
        List<ChannelFunnel> channels,

        @Schema(description = "Redemptions credited to this campaign", example = "37", requiredMode = REQUIRED)
        long redeemed,

        @Schema(description = "Total discount value across attributed redemptions", requiredMode = REQUIRED)
        BigDecimal attributedDiscount) {

    @Schema(description = "Reach funnel for one channel")
    public record ChannelFunnel(
            @Schema(
                    description = "Channel",
                    allowableValues = {"EMAIL", "SMS"},
                    requiredMode = REQUIRED)
            String channel,

            @Schema(description = "Recipients queued", example = "412", requiredMode = REQUIRED)
            long targeted,

            @Schema(
                    description = "Recipients blocked at dispatch by consent, account gate, or suppression",
                    example = "125",
                    requiredMode = REQUIRED)
            long suppressed,

            @Schema(description = "Messages dispatched to the provider", example = "287", requiredMode = REQUIRED)
            long sent,

            @Schema(description = "Messages the provider confirmed delivered", requiredMode = REQUIRED)
            long delivered,

            @Schema(description = "Hard bounces", requiredMode = REQUIRED)
            long bounced,

            @Schema(description = "Spam complaints", requiredMode = REQUIRED)
            long complained,

            @Schema(description = "Permanent send failures", requiredMode = REQUIRED)
            long failed) {}
}

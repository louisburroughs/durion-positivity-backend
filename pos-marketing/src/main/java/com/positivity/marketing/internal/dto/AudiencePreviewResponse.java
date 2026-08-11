package com.positivity.marketing.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;

/**
 * What a campaign would reach based on its latest audience snapshot (Story #1148).
 *
 * <p>Reports targeted and eligible counts per channel rather than a recipient list. The gap
 * between the two is the number the marketer needs: it is the difference between "my segment
 * is too narrow" and "most of these people have opted out".
 *
 * <p>Membership is replicated asynchronously (ADR-0044), so these counts describe a snapshot
 * with a stated age rather than a live query. Requesting a preview also asks the CRM to refresh
 * that snapshot, so a second call reflects any change.
 */
@Schema(description = "Per-channel audience preview after consent and suppression filtering")
public record AudiencePreviewResponse(
        @Schema(description = "Campaign previewed", requiredMode = REQUIRED)
        UUID campaignId,

        @Schema(description = "Bound segment", requiredMode = NOT_REQUIRED)
        UUID segmentId,

        @Schema(
                description = "Targeted party kind",
                allowableValues = {"COMMERCIAL", "INDIVIDUAL"},
                requiredMode = REQUIRED)
        String audienceType,

        @Schema(
                description = "Parties in the current audience snapshot, before consent filtering",
                requiredMode = REQUIRED)
        long segmentMatched,

        @Schema(
                description =
                        "When the CRM resolved this snapshot; null if none has arrived yet. Membership replicates asynchronously, so these counts are as of this moment, not live.",
                requiredMode = NOT_REQUIRED)
        java.time.Instant resolvedAt,

        @Schema(
                description = "Whether the segment resolution hit its candidate ceiling and is partial",
                requiredMode = REQUIRED)
        boolean truncated,

        @Schema(description = "Per-channel reach after consent, account gate, and suppression", requiredMode = REQUIRED)
        List<ChannelPreview> channels,

        @Schema(
                description = "Validation warnings that would block scheduling, e.g. an expired offer",
                requiredMode = REQUIRED)
        List<String> warnings) {

    @Schema(description = "Reach on one channel")
    public record ChannelPreview(
            @Schema(
                    description = "Channel",
                    allowableValues = {"EMAIL", "SMS"},
                    requiredMode = REQUIRED)
            String channel,

            @Schema(description = "Parties targeted on this channel", example = "412", requiredMode = REQUIRED)
            long targeted,

            @Schema(
                    description = "Of those, how many may actually be sent to",
                    example = "287",
                    requiredMode = REQUIRED)
            long eligible,

            @Schema(description = "Whether a template is attached for this channel", requiredMode = REQUIRED)
            boolean templateAttached) {}
}

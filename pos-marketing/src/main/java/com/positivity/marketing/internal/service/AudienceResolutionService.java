package com.positivity.marketing.internal.service;

import com.positivity.marketing.internal.dto.AudiencePreviewResponse;
import com.positivity.marketing.internal.entity.Campaign;
import com.positivity.marketing.internal.enums.CampaignChannel;
import com.positivity.marketing.internal.repository.CampaignAudienceMemberRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns a campaign's replicated audience snapshot into the numbers a marketer decides on
 * (Story #1148).
 *
 * <p>Two things about this are worth stating plainly, because both look like defects
 * otherwise.
 *
 * <p><b>The counts are a snapshot, not a query.</b> Segment membership is derived from party
 * data and has no event boundary to replicate from, so this module asks the CRM and waits for
 * an answer (ADR-0044 R3). A preview therefore reports the last answer received, stamped with
 * its age, and asks for a fresher one on the way out — so a second call reflects any change.
 * Reporting a stale number honestly beats blocking the request on a round trip that may never
 * complete.
 *
 * <p><b>Eligibility is evaluated exactly as the send worker will evaluate it.</b> Same
 * precedence, same staleness bound, same reason codes — a preview that used a looser rule
 * would promise reach the campaign never achieves, which is the one way a preview can be
 * worse than no preview at all.
 */
@Slf4j
@Component
public class AudienceResolutionService {

    /** Upper bound on the sample, whatever the configuration asks for. */
    private static final int MAX_SAMPLE_SIZE = 50;

    private final CampaignAudienceMemberRepository audienceRepository;
    private final AudienceEligibilityService eligibilityService;
    private final SegmentResolveRequester resolveRequester;
    private final int sampleSize;

    public AudienceResolutionService(
            CampaignAudienceMemberRepository audienceRepository,
            AudienceEligibilityService eligibilityService,
            SegmentResolveRequester resolveRequester,
            @Value("${pos.marketing.preview.sample-size:10}") int sampleSize) {
        this.audienceRepository = audienceRepository;
        this.eligibilityService = eligibilityService;
        this.resolveRequester = resolveRequester;
        this.sampleSize = Math.clamp(sampleSize, 0, MAX_SAMPLE_SIZE);
    }

    /**
     * Build the preview for a campaign that already has a segment bound.
     *
     * @param channelsWithTemplate channels the campaign has a resolvable template for; a channel
     *     with reach but no template still cannot send, and the preview is where that is cheapest
     *     to notice
     * @param warnings the campaign's readiness problems, reported alongside the counts rather
     *     than thrown, because a preview is exactly when a marketer wants the whole list
     */
    @Transactional
    public @NonNull AudiencePreviewResponse preview(
            @NonNull Campaign campaign,
            @NonNull Set<CampaignChannel> channelsWithTemplate,
            @NonNull List<String> warnings) {
        UUID campaignId = campaign.getCampaignId();
        List<UUID> members = audienceRepository.findPartyIdsByCampaignId(campaignId);
        Instant resolvedAt = audienceRepository.findResolvedAt(campaignId).orElse(null);
        boolean truncated = audienceRepository.isTruncated(campaignId);

        // Ask for a fresher snapshot on the way out, but only while the audience can still
        // change: replacing it under a campaign that is already dispatching would change who
        // gets contacted partway through a send.
        if (campaign.getStatus().audienceIsMutable() && campaign.getSegmentId() != null) {
            resolveRequester.requestResolve(campaignId, campaign.getSegmentId());
        }

        List<AudiencePreviewResponse.ChannelPreview> channels = campaign.getChannels().stream()
                .map(channel -> channelPreview(channel, members, channelsWithTemplate.contains(channel)))
                .toList();

        return new AudiencePreviewResponse(
                campaignId,
                campaign.getSegmentId(),
                campaign.getAudienceType().name(),
                members.size(),
                resolvedAt,
                truncated,
                channels,
                warnings);
    }

    private AudiencePreviewResponse.ChannelPreview channelPreview(
            CampaignChannel channel, List<UUID> members, boolean templateAttached) {
        Map<UUID, AudienceEligibilityService.Decision> decisions = eligibilityService.decideAll(members, channel);
        long eligible = decisions.values().stream()
                .filter(AudienceEligibilityService.Decision::allowed)
                .count();
        return new AudiencePreviewResponse.ChannelPreview(
                channel.name(), members.size(), eligible, templateAttached, sample(decisions));
    }

    /**
     * A bounded sample of the decisions behind the count.
     *
     * <p>Refusals come first, because they are what explains the gap between targeted and
     * eligible — a marketer looking at 287 of 412 wants the reason codes for the missing 125,
     * not proof that the other 287 are fine. A campaign with no refusals fills the sample with
     * eligible members instead, so the sample is never empty when the audience is not.
     *
     * <p>The whole decision map is scanned rather than the first few entries: the refusals that
     * explain a gap are rarely at the front of a snapshot, and stopping early would report "no
     * refusals" for a campaign that has hundreds. The map is already in memory, so the scan
     * costs nothing beyond the walk.
     */
    private List<AudiencePreviewResponse.SampleRecipient> sample(
            Map<UUID, AudienceEligibilityService.Decision> decisions) {
        if (sampleSize == 0 || decisions.isEmpty()) {
            return List.of();
        }
        List<AudiencePreviewResponse.SampleRecipient> refused = new ArrayList<>();
        List<AudiencePreviewResponse.SampleRecipient> allowed = new ArrayList<>();
        for (Map.Entry<UUID, AudienceEligibilityService.Decision> entry : decisions.entrySet()) {
            AudienceEligibilityService.Decision decision = entry.getValue();
            List<AudiencePreviewResponse.SampleRecipient> bucket = decision.allowed() ? allowed : refused;
            if (bucket.size() < sampleSize) {
                bucket.add(new AudiencePreviewResponse.SampleRecipient(
                        entry.getKey(), decision.contactPartyId(), decision.allowed(), decision.reason()));
            }
        }
        List<AudiencePreviewResponse.SampleRecipient> sample = new ArrayList<>(refused);
        allowed.stream().limit((long) sampleSize - sample.size()).forEach(sample::add);
        return List.copyOf(sample);
    }
}

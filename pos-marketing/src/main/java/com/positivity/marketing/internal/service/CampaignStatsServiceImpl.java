package com.positivity.marketing.internal.service;

import com.positivity.marketing.internal.dto.CampaignStatsResponse;
import com.positivity.marketing.internal.dto.ProgramStatsResponse;
import com.positivity.marketing.internal.entity.Campaign;
import com.positivity.marketing.internal.enums.CampaignChannel;
import com.positivity.marketing.internal.enums.SendStatus;
import com.positivity.marketing.internal.exception.MarketingResourceNotFoundException;
import com.positivity.marketing.internal.repository.CampaignAttributionRepository;
import com.positivity.marketing.internal.repository.CampaignRepository;
import com.positivity.marketing.internal.repository.CampaignSendRepository;
import com.positivity.marketing.service.CampaignStatsService;
import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CampaignStatsServiceImpl implements CampaignStatsService {

    private final CampaignRepository campaignRepository;
    private final CampaignSendRepository sendRepository;
    private final CampaignAttributionRepository attributionRepository;

    @Override
    @Transactional(readOnly = true)
    public @NonNull CampaignStatsResponse campaignStats(@NonNull UUID campaignId) {
        Campaign campaign = campaignRepository
                .findById(campaignId)
                .orElseThrow(() -> new MarketingResourceNotFoundException("Campaign", campaignId));
        return buildStats(campaign);
    }

    @Override
    @Transactional(readOnly = true)
    public @NonNull ProgramStatsResponse programStats(@NonNull UUID campaignProgramId) {
        List<Campaign> arms = campaignRepository.findByCampaignProgramId(campaignProgramId);
        if (arms.isEmpty()) {
            throw new MarketingResourceNotFoundException("Campaign program", campaignProgramId);
        }
        return new ProgramStatsResponse(
                campaignProgramId, arms.stream().map(this::buildStats).toList());
    }

    private CampaignStatsResponse buildStats(Campaign campaign) {
        UUID campaignId = campaign.getCampaignId();
        // One grouped query rather than a count per (channel, status) pair — seven statuses times
        // two channels would otherwise be fourteen round trips for a single stats page.
        Map<CampaignChannel, Map<SendStatus, Long>> counts = new EnumMap<>(CampaignChannel.class);
        for (Object[] row : sendRepository.countByChannelAndStatus(campaignId)) {
            CampaignChannel channel = (CampaignChannel) row[0];
            SendStatus status = (SendStatus) row[1];
            long count = ((Number) row[2]).longValue();
            counts.computeIfAbsent(channel, key -> new EnumMap<>(SendStatus.class))
                    .merge(status, count, Long::sum);
        }

        List<CampaignStatsResponse.ChannelFunnel> funnels = campaign.getChannels().stream()
                .map(channel -> funnel(channel, counts.getOrDefault(channel, Map.of())))
                .toList();

        BigDecimal attributedDiscount = attributionRepository.sumDiscountByCampaignId(campaignId);
        return new CampaignStatsResponse(
                campaignId,
                campaign.getCode(),
                campaign.getAudienceType().name(),
                campaign.getCampaignProgramId(),
                funnels,
                attributionRepository.countByCampaignId(campaignId),
                attributedDiscount != null ? attributedDiscount : BigDecimal.ZERO);
    }

    private static CampaignStatsResponse.ChannelFunnel funnel(
            CampaignChannel channel, Map<SendStatus, Long> statusCounts) {
        long pending = count(statusCounts, SendStatus.PENDING);
        long suppressed = count(statusCounts, SendStatus.SUPPRESSED);
        long sent = count(statusCounts, SendStatus.SENT);
        long delivered = count(statusCounts, SendStatus.DELIVERED);
        long bounced = count(statusCounts, SendStatus.BOUNCED);
        long complained = count(statusCounts, SendStatus.COMPLAINED);
        long failed = count(statusCounts, SendStatus.FAILED);
        // "Sent" is cumulative down the funnel: a delivered, bounced, or complained message was
        // also sent, so reporting only the rows still sitting in SENT would understate reach.
        long sentTotal = sent + delivered + bounced + complained;
        long targeted = sentTotal + pending + suppressed + failed;
        return new CampaignStatsResponse.ChannelFunnel(
                channel.name(), targeted, suppressed, sentTotal, delivered, bounced, complained, failed);
    }

    private static long count(Map<SendStatus, Long> counts, SendStatus status) {
        return counts.getOrDefault(status, 0L);
    }
}

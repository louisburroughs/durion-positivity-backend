package com.positivity.marketing.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.when;

import com.positivity.marketing.internal.dto.CampaignStatsResponse;
import com.positivity.marketing.internal.dto.ProgramStatsResponse;
import com.positivity.marketing.internal.entity.Campaign;
import com.positivity.marketing.internal.enums.AudienceType;
import com.positivity.marketing.internal.enums.CampaignChannel;
import com.positivity.marketing.internal.enums.SendStatus;
import com.positivity.marketing.internal.exception.MarketingResourceNotFoundException;
import com.positivity.marketing.internal.repository.CampaignAttributionRepository;
import com.positivity.marketing.internal.repository.CampaignRepository;
import com.positivity.marketing.internal.repository.CampaignSendRepository;
import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link CampaignStatsServiceImpl}.
 *
 * <p>
 * These numbers are what a marketer decides next quarter's spend on, so the
 * arithmetic is the contract:
 *
 * <ul>
 * <li><b>"Sent" is cumulative down the funnel.</b> A delivered, bounced, or
 * complained message was also sent; counting only rows still sitting in
 * {@code SENT} would understate reach by exactly the messages that worked.</li>
 * <li><b>Every bound channel appears, even with no rows.</b> A channel silently
 * missing from the funnel reads as "not configured" rather than "nothing has
 * gone out yet".</li>
 * <li><b>A campaign with no attributed redemptions reports zero, not null</b> —
 * {@code SUM} over an empty set is null in SQL and would serialize as a missing
 * figure.</li>
 * <li><b>A program with no arms is a 404</b>, not an empty comparison; the
 * whole point of the rollup is the side-by-side.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CampaignStatsServiceImpl — reach funnel and program rollup")
class CampaignStatsServiceImplTest {

    private static final UUID CAMPAIGN_ID = UUID.randomUUID();
    private static final UUID PROGRAM_ID = UUID.randomUUID();

    @Mock
    private CampaignRepository campaignRepository;

    @Mock
    private CampaignSendRepository sendRepository;

    @Mock
    private CampaignAttributionRepository attributionRepository;

    @InjectMocks
    private CampaignStatsServiceImpl service;

    private static Campaign campaign(UUID campaignId, String code, CampaignChannel... channels) {
        Set<CampaignChannel> channelSet = new LinkedHashSet<>(List.of(channels));
        return Campaign.builder()
                .campaignId(campaignId)
                .code(code)
                .audienceType(AudienceType.INDIVIDUAL)
                .campaignProgramId(PROGRAM_ID)
                .channels(channelSet)
                .build();
    }

    private static Object[] row(CampaignChannel channel, SendStatus status, long count) {
        return new Object[] {channel, status, count};
    }

    @Nested
    @DisplayName("campaignStats")
    class CampaignStats {

        @Test
        @DisplayName("rejects a campaign that does not exist")
        void unknownCampaign() {
            when(campaignRepository.findById(CAMPAIGN_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.campaignStats(CAMPAIGN_ID))
                    .isInstanceOf(MarketingResourceNotFoundException.class);
        }

        @Test
        @DisplayName("rolls delivered, bounced, and complained back up into the sent total")
        void sentIsCumulative() {
            when(campaignRepository.findById(CAMPAIGN_ID))
                    .thenReturn(Optional.of(campaign(CAMPAIGN_ID, "SPRING", CampaignChannel.EMAIL)));
            when(sendRepository.countByChannelAndStatus(CAMPAIGN_ID))
                    .thenReturn(List.<Object[]>of(
                            row(CampaignChannel.EMAIL, SendStatus.PENDING, 5),
                            row(CampaignChannel.EMAIL, SendStatus.SUPPRESSED, 12),
                            row(CampaignChannel.EMAIL, SendStatus.SENT, 3),
                            row(CampaignChannel.EMAIL, SendStatus.DELIVERED, 40),
                            row(CampaignChannel.EMAIL, SendStatus.BOUNCED, 2),
                            row(CampaignChannel.EMAIL, SendStatus.COMPLAINED, 1),
                            row(CampaignChannel.EMAIL, SendStatus.FAILED, 7)));
            when(attributionRepository.sumDiscountByCampaignId(CAMPAIGN_ID)).thenReturn(new BigDecimal("125.50"));
            when(attributionRepository.countByCampaignId(CAMPAIGN_ID)).thenReturn(9L);

            CampaignStatsResponse stats = service.campaignStats(CAMPAIGN_ID);

            assertThat(stats.campaignId()).isEqualTo(CAMPAIGN_ID);
            assertThat(stats.code()).isEqualTo("SPRING");
            assertThat(stats.audienceType()).isEqualTo("INDIVIDUAL");
            assertThat(stats.campaignProgramId()).isEqualTo(PROGRAM_ID);
            assertThat(stats.redeemed()).isEqualTo(9);
            assertThat(stats.attributedDiscount()).isEqualByComparingTo("125.50");

            CampaignStatsResponse.ChannelFunnel email = stats.channels().getFirst();
            assertThat(email.channel()).isEqualTo("EMAIL");
            // 3 SENT + 40 DELIVERED + 2 BOUNCED + 1 COMPLAINED
            assertThat(email.sent()).isEqualTo(46);
            // sent total + 5 PENDING + 12 SUPPRESSED + 7 FAILED
            assertThat(email.targeted()).isEqualTo(70);
            assertThat(email.suppressed()).isEqualTo(12);
            assertThat(email.delivered()).isEqualTo(40);
            assertThat(email.bounced()).isEqualTo(2);
            assertThat(email.complained()).isEqualTo(1);
            assertThat(email.failed()).isEqualTo(7);
        }

        @Test
        @DisplayName("reports a zeroed funnel for a bound channel that has no rows yet")
        void channelWithNoRowsStillAppears() {
            when(campaignRepository.findById(CAMPAIGN_ID))
                    .thenReturn(
                            Optional.of(campaign(CAMPAIGN_ID, "SPRING", CampaignChannel.EMAIL, CampaignChannel.SMS)));
            when(sendRepository.countByChannelAndStatus(CAMPAIGN_ID))
                    .thenReturn(List.<Object[]>of(row(CampaignChannel.EMAIL, SendStatus.SENT, 4)));
            when(attributionRepository.sumDiscountByCampaignId(CAMPAIGN_ID)).thenReturn(BigDecimal.TEN);

            CampaignStatsResponse stats = service.campaignStats(CAMPAIGN_ID);

            assertThat(stats.channels())
                    .extracting(
                            CampaignStatsResponse.ChannelFunnel::channel,
                            CampaignStatsResponse.ChannelFunnel::targeted,
                            CampaignStatsResponse.ChannelFunnel::sent)
                    .containsExactlyInAnyOrder(tuple("EMAIL", 4L, 4L), tuple("SMS", 0L, 0L));
        }

        @Test
        @DisplayName("sums repeated channel-status rows rather than letting the last one win")
        void mergesDuplicateRows() {
            when(campaignRepository.findById(CAMPAIGN_ID))
                    .thenReturn(Optional.of(campaign(CAMPAIGN_ID, "SPRING", CampaignChannel.SMS)));
            when(sendRepository.countByChannelAndStatus(CAMPAIGN_ID))
                    .thenReturn(List.<Object[]>of(
                            row(CampaignChannel.SMS, SendStatus.DELIVERED, 6),
                            row(CampaignChannel.SMS, SendStatus.DELIVERED, 4)));
            when(attributionRepository.sumDiscountByCampaignId(CAMPAIGN_ID)).thenReturn(BigDecimal.ZERO);

            assertThat(service.campaignStats(CAMPAIGN_ID).channels().getFirst().delivered())
                    .isEqualTo(10);
        }

        @Test
        @DisplayName("reports zero attributed discount when nothing has been redeemed")
        void nullDiscountSumBecomesZero() {
            when(campaignRepository.findById(CAMPAIGN_ID))
                    .thenReturn(Optional.of(campaign(CAMPAIGN_ID, "SPRING", CampaignChannel.EMAIL)));
            when(sendRepository.countByChannelAndStatus(CAMPAIGN_ID)).thenReturn(List.of());
            when(attributionRepository.sumDiscountByCampaignId(CAMPAIGN_ID)).thenReturn(null);

            assertThat(service.campaignStats(CAMPAIGN_ID).attributedDiscount()).isEqualByComparingTo(BigDecimal.ZERO);
        }
    }

    @Nested
    @DisplayName("programStats")
    class ProgramStats {

        @Test
        @DisplayName("rejects a program with no campaigns")
        void unknownProgram() {
            when(campaignRepository.findByCampaignProgramId(PROGRAM_ID)).thenReturn(List.of());

            assertThatThrownBy(() -> service.programStats(PROGRAM_ID))
                    .isInstanceOf(MarketingResourceNotFoundException.class);
        }

        @Test
        @DisplayName("returns one funnel set per arm so the arms can be compared")
        void oneEntryPerArm() {
            UUID commercialArm = UUID.randomUUID();
            Campaign individual = campaign(CAMPAIGN_ID, "SPRING-IND", CampaignChannel.EMAIL);
            Campaign commercial = campaign(commercialArm, "SPRING-COM", CampaignChannel.EMAIL);
            commercial.setAudienceType(AudienceType.COMMERCIAL);
            when(campaignRepository.findByCampaignProgramId(PROGRAM_ID)).thenReturn(List.of(individual, commercial));
            when(sendRepository.countByChannelAndStatus(CAMPAIGN_ID))
                    .thenReturn(List.<Object[]>of(row(CampaignChannel.EMAIL, SendStatus.DELIVERED, 100)));
            when(sendRepository.countByChannelAndStatus(commercialArm))
                    .thenReturn(List.<Object[]>of(row(CampaignChannel.EMAIL, SendStatus.DELIVERED, 3)));
            when(attributionRepository.sumDiscountByCampaignId(CAMPAIGN_ID)).thenReturn(BigDecimal.ONE);
            when(attributionRepository.sumDiscountByCampaignId(commercialArm)).thenReturn(BigDecimal.ONE);

            ProgramStatsResponse stats = service.programStats(PROGRAM_ID);

            assertThat(stats.campaignProgramId()).isEqualTo(PROGRAM_ID);
            assertThat(stats.arms())
                    .extracting(CampaignStatsResponse::code, CampaignStatsResponse::audienceType)
                    .containsExactly(tuple("SPRING-IND", "INDIVIDUAL"), tuple("SPRING-COM", "COMMERCIAL"));
            assertThat(stats.arms().getFirst().channels().getFirst().delivered())
                    .isEqualTo(100);
        }
    }
}

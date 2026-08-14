package com.positivity.marketing.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.marketing.internal.dto.AudiencePreviewResponse;
import com.positivity.marketing.internal.entity.Campaign;
import com.positivity.marketing.internal.enums.AudienceType;
import com.positivity.marketing.internal.enums.CampaignChannel;
import com.positivity.marketing.internal.enums.CampaignStatus;
import com.positivity.marketing.internal.repository.CampaignAudienceMemberRepository;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link AudienceResolutionService}.
 *
 * <p>What is worth pinning here is everything a marketer would misread if it were wrong:
 *
 * <ul>
 *   <li><b>The snapshot's age and truncation travel with the counts.</b> A count that is
 *       quietly short is indistinguishable from a small audience unless the preview says so.
 *   <li><b>A refresh is requested only while the audience can still change.</b> Replacing the
 *       audience of a campaign that is already dispatching would change who gets contacted
 *       partway through a send.
 *   <li><b>The sample leads with refusals.</b> They are what explains the gap between targeted
 *       and eligible; a sample of happy recipients answers a question nobody asked.
 *   <li><b>The sample carries identifiers and reason codes only.</b> This module replicates no
 *       names or addresses, so a preview cannot leak contact data.
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AudienceResolutionService — audience preview")
class AudienceResolutionServiceTest {

    private static final UUID CAMPAIGN_ID = UUID.randomUUID();
    private static final UUID SEGMENT_ID = UUID.randomUUID();
    private static final Instant RESOLVED_AT = Instant.parse("2026-08-11T11:00:00Z");

    @Mock
    private CampaignAudienceMemberRepository audienceRepository;

    @Mock
    private AudienceEligibilityService eligibilityService;

    @Mock
    private SegmentResolveRequester resolveRequester;

    private AudienceResolutionService service(int sampleSize) {
        return new AudienceResolutionService(audienceRepository, eligibilityService, resolveRequester, sampleSize);
    }

    private static Campaign campaign(CampaignStatus status, CampaignChannel... channels) {
        return Campaign.builder()
                .campaignId(CAMPAIGN_ID)
                .code("SPRING24")
                .audienceType(AudienceType.COMMERCIAL)
                .status(status)
                .segmentId(SEGMENT_ID)
                .channels(new LinkedHashSet<>(List.of(channels)))
                .build();
    }

    /** A snapshot of {@code allowed} reachable parties followed by {@code refused} opted-out ones. */
    private Map<UUID, AudienceEligibilityService.Decision> snapshot(List<UUID> members, int allowed) {
        Map<UUID, AudienceEligibilityService.Decision> decisions = new LinkedHashMap<>();
        for (int i = 0; i < members.size(); i++) {
            decisions.put(
                    members.get(i),
                    i < allowed
                            ? new AudienceEligibilityService.Decision(true, "OPT_IN", UUID.randomUUID())
                            : new AudienceEligibilityService.Decision(false, "CONSENT_OPT_OUT"));
        }
        return decisions;
    }

    private static List<UUID> members(int count) {
        return java.util.stream.Stream.generate(UUID::randomUUID).limit(count).toList();
    }

    @Nested
    @DisplayName("counts")
    class Counts {

        @Test
        @DisplayName("reports the snapshot's size, age and truncation alongside the per-channel counts")
        void reportsSnapshotWithItsAge() {
            List<UUID> members = members(4);
            when(audienceRepository.findPartyIdsByCampaignId(CAMPAIGN_ID)).thenReturn(members);
            when(audienceRepository.findResolvedAt(CAMPAIGN_ID)).thenReturn(Optional.of(RESOLVED_AT));
            when(audienceRepository.isTruncated(CAMPAIGN_ID)).thenReturn(true);
            when(eligibilityService.decideAll(anyCollection(), any())).thenReturn(snapshot(members, 3));

            AudiencePreviewResponse preview =
                    service(10).preview(campaign(CampaignStatus.DRAFT, CampaignChannel.EMAIL), Set.of(), List.of());

            assertThat(preview.segmentMatched()).isEqualTo(4);
            assertThat(preview.resolvedAt()).isEqualTo(RESOLVED_AT);
            // Without this the marketer reads a cut-off count as a small audience.
            assertThat(preview.truncated()).isTrue();
            assertThat(preview.channels()).singleElement().satisfies(channel -> {
                assertThat(channel.channel()).isEqualTo("EMAIL");
                assertThat(channel.targeted()).isEqualTo(4);
                assertThat(channel.eligible()).isEqualTo(3);
            });
        }

        @Test
        @DisplayName("reports each channel separately, with whether it has a template")
        void reportsEachChannel() {
            List<UUID> members = members(2);
            when(audienceRepository.findPartyIdsByCampaignId(CAMPAIGN_ID)).thenReturn(members);
            when(audienceRepository.findResolvedAt(CAMPAIGN_ID)).thenReturn(Optional.empty());
            when(eligibilityService.decideAll(anyCollection(), any())).thenReturn(snapshot(members, 1));

            AudiencePreviewResponse preview = service(10)
                    .preview(
                            campaign(CampaignStatus.DRAFT, CampaignChannel.EMAIL, CampaignChannel.SMS),
                            Set.of(CampaignChannel.EMAIL),
                            List.of());

            assertThat(preview.channels())
                    .extracting(
                            AudiencePreviewResponse.ChannelPreview::channel,
                            AudiencePreviewResponse.ChannelPreview::templateAttached)
                    .containsExactly(
                            org.assertj.core.api.Assertions.tuple("EMAIL", true),
                            org.assertj.core.api.Assertions.tuple("SMS", false));
        }

        @Test
        @DisplayName("an empty snapshot previews as zero rather than failing")
        void emptySnapshot() {
            when(audienceRepository.findPartyIdsByCampaignId(CAMPAIGN_ID)).thenReturn(List.of());
            when(audienceRepository.findResolvedAt(CAMPAIGN_ID)).thenReturn(Optional.empty());
            when(eligibilityService.decideAll(anyCollection(), any())).thenReturn(Map.of());

            AudiencePreviewResponse preview =
                    service(10).preview(campaign(CampaignStatus.DRAFT, CampaignChannel.EMAIL), Set.of(), List.of());

            assertThat(preview.segmentMatched()).isZero();
            assertThat(preview.channels()).singleElement().satisfies(channel -> {
                assertThat(channel.eligible()).isZero();
                assertThat(channel.sample()).isEmpty();
            });
        }

        @Test
        @DisplayName("carries the readiness problems through as warnings")
        void carriesWarnings() {
            when(audienceRepository.findPartyIdsByCampaignId(CAMPAIGN_ID)).thenReturn(List.of());
            when(audienceRepository.findResolvedAt(CAMPAIGN_ID)).thenReturn(Optional.empty());

            AudiencePreviewResponse preview = service(10)
                    .preview(campaign(CampaignStatus.DRAFT), Set.of(), List.of("promotion offer is EXPIRED"));

            assertThat(preview.warnings()).containsExactly("promotion offer is EXPIRED");
        }
    }

    @Nested
    @DisplayName("refresh request")
    class RefreshRequest {

        @Test
        @DisplayName("asks the CRM for a fresher snapshot while the audience can still change")
        void requestsRefreshWhileMutable() {
            when(audienceRepository.findPartyIdsByCampaignId(CAMPAIGN_ID)).thenReturn(List.of());
            when(audienceRepository.findResolvedAt(CAMPAIGN_ID)).thenReturn(Optional.empty());

            service(10).preview(campaign(CampaignStatus.DRAFT), Set.of(), List.of());

            verify(resolveRequester).requestResolve(CAMPAIGN_ID, SEGMENT_ID);
        }

        @Test
        @DisplayName("leaves a frozen audience alone")
        void doesNotRefreshOnceFrozen() {
            when(audienceRepository.findPartyIdsByCampaignId(CAMPAIGN_ID)).thenReturn(List.of());
            when(audienceRepository.findResolvedAt(CAMPAIGN_ID)).thenReturn(Optional.empty());

            // Replacing the audience mid-send would change who gets contacted partway through.
            service(10).preview(campaign(CampaignStatus.SENT), Set.of(), List.of());

            verify(resolveRequester, never()).requestResolve(any(), any());
        }
    }

    @Nested
    @DisplayName("sample")
    class Sample {

        @Test
        @DisplayName("leads with refusals, because they are what explains the gap")
        void refusalsComeFirst() {
            List<UUID> members = members(6);
            when(audienceRepository.findPartyIdsByCampaignId(CAMPAIGN_ID)).thenReturn(members);
            when(audienceRepository.findResolvedAt(CAMPAIGN_ID)).thenReturn(Optional.empty());
            // The first four are reachable; the refusals sit at the back of the snapshot.
            when(eligibilityService.decideAll(anyCollection(), any())).thenReturn(snapshot(members, 4));

            AudiencePreviewResponse preview =
                    service(3).preview(campaign(CampaignStatus.DRAFT, CampaignChannel.EMAIL), Set.of(), List.of());

            List<AudiencePreviewResponse.SampleRecipient> sample =
                    preview.channels().getFirst().sample();
            assertThat(sample).hasSize(3);
            assertThat(sample.subList(0, 2))
                    .allSatisfy(entry -> assertThat(entry.eligible()).isFalse());
            assertThat(sample.subList(0, 2))
                    .allSatisfy(entry -> assertThat(entry.reason()).isEqualTo("CONSENT_OPT_OUT"));
            // A refusal-only sample would hide that anyone is reachable at all.
            assertThat(sample.getLast().eligible()).isTrue();
        }

        @Test
        @DisplayName("falls back to reachable members when nobody was refused")
        void allEligibleStillSamples() {
            List<UUID> members = members(3);
            when(audienceRepository.findPartyIdsByCampaignId(CAMPAIGN_ID)).thenReturn(members);
            when(audienceRepository.findResolvedAt(CAMPAIGN_ID)).thenReturn(Optional.empty());
            when(eligibilityService.decideAll(anyCollection(), any())).thenReturn(snapshot(members, 3));

            AudiencePreviewResponse preview =
                    service(10).preview(campaign(CampaignStatus.DRAFT, CampaignChannel.EMAIL), Set.of(), List.of());

            assertThat(preview.channels().getFirst().sample())
                    .hasSize(3)
                    .allSatisfy(entry -> assertThat(entry.eligible()).isTrue());
        }

        @Test
        @DisplayName("names the contact who would actually be addressed within the party")
        void reportsTheResolvedContact() {
            List<UUID> members = members(1);
            UUID contact = UUID.randomUUID();
            when(audienceRepository.findPartyIdsByCampaignId(CAMPAIGN_ID)).thenReturn(members);
            when(audienceRepository.findResolvedAt(CAMPAIGN_ID)).thenReturn(Optional.empty());
            when(eligibilityService.decideAll(anyCollection(), any()))
                    .thenReturn(Map.of(
                            members.getFirst(), new AudienceEligibilityService.Decision(true, "OPT_IN", contact)));

            AudiencePreviewResponse preview =
                    service(10).preview(campaign(CampaignStatus.DRAFT, CampaignChannel.EMAIL), Set.of(), List.of());

            // For a commercial account this is the contact the CRM designates to speak for it;
            // for an individual it is the person themselves.
            assertThat(preview.channels().getFirst().sample()).singleElement().satisfies(entry -> {
                assertThat(entry.partyId()).isEqualTo(members.getFirst());
                assertThat(entry.contactPartyId()).isEqualTo(contact);
            });
        }

        @Test
        @DisplayName("a configured size of zero suppresses the sample entirely")
        void zeroSampleSize() {
            List<UUID> members = members(3);
            when(audienceRepository.findPartyIdsByCampaignId(CAMPAIGN_ID)).thenReturn(members);
            when(audienceRepository.findResolvedAt(CAMPAIGN_ID)).thenReturn(Optional.empty());
            when(eligibilityService.decideAll(anyCollection(), any())).thenReturn(snapshot(members, 0));

            AudiencePreviewResponse preview =
                    service(0).preview(campaign(CampaignStatus.DRAFT, CampaignChannel.EMAIL), Set.of(), List.of());

            assertThat(preview.channels().getFirst().sample()).isEmpty();
        }

        @Test
        @DisplayName("an oversized configured value is clamped rather than honoured")
        void sampleSizeIsClamped() {
            List<UUID> members = members(100);
            when(audienceRepository.findPartyIdsByCampaignId(CAMPAIGN_ID)).thenReturn(members);
            when(audienceRepository.findResolvedAt(CAMPAIGN_ID)).thenReturn(Optional.empty());
            when(eligibilityService.decideAll(anyCollection(), any())).thenReturn(snapshot(members, 0));

            // A preview must not become a bulk export of the audience.
            AudiencePreviewResponse preview =
                    service(5_000).preview(campaign(CampaignStatus.DRAFT, CampaignChannel.EMAIL), Set.of(), List.of());

            assertThat(preview.channels().getFirst().sample()).hasSize(50);
        }
    }
}

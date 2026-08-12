package com.positivity.marketing.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.marketing.internal.dto.AudiencePreviewResponse;
import com.positivity.marketing.internal.dto.CampaignResponse;
import com.positivity.marketing.internal.dto.UpsertCampaignRequest;
import com.positivity.marketing.internal.entity.Campaign;
import com.positivity.marketing.internal.entity.MessageTemplate;
import com.positivity.marketing.internal.entity.SegmentReplica;
import com.positivity.marketing.internal.enums.AudienceType;
import com.positivity.marketing.internal.enums.CampaignChannel;
import com.positivity.marketing.internal.enums.CampaignStatus;
import com.positivity.marketing.internal.enums.ScheduleType;
import com.positivity.marketing.internal.exception.MarketingDuplicateResourceException;
import com.positivity.marketing.internal.exception.MarketingResourceNotFoundException;
import com.positivity.marketing.internal.exception.MarketingUnprocessableEntityException;
import com.positivity.marketing.internal.repository.CampaignAudienceMemberRepository;
import com.positivity.marketing.internal.repository.CampaignRepository;
import com.positivity.marketing.internal.repository.MessageTemplateRepository;
import com.positivity.marketing.internal.repository.SegmentReplicaRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link CampaignServiceImpl}.
 *
 * <p>
 * A campaign that goes out wrong cannot be recalled, so most of the behaviour
 * worth pinning here is what stops one going out at all:
 *
 * <ul>
 * <li><b>Readiness problems are gathered, not thrown one at a time.</b> A
 * marketer fixing a campaign wants the whole list rather than a game of
 * whack-a-mole, so {@code schedule} reports every problem at once.</li>
 * <li><b>A dangling template is as broken as a missing one.</b> The template id
 * is verified to still resolve, not merely to be non-null.</li>
 * <li><b>Audience type is immutable and must match the bound segment.</b> A
 * commercial campaign on an individual segment would render templates written
 * for organizations against people, for every recipient.</li>
 * <li><b>Editing stops once recipients may have been contacted.</b> Status
 * governs editability, and resume returns to SCHEDULED rather than SENDING so
 * the worker decides when dispatch restarts.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CampaignServiceImpl — campaign lifecycle and readiness")
class CampaignServiceImplTest {

    private static final UUID CAMPAIGN_ID = UUID.randomUUID();
    private static final UUID SEGMENT_ID = UUID.randomUUID();
    private static final UUID EMAIL_TEMPLATE_ID = UUID.randomUUID();

    @Mock
    private CampaignRepository campaignRepository;

    @Mock
    private MessageTemplateRepository templateRepository;

    @Mock
    private SegmentReplicaRepository segmentReplicaRepository;

    @Mock
    private CampaignAudienceMemberRepository audienceRepository;

    @Mock
    private AudienceEligibilityService eligibilityService;

    @Mock
    private SegmentResolveRequester resolveRequester;

    @Mock
    private MarketingFactPublisher factPublisher;

    @InjectMocks
    private CampaignServiceImpl sut;

    private static Campaign campaign(CampaignStatus status) {
        return Campaign.builder()
                .campaignId(CAMPAIGN_ID)
                .code("SPRING24")
                .name("Spring service push")
                .audienceType(AudienceType.COMMERCIAL)
                .status(status)
                .channels(new java.util.HashSet<>(Set.of(CampaignChannel.EMAIL)))
                .segmentId(SEGMENT_ID)
                .scheduleType(ScheduleType.IMMEDIATE)
                .emailTemplateId(EMAIL_TEMPLATE_ID)
                .build();
    }

    private static UpsertCampaignRequest request() {
        return new UpsertCampaignRequest(
                "SPRING24",
                "Spring service push",
                "desc",
                AudienceType.COMMERCIAL,
                null,
                Set.of(CampaignChannel.EMAIL),
                SEGMENT_ID,
                null,
                null,
                null,
                null,
                ScheduleType.IMMEDIATE,
                null,
                EMAIL_TEMPLATE_ID,
                null);
    }

    private static SegmentReplica segmentReplica(boolean active, String audienceType) {
        return SegmentReplica.builder()
                .segmentId(SEGMENT_ID)
                .name("Fleet accounts")
                .audienceType(audienceType)
                .active(active)
                .build();
    }

    /** Segment replica present and matching, email template resolvable. */
    private void campaignIsReady() {
        lenient()
                .when(segmentReplicaRepository.findById(SEGMENT_ID))
                .thenReturn(Optional.of(segmentReplica(true, "COMMERCIAL")));
        lenient().when(templateRepository.findById(EMAIL_TEMPLATE_ID)).thenReturn(Optional.of(new MessageTemplate()));
    }

    @Nested
    @DisplayName("create")
    class Create {

        @Test
        @DisplayName("persists a new campaign in DRAFT with a trimmed code")
        void create_persistsDraft() {
            when(campaignRepository.existsByCodeIgnoreCase("SPRING24")).thenReturn(false);
            when(campaignRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            CampaignResponse response = sut.create(request());

            assertThat(response.status()).isEqualTo("DRAFT");
            assertThat(response.code()).isEqualTo("SPRING24");
        }

        @Test
        @DisplayName("rejects a duplicate code case-insensitively")
        void create_whenCodeTaken_throws() {
            when(campaignRepository.existsByCodeIgnoreCase("SPRING24")).thenReturn(true);

            assertThatThrownBy(() -> sut.create(request())).isInstanceOf(MarketingDuplicateResourceException.class);
            verify(campaignRepository, never()).save(any());
        }

        @Test
        @DisplayName("rejects a SCHEDULED campaign with no scheduledAt — it would never dispatch")
        void create_scheduledWithoutTime_throws() {
            UpsertCampaignRequest invalid = new UpsertCampaignRequest(
                    "SPRING24",
                    "n",
                    null,
                    AudienceType.COMMERCIAL,
                    null,
                    Set.of(CampaignChannel.EMAIL),
                    SEGMENT_ID,
                    null,
                    null,
                    null,
                    null,
                    ScheduleType.SCHEDULED,
                    null,
                    EMAIL_TEMPLATE_ID,
                    null);
            when(campaignRepository.existsByCodeIgnoreCase(any())).thenReturn(false);

            assertThatThrownBy(() -> sut.create(invalid))
                    .isInstanceOf(MarketingUnprocessableEntityException.class)
                    .hasMessageContaining("scheduledAt");
        }

        @Test
        @DisplayName("rejects a send window that ends before it starts")
        void create_invertedWindow_throws() {
            Instant start = Instant.parse("2026-08-11T12:00:00Z");
            UpsertCampaignRequest invalid = new UpsertCampaignRequest(
                    "SPRING24",
                    "n",
                    null,
                    AudienceType.COMMERCIAL,
                    null,
                    Set.of(CampaignChannel.EMAIL),
                    SEGMENT_ID,
                    null,
                    null,
                    start,
                    start.minusSeconds(60),
                    ScheduleType.IMMEDIATE,
                    null,
                    EMAIL_TEMPLATE_ID,
                    null);
            when(campaignRepository.existsByCodeIgnoreCase(any())).thenReturn(false);

            assertThatThrownBy(() -> sut.create(invalid))
                    .isInstanceOf(MarketingUnprocessableEntityException.class)
                    .hasMessageContaining("windowEnd");
        }

        @Test
        @DisplayName("defaults the schedule type to IMMEDIATE when the request omits it")
        void create_whenScheduleTypeOmitted_defaultsImmediate() {
            UpsertCampaignRequest withoutType = new UpsertCampaignRequest(
                    "SPRING24",
                    "n",
                    null,
                    AudienceType.COMMERCIAL,
                    null,
                    Set.of(CampaignChannel.EMAIL),
                    SEGMENT_ID,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    EMAIL_TEMPLATE_ID,
                    null);
            when(campaignRepository.existsByCodeIgnoreCase(any())).thenReturn(false);
            when(campaignRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            assertThat(sut.create(withoutType).scheduleType()).isEqualTo("IMMEDIATE");
        }
    }

    @Nested
    @DisplayName("update")
    class Update {

        @Test
        @DisplayName("applies the changes to an editable campaign")
        void update_appliesChanges() {
            Campaign existing = campaign(CampaignStatus.DRAFT);
            when(campaignRepository.findById(CAMPAIGN_ID)).thenReturn(Optional.of(existing));
            when(campaignRepository.findByCodeIgnoreCase(any())).thenReturn(Optional.empty());
            when(campaignRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            sut.update(CAMPAIGN_ID, request());

            assertThat(existing.getName()).isEqualTo("Spring service push");
        }

        @ParameterizedTest(name = "a {0} campaign cannot be edited")
        @EnumSource(
                value = CampaignStatus.class,
                names = {"SENDING", "SENT", "CANCELLED", "CLOSED"})
        @DisplayName("refuses to edit once recipients may already have been contacted")
        void update_whenNotEditable_throws(CampaignStatus status) {
            when(campaignRepository.findById(CAMPAIGN_ID)).thenReturn(Optional.of(campaign(status)));

            assertThatThrownBy(() -> sut.update(CAMPAIGN_ID, request()))
                    .isInstanceOf(MarketingUnprocessableEntityException.class);
            verify(campaignRepository, never()).save(any());
        }

        @Test
        @DisplayName("refuses to change audience type, which its segment and templates are bound to")
        void update_whenAudienceTypeChanges_throws() {
            when(campaignRepository.findById(CAMPAIGN_ID)).thenReturn(Optional.of(campaign(CampaignStatus.DRAFT)));
            UpsertCampaignRequest switched = new UpsertCampaignRequest(
                    "SPRING24",
                    "n",
                    null,
                    AudienceType.INDIVIDUAL,
                    null,
                    Set.of(CampaignChannel.EMAIL),
                    SEGMENT_ID,
                    null,
                    null,
                    null,
                    null,
                    ScheduleType.IMMEDIATE,
                    null,
                    EMAIL_TEMPLATE_ID,
                    null);

            assertThatThrownBy(() -> sut.update(CAMPAIGN_ID, switched))
                    .isInstanceOf(MarketingUnprocessableEntityException.class)
                    .hasMessageContaining("immutable");
        }

        @Test
        @DisplayName("rejects a rename onto another campaign's code")
        void update_whenCodeTakenByOther_throws() {
            Campaign other = campaign(CampaignStatus.DRAFT);
            other.setCampaignId(UUID.randomUUID());
            when(campaignRepository.findById(CAMPAIGN_ID)).thenReturn(Optional.of(campaign(CampaignStatus.DRAFT)));
            when(campaignRepository.findByCodeIgnoreCase("SPRING24")).thenReturn(Optional.of(other));

            assertThatThrownBy(() -> sut.update(CAMPAIGN_ID, request()))
                    .isInstanceOf(MarketingDuplicateResourceException.class);
        }

        @Test
        @DisplayName("throws 404 for an unknown campaign")
        void update_whenAbsent_throws() {
            when(campaignRepository.findById(CAMPAIGN_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> sut.update(CAMPAIGN_ID, request()))
                    .isInstanceOf(MarketingResourceNotFoundException.class);
        }
    }

    @Nested
    @DisplayName("schedule readiness")
    class ScheduleReadiness {

        @Test
        @DisplayName("schedules a ready campaign and publishes the fact")
        void schedule_whenReady_transitionsAndPublishes() {
            Campaign existing = campaign(CampaignStatus.DRAFT);
            when(campaignRepository.findById(CAMPAIGN_ID)).thenReturn(Optional.of(existing));
            when(campaignRepository.save(any())).thenAnswer(i -> i.getArgument(0));
            campaignIsReady();

            assertThat(sut.schedule(CAMPAIGN_ID).status()).isEqualTo("SCHEDULED");
            verify(factPublisher).campaignScheduled(existing);
        }

        @Test
        @DisplayName("reports every readiness problem at once rather than one at a time")
        void schedule_gathersAllProblems() {
            Campaign broken = campaign(CampaignStatus.DRAFT);
            broken.setChannels(new java.util.HashSet<>());
            broken.setSegmentId(null);
            when(campaignRepository.findById(CAMPAIGN_ID)).thenReturn(Optional.of(broken));

            // A marketer fixing a campaign wants the whole list, not whack-a-mole.
            assertThatThrownBy(() -> sut.schedule(CAMPAIGN_ID))
                    .isInstanceOf(MarketingUnprocessableEntityException.class)
                    .hasMessageContaining("no delivery channel selected")
                    .hasMessageContaining("no segment bound");
            verify(factPublisher, never()).campaignScheduled(any());
        }

        @Test
        @DisplayName("refuses to schedule against a segment this module has not replicated yet")
        void schedule_whenSegmentUnknown_reportsProblem() {
            when(campaignRepository.findById(CAMPAIGN_ID)).thenReturn(Optional.of(campaign(CampaignStatus.DRAFT)));
            when(segmentReplicaRepository.findById(SEGMENT_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> sut.schedule(CAMPAIGN_ID)).hasMessageContaining("is not known to this module yet");
        }

        @Test
        @DisplayName("refuses to schedule against an inactive segment")
        void schedule_whenSegmentInactive_reportsProblem() {
            when(campaignRepository.findById(CAMPAIGN_ID)).thenReturn(Optional.of(campaign(CampaignStatus.DRAFT)));
            when(segmentReplicaRepository.findById(SEGMENT_ID))
                    .thenReturn(Optional.of(segmentReplica(false, "COMMERCIAL")));
            lenient()
                    .when(templateRepository.findById(EMAIL_TEMPLATE_ID))
                    .thenReturn(Optional.of(new MessageTemplate()));

            assertThatThrownBy(() -> sut.schedule(CAMPAIGN_ID)).hasMessageContaining("is inactive");
        }

        @Test
        @DisplayName("refuses to schedule a campaign whose segment targets a different audience")
        void schedule_whenAudienceMismatched_reportsProblem() {
            when(campaignRepository.findById(CAMPAIGN_ID)).thenReturn(Optional.of(campaign(CampaignStatus.DRAFT)));
            when(segmentReplicaRepository.findById(SEGMENT_ID))
                    .thenReturn(Optional.of(segmentReplica(true, "INDIVIDUAL")));
            lenient()
                    .when(templateRepository.findById(EMAIL_TEMPLATE_ID))
                    .thenReturn(Optional.of(new MessageTemplate()));

            // Templates written for organizations would render against people.
            assertThatThrownBy(() -> sut.schedule(CAMPAIGN_ID)).hasMessageContaining("does not match campaign");
        }

        @Test
        @DisplayName("treats a dangling template id as a missing template")
        void schedule_whenTemplateDangling_reportsProblem() {
            when(campaignRepository.findById(CAMPAIGN_ID)).thenReturn(Optional.of(campaign(CampaignStatus.DRAFT)));
            when(segmentReplicaRepository.findById(SEGMENT_ID))
                    .thenReturn(Optional.of(segmentReplica(true, "COMMERCIAL")));
            // Set on the campaign but no longer resolvable.
            when(templateRepository.findById(EMAIL_TEMPLATE_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> sut.schedule(CAMPAIGN_ID)).hasMessageContaining("no EMAIL template attached");
        }
    }

    @Nested
    @DisplayName("state transitions")
    class Transitions {

        @Test
        @DisplayName("resume returns a paused campaign to SCHEDULED, not straight back to SENDING")
        void resume_returnsToScheduled() {
            when(campaignRepository.findById(CAMPAIGN_ID)).thenReturn(Optional.of(campaign(CampaignStatus.PAUSED)));
            when(campaignRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            // The worker decides when dispatch restarts; a resumed campaign
            // re-enters the queue rather than jumping into send.
            assertThat(sut.resume(CAMPAIGN_ID).status()).isEqualTo("SCHEDULED");
        }

        @Test
        @DisplayName("pause moves a sending campaign to PAUSED")
        void pause_movesToPaused() {
            when(campaignRepository.findById(CAMPAIGN_ID)).thenReturn(Optional.of(campaign(CampaignStatus.SENDING)));
            when(campaignRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            assertThat(sut.pause(CAMPAIGN_ID).status()).isEqualTo("PAUSED");
        }

        @Test
        @DisplayName("refuses an illegal transition rather than silently accepting it")
        void transition_whenIllegal_throws() {
            when(campaignRepository.findById(CAMPAIGN_ID)).thenReturn(Optional.of(campaign(CampaignStatus.SENT)));

            assertThatThrownBy(() -> sut.pause(CAMPAIGN_ID))
                    .isInstanceOf(MarketingUnprocessableEntityException.class)
                    .hasMessageContaining("Cannot move campaign");
        }
    }

    @Nested
    @DisplayName("previewAudience")
    class PreviewAudience {

        @Test
        @DisplayName("reports the last snapshot with its age and asks for a fresher one")
        void preview_reportsSnapshotAndRequestsRefresh() {
            Instant resolvedAt = Instant.parse("2026-08-11T11:00:00Z");
            when(campaignRepository.findById(CAMPAIGN_ID)).thenReturn(Optional.of(campaign(CampaignStatus.DRAFT)));
            when(audienceRepository.findPartyIdsByCampaignId(CAMPAIGN_ID))
                    .thenReturn(List.of(UUID.randomUUID(), UUID.randomUUID()));
            when(audienceRepository.findResolvedAt(CAMPAIGN_ID)).thenReturn(Optional.of(resolvedAt));
            when(eligibilityService.countEligible(anyCollection(), any())).thenReturn(1L);
            campaignIsReady();

            AudiencePreviewResponse preview = sut.previewAudience(CAMPAIGN_ID);

            // The snapshot's age travels with the numbers so a marketer can see how
            // current they are rather than assuming they are live.
            assertThat(preview.segmentMatched()).isEqualTo(2);
            assertThat(preview.resolvedAt()).isEqualTo(resolvedAt);
            verify(resolveRequester).requestResolve(CAMPAIGN_ID, SEGMENT_ID);
        }

        @Test
        @DisplayName("reports per-channel eligibility and whether a template is attached")
        void preview_reportsPerChannelDetail() {
            when(campaignRepository.findById(CAMPAIGN_ID)).thenReturn(Optional.of(campaign(CampaignStatus.DRAFT)));
            when(audienceRepository.findPartyIdsByCampaignId(CAMPAIGN_ID)).thenReturn(List.of(UUID.randomUUID()));
            when(audienceRepository.findResolvedAt(CAMPAIGN_ID)).thenReturn(Optional.empty());
            when(eligibilityService.countEligible(anyCollection(), any())).thenReturn(0L);
            campaignIsReady();

            AudiencePreviewResponse preview = sut.previewAudience(CAMPAIGN_ID);

            assertThat(preview.channels()).singleElement().satisfies(channel -> {
                assertThat(channel.channel()).isEqualTo("EMAIL");
                assertThat(channel.targeted()).isEqualTo(1);
                assertThat(channel.eligible()).isZero();
                assertThat(channel.templateAttached()).isTrue();
            });
        }

        @Test
        @DisplayName("does not request a refresh once the audience is frozen")
        void preview_whenAudienceImmutable_doesNotRequestRefresh() {
            when(campaignRepository.findById(CAMPAIGN_ID)).thenReturn(Optional.of(campaign(CampaignStatus.SENT)));
            when(audienceRepository.findPartyIdsByCampaignId(CAMPAIGN_ID)).thenReturn(List.of());
            when(audienceRepository.findResolvedAt(CAMPAIGN_ID)).thenReturn(Optional.empty());
            when(eligibilityService.countEligible(anyCollection(), any())).thenReturn(0L);
            campaignIsReady();

            sut.previewAudience(CAMPAIGN_ID);

            verify(resolveRequester, never()).requestResolve(any(), any());
        }

        @Test
        @DisplayName("refuses to preview a campaign with no segment bound")
        void preview_whenNoSegment_throws() {
            Campaign unbound = campaign(CampaignStatus.DRAFT);
            unbound.setSegmentId(null);
            when(campaignRepository.findById(CAMPAIGN_ID)).thenReturn(Optional.of(unbound));

            assertThatThrownBy(() -> sut.previewAudience(CAMPAIGN_ID))
                    .isInstanceOf(MarketingUnprocessableEntityException.class)
                    .hasMessageContaining("nothing to preview");
        }
    }

    @Nested
    @DisplayName("reads")
    class Reads {

        @Test
        @DisplayName("get returns the campaign")
        void get_returnsCampaign() {
            when(campaignRepository.findById(CAMPAIGN_ID)).thenReturn(Optional.of(campaign(CampaignStatus.DRAFT)));

            assertThat(sut.get(CAMPAIGN_ID).code()).isEqualTo("SPRING24");
        }

        @Test
        @DisplayName("get throws 404 for an unknown campaign")
        void get_whenAbsent_throws() {
            when(campaignRepository.findById(CAMPAIGN_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> sut.get(CAMPAIGN_ID)).isInstanceOf(MarketingResourceNotFoundException.class);
        }

        @Test
        @DisplayName("list without filters returns everything, newest first")
        void list_withoutFilters_returnsAll() {
            when(campaignRepository.findAllByOrderByCreatedAtDesc())
                    .thenReturn(List.of(campaign(CampaignStatus.DRAFT)));

            assertThat(sut.list(null, null)).hasSize(1);
        }

        @Test
        @DisplayName("list by status uses the status query")
        void list_byStatus_usesStatusQuery() {
            when(campaignRepository.findByStatusOrderByCreatedAtDesc(CampaignStatus.DRAFT))
                    .thenReturn(List.of(campaign(CampaignStatus.DRAFT)));

            assertThat(sut.list(CampaignStatus.DRAFT, null)).hasSize(1);
            verify(campaignRepository, never()).findAllByOrderByCreatedAtDesc();
        }

        @Test
        @DisplayName("list by program narrows to that program, then applies any status filter in memory")
        void list_byProgram_filtersStatusInMemory() {
            UUID programId = UUID.randomUUID();
            when(campaignRepository.findByCampaignProgramId(programId))
                    .thenReturn(List.of(campaign(CampaignStatus.DRAFT), campaign(CampaignStatus.SENT)));

            assertThat(sut.list(CampaignStatus.SENT, programId))
                    .singleElement()
                    .satisfies(response -> assertThat(response.status()).isEqualTo("SENT"));
        }
    }
}

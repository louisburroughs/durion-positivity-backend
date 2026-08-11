package com.positivity.marketing.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.positivity.marketing.internal.dto.CampaignSendResponse;
import com.positivity.marketing.internal.dto.PagedResponse;
import com.positivity.marketing.internal.entity.Campaign;
import com.positivity.marketing.internal.entity.CampaignSend;
import com.positivity.marketing.internal.enums.CampaignChannel;
import com.positivity.marketing.internal.enums.CampaignStatus;
import com.positivity.marketing.internal.enums.SendStatus;
import com.positivity.marketing.internal.exception.MarketingResourceNotFoundException;
import com.positivity.marketing.internal.exception.MarketingUnprocessableEntityException;
import com.positivity.marketing.internal.repository.CampaignAudienceMemberRepository;
import com.positivity.marketing.internal.repository.CampaignRepository;
import com.positivity.marketing.internal.repository.CampaignSendRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * Unit tests for {@link CampaignSendServiceImpl}.
 *
 * <p>
 * Dispatch is the point of no return for a campaign, so the behaviour worth
 * pinning is what it refuses to do and what it survives:
 *
 * <ul>
 * <li><b>An unresolved audience queues nothing.</b> Zero resolved members is
 * ambiguous — it can mean "the segment matched nobody" or "the CRM has not
 * replied yet" — so the service asks for a resolve and returns zero rather than
 * declaring the campaign sent to an empty audience.</li>
 * <li><b>Re-dispatch is expected, not exceptional.</b> An operator retrying
 * after a partial failure must not be punished: rows are saved one at a time so
 * a uniqueness collision costs that single recipient, not the batch.</li>
 * <li><b>Status governs whether dispatch may start at all</b>, and a campaign
 * already SENDING is not re-saved back to SENDING.</li>
 * <li><b>Page size is clamped.</b> An unbounded {@code size} on a bulk campaign
 * would materialize an entire audience into one response.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CampaignSendServiceImpl — dispatch materialization and send listing")
class CampaignSendServiceImplTest {

    private static final UUID CAMPAIGN_ID = UUID.randomUUID();
    private static final UUID SEGMENT_ID = UUID.randomUUID();
    private static final UUID PARTY_A = UUID.randomUUID();
    private static final UUID PARTY_B = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-08-11T10:15:30Z");

    @Mock
    private CampaignRepository campaignRepository;

    @Mock
    private CampaignSendRepository sendRepository;

    @Mock
    private CampaignAudienceMemberRepository audienceRepository;

    @Mock
    private SegmentResolveRequester resolveRequester;

    private CampaignSendServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new CampaignSendServiceImpl(
                Clock.fixed(NOW, ZoneOffset.UTC),
                campaignRepository,
                sendRepository,
                audienceRepository,
                resolveRequester);
    }

    private static Campaign campaign(CampaignStatus status, UUID segmentId, CampaignChannel... channels) {
        Set<CampaignChannel> channelSet = new LinkedHashSet<>(List.of(channels));
        return Campaign.builder()
                .campaignId(CAMPAIGN_ID)
                .code("SPRING-TUNE-UP")
                .status(status)
                .segmentId(segmentId)
                .channels(channelSet)
                .build();
    }

    @Nested
    @DisplayName("dispatch")
    class Dispatch {

        @Test
        @DisplayName("rejects a campaign that does not exist")
        void unknownCampaign() {
            when(campaignRepository.findById(CAMPAIGN_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.dispatch(CAMPAIGN_ID))
                    .isInstanceOf(MarketingResourceNotFoundException.class);

            verifyNoInteractions(audienceRepository, sendRepository, resolveRequester);
        }

        @ParameterizedTest
        @EnumSource(
                value = CampaignStatus.class,
                names = {"SCHEDULED", "SENDING"},
                mode = EnumSource.Mode.EXCLUDE)
        @DisplayName("refuses to dispatch from any status other than SCHEDULED or SENDING")
        void wrongStatus(CampaignStatus status) {
            when(campaignRepository.findById(CAMPAIGN_ID))
                    .thenReturn(Optional.of(campaign(status, SEGMENT_ID, CampaignChannel.EMAIL)));

            assertThatThrownBy(() -> service.dispatch(CAMPAIGN_ID))
                    .isInstanceOf(MarketingUnprocessableEntityException.class)
                    .hasMessageContaining(status.name());

            verifyNoInteractions(audienceRepository, sendRepository);
        }

        @Test
        @DisplayName("refuses to dispatch a campaign with no segment bound")
        void noSegment() {
            when(campaignRepository.findById(CAMPAIGN_ID))
                    .thenReturn(Optional.of(campaign(CampaignStatus.SCHEDULED, null, CampaignChannel.EMAIL)));

            assertThatThrownBy(() -> service.dispatch(CAMPAIGN_ID))
                    .isInstanceOf(MarketingUnprocessableEntityException.class)
                    .hasMessageContaining("no segment bound");

            verifyNoInteractions(audienceRepository, sendRepository);
        }

        @Test
        @DisplayName("requests a resolve and queues nothing when the audience has not arrived yet")
        void unresolvedAudienceQueuesNothing() {
            Campaign scheduled = campaign(CampaignStatus.SCHEDULED, SEGMENT_ID, CampaignChannel.EMAIL);
            when(campaignRepository.findById(CAMPAIGN_ID)).thenReturn(Optional.of(scheduled));
            when(audienceRepository.findPartyIdsByCampaignId(CAMPAIGN_ID)).thenReturn(List.of());

            assertThat(service.dispatch(CAMPAIGN_ID)).isZero();

            verify(resolveRequester).requestResolve(CAMPAIGN_ID, SEGMENT_ID);
            verify(sendRepository, never()).saveAndFlush(any());
            // The campaign must stay SCHEDULED — flipping it to SENDING with an empty audience
            // would report the campaign as under way when nothing has been queued.
            verify(campaignRepository, never()).save(any());
            assertThat(scheduled.getStatus()).isEqualTo(CampaignStatus.SCHEDULED);
        }

        @Test
        @DisplayName("queues one PENDING row per recipient per channel and moves SCHEDULED to SENDING")
        void queuesRecipientChannelMatrix() {
            Campaign scheduled =
                    campaign(CampaignStatus.SCHEDULED, SEGMENT_ID, CampaignChannel.EMAIL, CampaignChannel.SMS);
            when(campaignRepository.findById(CAMPAIGN_ID)).thenReturn(Optional.of(scheduled));
            when(audienceRepository.findPartyIdsByCampaignId(CAMPAIGN_ID)).thenReturn(List.of(PARTY_A, PARTY_B));
            when(sendRepository.findByCampaignIdAndRecipientPartyIdAndChannel(eq(CAMPAIGN_ID), any(), any()))
                    .thenReturn(Optional.empty());

            assertThat(service.dispatch(CAMPAIGN_ID)).isEqualTo(4);

            ArgumentCaptor<CampaignSend> saved = ArgumentCaptor.forClass(CampaignSend.class);
            verify(sendRepository, times(4)).saveAndFlush(saved.capture());
            assertThat(saved.getAllValues())
                    .allSatisfy(send -> {
                        assertThat(send.getCampaignId()).isEqualTo(CAMPAIGN_ID);
                        assertThat(send.getStatus()).isEqualTo(SendStatus.PENDING);
                        assertThat(send.getQueuedAt()).isEqualTo(NOW);
                        assertThat(send.getUpdatedAt()).isEqualTo(NOW);
                    })
                    .extracting(CampaignSend::getRecipientPartyId, CampaignSend::getChannel)
                    .containsExactlyInAnyOrder(
                            tuple(PARTY_A, CampaignChannel.EMAIL),
                            tuple(PARTY_A, CampaignChannel.SMS),
                            tuple(PARTY_B, CampaignChannel.EMAIL),
                            tuple(PARTY_B, CampaignChannel.SMS));

            assertThat(scheduled.getStatus()).isEqualTo(CampaignStatus.SENDING);
            verify(campaignRepository).save(scheduled);
            verifyNoInteractions(resolveRequester);
        }

        @Test
        @DisplayName("skips a recipient-channel pair that is already queued")
        void skipsAlreadyQueuedPairs() {
            Campaign sending = campaign(CampaignStatus.SENDING, SEGMENT_ID, CampaignChannel.EMAIL);
            when(campaignRepository.findById(CAMPAIGN_ID)).thenReturn(Optional.of(sending));
            when(audienceRepository.findPartyIdsByCampaignId(CAMPAIGN_ID)).thenReturn(List.of(PARTY_A, PARTY_B));
            when(sendRepository.findByCampaignIdAndRecipientPartyIdAndChannel(
                            CAMPAIGN_ID, PARTY_A, CampaignChannel.EMAIL))
                    .thenReturn(Optional.of(new CampaignSend()));
            when(sendRepository.findByCampaignIdAndRecipientPartyIdAndChannel(
                            CAMPAIGN_ID, PARTY_B, CampaignChannel.EMAIL))
                    .thenReturn(Optional.empty());

            assertThat(service.dispatch(CAMPAIGN_ID)).isEqualTo(1);

            ArgumentCaptor<CampaignSend> saved = ArgumentCaptor.forClass(CampaignSend.class);
            verify(sendRepository).saveAndFlush(saved.capture());
            assertThat(saved.getValue().getRecipientPartyId()).isEqualTo(PARTY_B);
            // Already SENDING: no redundant write back to the same status.
            verify(campaignRepository, never()).save(any());
        }

        @Test
        @DisplayName("counts only the rows that survived a uniqueness collision and keeps going")
        void collisionCostsOnlyThatRecipient() {
            when(campaignRepository.findById(CAMPAIGN_ID))
                    .thenReturn(Optional.of(campaign(CampaignStatus.SENDING, SEGMENT_ID, CampaignChannel.EMAIL)));
            when(audienceRepository.findPartyIdsByCampaignId(CAMPAIGN_ID)).thenReturn(List.of(PARTY_A, PARTY_B));
            when(sendRepository.findByCampaignIdAndRecipientPartyIdAndChannel(eq(CAMPAIGN_ID), any(), any()))
                    .thenReturn(Optional.empty());
            when(sendRepository.saveAndFlush(any()))
                    .thenThrow(new DataIntegrityViolationException("duplicate key"))
                    .thenReturn(new CampaignSend());

            assertThat(service.dispatch(CAMPAIGN_ID)).isEqualTo(1);

            // The loser of the race must not abort the batch: the second recipient still gets a row.
            verify(sendRepository, times(2)).saveAndFlush(any());
        }
    }

    @Nested
    @DisplayName("listSends")
    class ListSends {

        @Test
        @DisplayName("maps a send row onto the response contract")
        void mapsResponseFields() {
            UUID sendId = UUID.randomUUID();
            UUID contactId = UUID.randomUUID();
            CampaignSend send = CampaignSend.builder()
                    .campaignSendId(sendId)
                    .campaignId(CAMPAIGN_ID)
                    .recipientPartyId(PARTY_A)
                    .contactId(contactId)
                    .channel(CampaignChannel.EMAIL)
                    .status(SendStatus.BOUNCED)
                    .failureReason("mailbox full")
                    .providerMessageId("prov-1")
                    .attempts(3)
                    .queuedAt(NOW)
                    .sentAt(NOW.plusSeconds(5))
                    .build();
            when(sendRepository.findByCampaignId(eq(CAMPAIGN_ID), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of(send), PageRequest.of(0, 50), 1));

            PagedResponse<CampaignSendResponse> response = service.listSends(CAMPAIGN_ID, 0, 50);

            assertThat(response.totalElements()).isEqualTo(1);
            assertThat(response.items())
                    .singleElement()
                    .isEqualTo(new CampaignSendResponse(
                            sendId,
                            CAMPAIGN_ID,
                            PARTY_A,
                            contactId,
                            "EMAIL",
                            "BOUNCED",
                            "mailbox full",
                            "prov-1",
                            3,
                            NOW,
                            NOW.plusSeconds(5)));
        }

        @ParameterizedTest
        @ValueSource(ints = {0, 1, 201, 5_000, Integer.MAX_VALUE})
        @DisplayName("clamps page size into 1..200 so one request cannot pull a whole audience")
        void clampsSize(int requestedSize) {
            when(sendRepository.findByCampaignId(eq(CAMPAIGN_ID), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of()));

            service.listSends(CAMPAIGN_ID, 0, requestedSize);

            assertThat(capturedPageable().getPageSize()).isBetween(1, 200);
        }

        @Test
        @DisplayName("treats a negative page index as the first page and sorts oldest-queued first")
        void negativePageAndSortOrder() {
            when(sendRepository.findByCampaignId(eq(CAMPAIGN_ID), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of()));

            service.listSends(CAMPAIGN_ID, -3, 50);

            Pageable pageable = capturedPageable();
            assertThat(pageable.getPageNumber()).isZero();
            assertThat(pageable.getSort().getOrderFor("queuedAt"))
                    .isNotNull()
                    .extracting(Sort.Order::getDirection)
                    .isEqualTo(Sort.Direction.ASC);
        }

        private Pageable capturedPageable() {
            ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
            verify(sendRepository).findByCampaignId(eq(CAMPAIGN_ID), captor.capture());
            return captor.getValue();
        }
    }
}

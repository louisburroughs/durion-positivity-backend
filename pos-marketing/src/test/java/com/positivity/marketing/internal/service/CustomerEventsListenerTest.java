package com.positivity.marketing.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.positivity.domainevents.customer.CustomerConsentDecisionChangedV1;
import com.positivity.domainevents.customer.CustomerRedemptionRecordedV1;
import com.positivity.domainevents.customer.CustomerSegmentChangedV1;
import com.positivity.domainevents.customer.CustomerSegmentResolvedV1;
import com.positivity.domainevents.customer.CustomerSuppressionChangedV1;
import com.positivity.marketing.internal.entity.Campaign;
import com.positivity.marketing.internal.entity.CampaignAttribution;
import com.positivity.marketing.internal.entity.CampaignAudienceMember;
import com.positivity.marketing.internal.entity.PartyConsentReplica;
import com.positivity.marketing.internal.entity.ProcessedEvent;
import com.positivity.marketing.internal.entity.SegmentReplica;
import com.positivity.marketing.internal.entity.SuppressionReplica;
import com.positivity.marketing.internal.enums.CampaignStatus;
import com.positivity.marketing.internal.repository.CampaignAttributionRepository;
import com.positivity.marketing.internal.repository.CampaignAudienceMemberRepository;
import com.positivity.marketing.internal.repository.CampaignRepository;
import com.positivity.marketing.internal.repository.PartyConsentReplicaRepository;
import com.positivity.marketing.internal.repository.ProcessedEventRepository;
import com.positivity.marketing.internal.repository.SegmentReplicaRepository;
import com.positivity.marketing.internal.repository.SuppressionReplicaRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

/**
 * Unit tests for {@link CustomerEventsListener}, this module's entire CRM
 * integration (ADR-0044 R3).
 *
 * <p>
 * Delivery is at-least-once and the payloads come from another service, so the
 * behaviour worth pinning is what survives repetition and malformed input:
 *
 * <ul>
 * <li><b>Replays apply nothing twice.</b> The {@code processed_events} log
 * guards the envelope, and attribution is additionally keyed on the CRM's
 * {@code redemptionId} so one conversion can only ever be counted once.</li>
 * <li><b>An event this module does not care about is not recorded.</b> Writing
 * a processed-event row for every customer fact would make the log useless as a
 * record of what was actually applied.</li>
 * <li><b>Consent freshness comes from the envelope, not the clock.</b> Stamping
 * arrival time would make a backlogged consumer look perfectly fresh to the
 * send worker's staleness check.</li>
 * <li><b>A resolved audience never replaces one mid-send.</b> Only campaigns
 * whose status still allows audience mutation are refreshed.</li>
 * <li><b>Missing or malformed fields degrade to a skip, not an exception</b> —
 * a poison message must not wedge the partition.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CustomerEventsListener — CRM fact replication and attribution")
class CustomerEventsListenerTest {

    private static final UUID SEGMENT_ID = UUID.fromString("01960004-0000-7000-8000-0000000000a1");
    private static final UUID PARTY_ID = UUID.fromString("01960004-0000-7000-8000-0000000000a2");
    private static final UUID CAMPAIGN_ID = UUID.fromString("01960004-0000-7000-8000-0000000000a3");
    private static final UUID REDEMPTION_ID = UUID.fromString("01960004-0000-7000-8000-0000000000a4");
    private static final Instant NOW = Instant.parse("2026-08-11T09:00:00Z");
    private static final Instant OCCURRED_AT = Instant.parse("2026-08-10T07:30:00Z");

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private ProcessedEventRepository processedEventRepository;

    @Mock
    private SuppressionReplicaRepository suppressionRepository;

    @Mock
    private PartyConsentReplicaRepository consentRepository;

    @Mock
    private SegmentReplicaRepository segmentRepository;

    @Mock
    private CampaignAudienceMemberRepository audienceRepository;

    @Mock
    private CampaignAttributionRepository attributionRepository;

    @Mock
    private CampaignRepository campaignRepository;

    private CustomerEventsListener listener;

    @BeforeEach
    void setUp() {
        listener = new CustomerEventsListener(
                clock,
                objectMapper,
                processedEventRepository,
                suppressionRepository,
                consentRepository,
                segmentRepository,
                audienceRepository,
                attributionRepository,
                campaignRepository);
    }

    private static String envelope(String eventId, String eventType, String payloadJson) {
        return """
                {"eventId":"%s","eventType":"%s","occurredAtUtc":"%s","payload":%s}""".formatted(eventId, eventType, OCCURRED_AT, payloadJson);
    }

    private void expectUnprocessed(String eventId) {
        when(processedEventRepository.existsById(eventId)).thenReturn(false);
    }

    @Nested
    @DisplayName("envelope handling")
    class Envelope {

        @Test
        @DisplayName("skips a replay of an event already in the processed log")
        void skipsReplay() {
            when(processedEventRepository.existsById("evt-1")).thenReturn(true);

            listener.onCustomerEvent(envelope("evt-1", CustomerSuppressionChangedV1.EVENT_TYPE, """
                    {"channel":"EMAIL","addressHash":"abc","suppressed":true}"""));

            verifyNoInteractions(suppressionRepository);
            verify(processedEventRepository, never()).save(any());
        }

        @Test
        @DisplayName("skips an event with no id, since it cannot be de-duplicated")
        void skipsUnidentifiedEvent() {
            listener.onCustomerEvent(envelope("", CustomerSuppressionChangedV1.EVENT_TYPE, """
                    {"channel":"EMAIL","addressHash":"abc","suppressed":true}"""));

            verifyNoInteractions(suppressionRepository);
            verify(processedEventRepository, never()).save(any());
        }

        @Test
        @DisplayName("ignores a customer fact this module does not consume, without logging it as processed")
        void unknownEventTypeIsNotRecorded() {
            expectUnprocessed("evt-2");

            listener.onCustomerEvent(envelope("evt-2", "customer.party.updated", "{}"));

            verify(processedEventRepository, never()).save(any());
            verifyNoInteractions(suppressionRepository, consentRepository, segmentRepository, attributionRepository);
        }

        @Test
        @DisplayName("records the event as processed once a handler has applied it")
        void recordsProcessedEvent() {
            expectUnprocessed("evt-3");

            listener.onCustomerEvent(envelope("evt-3", CustomerSuppressionChangedV1.EVENT_TYPE, """
                    {"channel":"EMAIL","addressHash":"abc","suppressed":true}"""));

            ArgumentCaptor<ProcessedEvent> captor = ArgumentCaptor.forClass(ProcessedEvent.class);
            verify(processedEventRepository).save(captor.capture());
            assertThat(captor.getValue().getEventId()).isEqualTo("evt-3");
            assertThat(captor.getValue().getOwner()).isEqualTo("pos-customer");
            assertThat(captor.getValue().getProcessedAt()).isEqualTo(NOW);
        }
    }

    @Nested
    @DisplayName("customer.suppression.changed")
    class Suppression {

        @Test
        @DisplayName("stores a suppression under its channel-address key")
        void storesSuppression() {
            expectUnprocessed("evt-s1");

            listener.onCustomerEvent(
                    envelope("evt-s1", CustomerSuppressionChangedV1.EVENT_TYPE, """
                    {"channel":"EMAIL","addressHash":"abc","suppressed":true,"reason":"BOUNCE","partyId":"%s"}""".formatted(PARTY_ID)));

            ArgumentCaptor<SuppressionReplica> captor = ArgumentCaptor.forClass(SuppressionReplica.class);
            verify(suppressionRepository).save(captor.capture());
            SuppressionReplica saved = captor.getValue();
            assertThat(saved.getSuppressionKey()).isEqualTo(SuppressionReplica.keyOf("EMAIL", "abc"));
            assertThat(saved.getChannel()).isEqualTo("EMAIL");
            assertThat(saved.getPartyId()).isEqualTo(PARTY_ID);
            assertThat(saved.getReason()).isEqualTo("BOUNCE");
            assertThat(saved.getUpdatedAt()).isEqualTo(NOW);
        }

        @Test
        @DisplayName("lifts a suppression by deleting the replica row")
        void liftsSuppression() {
            expectUnprocessed("evt-s2");

            listener.onCustomerEvent(envelope("evt-s2", CustomerSuppressionChangedV1.EVENT_TYPE, """
                    {"channel":"SMS","addressHash":"xyz","suppressed":false}"""));

            verify(suppressionRepository).deleteById(SuppressionReplica.keyOf("SMS", "xyz"));
            verify(suppressionRepository, never()).save(any());
        }

        @ParameterizedTest
        @ValueSource(
                strings = {
                    "{\"addressHash\":\"abc\",\"suppressed\":true}",
                    "{\"channel\":\"EMAIL\",\"suppressed\":true}",
                    "{\"channel\":\"\",\"addressHash\":\"\",\"suppressed\":true}"
                })
        @DisplayName("ignores a suppression fact missing the fields that form its key")
        void ignoresIncompleteSuppression(String payload) {
            expectUnprocessed("evt-s3");

            listener.onCustomerEvent(envelope("evt-s3", CustomerSuppressionChangedV1.EVENT_TYPE, payload));

            verifyNoInteractions(suppressionRepository);
            // Still recorded: the event was consumed and deliberately dropped, not left unhandled.
            verify(processedEventRepository).save(any());
        }
    }

    @Nested
    @DisplayName("customer.consent.decision-changed")
    class Consent {

        @Test
        @DisplayName("takes decidedAt from the envelope so a backlogged consumer does not look fresh")
        void decidedAtComesFromEnvelope() {
            expectUnprocessed("evt-c1");
            UUID governingParty = UUID.randomUUID();

            listener.onCustomerEvent(envelope(
                    "evt-c1", CustomerConsentDecisionChangedV1.EVENT_TYPE, """
                    {"partyId":"%s","channel":"EMAIL","allowed":true,"reason":"OPT_IN","governingPartyId":"%s"}""".formatted(PARTY_ID, governingParty)));

            ArgumentCaptor<PartyConsentReplica> captor = ArgumentCaptor.forClass(PartyConsentReplica.class);
            verify(consentRepository).save(captor.capture());
            PartyConsentReplica saved = captor.getValue();
            assertThat(saved.getConsentKey()).isEqualTo(PartyConsentReplica.keyOf(PARTY_ID, "EMAIL"));
            assertThat(saved.isAllowed()).isTrue();
            assertThat(saved.getReason()).isEqualTo("OPT_IN");
            assertThat(saved.getGoverningPartyId()).isEqualTo(governingParty);
            assertThat(saved.getDecidedAt()).isEqualTo(OCCURRED_AT);
        }

        @Test
        @DisplayName("falls back to the local clock when the envelope carries no usable timestamp")
        void unparseableTimestampFallsBackToClock() {
            expectUnprocessed("evt-c2");

            listener.onCustomerEvent("""
                    {"eventId":"evt-c2","eventType":"%s","occurredAtUtc":"not-a-timestamp",\
                    "payload":{"partyId":"%s","channel":"EMAIL","allowed":false}}""".formatted(CustomerConsentDecisionChangedV1.EVENT_TYPE, PARTY_ID));

            ArgumentCaptor<PartyConsentReplica> captor = ArgumentCaptor.forClass(PartyConsentReplica.class);
            verify(consentRepository).save(captor.capture());
            assertThat(captor.getValue().getDecidedAt()).isEqualTo(NOW);
            // An absent reason must not be null: the send worker branches on it.
            assertThat(captor.getValue().getReason()).isEqualTo("UNKNOWN");
        }

        @Test
        @DisplayName("ignores a consent fact whose party id is not a UUID")
        void ignoresMalformedPartyId() {
            expectUnprocessed("evt-c3");

            listener.onCustomerEvent(envelope("evt-c3", CustomerConsentDecisionChangedV1.EVENT_TYPE, """
                    {"partyId":"not-a-uuid","channel":"EMAIL","allowed":true}"""));

            verifyNoInteractions(consentRepository);
        }
    }

    @Nested
    @DisplayName("customer.segment.changed")
    class Segment {

        @Test
        @DisplayName("replicates segment metadata")
        void replicatesSegment() {
            expectUnprocessed("evt-g1");

            listener.onCustomerEvent(
                    envelope("evt-g1", CustomerSegmentChangedV1.EVENT_TYPE, """
                    {"segmentId":"%s","name":"Fleet owners","audienceType":"COMMERCIAL",\
                    "type":"DYNAMIC","active":true}""".formatted(SEGMENT_ID)));

            ArgumentCaptor<SegmentReplica> captor = ArgumentCaptor.forClass(SegmentReplica.class);
            verify(segmentRepository).save(captor.capture());
            SegmentReplica saved = captor.getValue();
            assertThat(saved.getSegmentId()).isEqualTo(SEGMENT_ID);
            assertThat(saved.getName()).isEqualTo("Fleet owners");
            assertThat(saved.getAudienceType()).isEqualTo("COMMERCIAL");
            assertThat(saved.isActive()).isTrue();
            assertThat(saved.getUpdatedAt()).isEqualTo(NOW);
        }

        @Test
        @DisplayName("removes the replica when the segment is deleted upstream")
        void deletesSegment() {
            expectUnprocessed("evt-g2");

            listener.onCustomerEvent(
                    envelope("evt-g2", CustomerSegmentChangedV1.EVENT_TYPE, """
                    {"segmentId":"%s","deleted":true}""".formatted(SEGMENT_ID)));

            verify(segmentRepository).deleteById(SEGMENT_ID);
            verify(segmentRepository, never()).save(any());
        }

        @Test
        @DisplayName("ignores a segment fact with no segment id")
        void ignoresSegmentWithoutId() {
            expectUnprocessed("evt-g3");

            listener.onCustomerEvent(envelope("evt-g3", CustomerSegmentChangedV1.EVENT_TYPE, "{}"));

            verifyNoInteractions(segmentRepository);
        }
    }

    @Nested
    @DisplayName("customer.segment.resolved")
    class SegmentResolved {

        private Campaign campaign(UUID campaignId, UUID segmentId, CampaignStatus status) {
            return Campaign.builder()
                    .campaignId(campaignId)
                    .segmentId(segmentId)
                    .status(status)
                    .build();
        }

        @Test
        @DisplayName("replaces the audience snapshot of every campaign on the resolved segment")
        void refreshesAudienceForMatchingCampaigns() {
            expectUnprocessed("evt-r1");
            UUID secondCampaign = UUID.randomUUID();
            when(campaignRepository.findAll())
                    .thenReturn(List.of(
                            campaign(CAMPAIGN_ID, SEGMENT_ID, CampaignStatus.SCHEDULED),
                            campaign(secondCampaign, SEGMENT_ID, CampaignStatus.DRAFT)));

            listener.onCustomerEvent(
                    envelope("evt-r1", CustomerSegmentResolvedV1.EVENT_TYPE, """
                    {"segmentId":"%s","partyIds":["%s"]}""".formatted(SEGMENT_ID, PARTY_ID)));

            // Delete-then-insert: a snapshot is a replacement, not an accumulation.
            verify(audienceRepository).deleteByCampaignId(CAMPAIGN_ID);
            verify(audienceRepository).deleteByCampaignId(secondCampaign);
            ArgumentCaptor<List<CampaignAudienceMember>> captor = ArgumentCaptor.captor();
            verify(audienceRepository, org.mockito.Mockito.times(2)).saveAll(captor.capture());
            assertThat(captor.getAllValues())
                    .allSatisfy(members -> assertThat(members).singleElement().satisfies(member -> {
                        assertThat(member.getPartyId()).isEqualTo(PARTY_ID);
                        assertThat(member.getResolvedAt()).isEqualTo(NOW);
                    }));
        }

        @Test
        @DisplayName("leaves a campaign that is already sending untouched")
        void skipsCampaignsMidSend() {
            expectUnprocessed("evt-r2");
            when(campaignRepository.findAll())
                    .thenReturn(List.of(
                            campaign(CAMPAIGN_ID, SEGMENT_ID, CampaignStatus.SENDING),
                            campaign(UUID.randomUUID(), UUID.randomUUID(), CampaignStatus.DRAFT)));

            listener.onCustomerEvent(
                    envelope("evt-r2", CustomerSegmentResolvedV1.EVENT_TYPE, """
                    {"segmentId":"%s","partyIds":["%s"]}""".formatted(SEGMENT_ID, PARTY_ID)));

            verifyNoInteractions(audienceRepository);
        }

        @Test
        @DisplayName("drops a malformed party id rather than failing the whole snapshot")
        void skipsMalformedPartyIds() {
            expectUnprocessed("evt-r3");
            when(campaignRepository.findAll())
                    .thenReturn(List.of(campaign(CAMPAIGN_ID, SEGMENT_ID, CampaignStatus.SCHEDULED)));

            listener.onCustomerEvent(
                    envelope("evt-r3", CustomerSegmentResolvedV1.EVENT_TYPE, """
                    {"segmentId":"%s","partyIds":["%s","not-a-uuid",""]}""".formatted(SEGMENT_ID, PARTY_ID)));

            ArgumentCaptor<List<CampaignAudienceMember>> captor = ArgumentCaptor.captor();
            verify(audienceRepository).saveAll(captor.capture());
            assertThat(captor.getValue())
                    .singleElement()
                    .satisfies(member -> assertThat(member.getPartyId()).isEqualTo(PARTY_ID));
        }

        @Test
        @DisplayName("ignores a resolve reply with no segment id")
        void ignoresResolveWithoutSegment() {
            expectUnprocessed("evt-r4");

            listener.onCustomerEvent(envelope("evt-r4", CustomerSegmentResolvedV1.EVENT_TYPE, "{}"));

            verifyNoInteractions(audienceRepository, campaignRepository);
        }
    }

    @Nested
    @DisplayName("customer.redemption.recorded")
    class Redemption {

        @Test
        @DisplayName("attributes a redemption to the campaign whose code it carries")
        void attributesToCampaign() {
            expectUnprocessed("evt-x1");
            UUID workorderId = UUID.randomUUID();
            when(attributionRepository.existsById(REDEMPTION_ID)).thenReturn(false);
            when(campaignRepository.findByCodeIgnoreCase("SPRING"))
                    .thenReturn(Optional.of(Campaign.builder()
                            .campaignId(CAMPAIGN_ID)
                            .code("SPRING")
                            .build()));

            listener.onCustomerEvent(envelope("evt-x1", CustomerRedemptionRecordedV1.EVENT_TYPE, """
                    {"campaignCode":"SPRING","redemptionId":"%s","customerId":"%s",\
                    "workorderId":"%s","discountAmount":42.50}""".formatted(
                            REDEMPTION_ID, PARTY_ID, workorderId)));

            ArgumentCaptor<CampaignAttribution> captor = ArgumentCaptor.forClass(CampaignAttribution.class);
            verify(attributionRepository).save(captor.capture());
            CampaignAttribution saved = captor.getValue();
            assertThat(saved.getRedemptionId()).isEqualTo(REDEMPTION_ID);
            assertThat(saved.getCampaignId()).isEqualTo(CAMPAIGN_ID);
            assertThat(saved.getCampaignCode()).isEqualTo("SPRING");
            assertThat(saved.getPartyId()).isEqualTo(PARTY_ID);
            assertThat(saved.getWorkorderId()).isEqualTo(workorderId);
            assertThat(saved.getDiscountAmount()).isEqualByComparingTo("42.50");
            assertThat(saved.getAttributedAt()).isEqualTo(NOW);
        }

        @Test
        @DisplayName("counts a redemption once even if the fact is redelivered under a new event id")
        void redemptionIdIsTheIdempotencyKey() {
            expectUnprocessed("evt-x2");
            when(attributionRepository.existsById(REDEMPTION_ID)).thenReturn(true);

            listener.onCustomerEvent(
                    envelope("evt-x2", CustomerRedemptionRecordedV1.EVENT_TYPE, """
                    {"campaignCode":"SPRING","redemptionId":"%s"}""".formatted(REDEMPTION_ID)));

            verify(attributionRepository, never()).save(any());
            verify(campaignRepository, never()).findByCodeIgnoreCase(any());
        }

        @Test
        @DisplayName("ignores an organic redemption that names no campaign")
        void organicRedemptionAttributesToNothing() {
            expectUnprocessed("evt-x3");

            listener.onCustomerEvent(
                    envelope("evt-x3", CustomerRedemptionRecordedV1.EVENT_TYPE, """
                    {"redemptionId":"%s","discountAmount":10}""".formatted(REDEMPTION_ID)));

            verifyNoInteractions(attributionRepository);
        }

        @Test
        @DisplayName("refuses to attribute a redemption with no redemption id, since it could not be de-duplicated")
        void missingRedemptionIdIsNotAttributed() {
            expectUnprocessed("evt-x4");

            listener.onCustomerEvent(envelope("evt-x4", CustomerRedemptionRecordedV1.EVENT_TYPE, """
                    {"campaignCode":"SPRING"}"""));

            verifyNoInteractions(attributionRepository);
        }

        @Test
        @DisplayName("does not attribute a redemption naming a campaign code this module has never seen")
        void unknownCampaignCodeIsNotAttributed() {
            expectUnprocessed("evt-x5");
            when(attributionRepository.existsById(REDEMPTION_ID)).thenReturn(false);
            when(campaignRepository.findByCodeIgnoreCase("GHOST")).thenReturn(Optional.empty());

            listener.onCustomerEvent(
                    envelope("evt-x5", CustomerRedemptionRecordedV1.EVENT_TYPE, """
                    {"campaignCode":"GHOST","redemptionId":"%s"}""".formatted(REDEMPTION_ID)));

            verify(attributionRepository, never()).save(any());
        }
    }
}

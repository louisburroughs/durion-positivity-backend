package com.positivity.marketing.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.positivity.domainevents.marketing.MarketingCampaignSendOutcomeV1;
import com.positivity.marketing.internal.config.OutboxEventWriter;
import com.positivity.marketing.internal.entity.Campaign;
import com.positivity.marketing.internal.entity.CampaignSend;
import com.positivity.marketing.internal.entity.ProcessedEvent;
import com.positivity.marketing.internal.enums.AudienceType;
import com.positivity.marketing.internal.enums.CampaignChannel;
import com.positivity.marketing.internal.enums.CampaignStatus;
import com.positivity.marketing.internal.enums.SendStatus;
import com.positivity.marketing.internal.repository.CampaignRepository;
import com.positivity.marketing.internal.repository.CampaignSendRepository;
import com.positivity.marketing.internal.repository.ProcessedEventRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Pins {@link DeliveryOutcomeListener} against the {@code sender.outcomes.v1} envelope tabulated
 * in {@code docs/PLATFORM_SENDER_CONTRACT.md} §2 (issue #1537 / D5). {@code sender.outcomes.v1}
 * has no in-repo producer — it is fed by the external shared platform sender — so the published
 * document is the only other copy of this shape, and this test is what keeps the consumer from
 * drifting away from it silently.
 *
 * <p>Every fixture below is raw JSON built from the §2 table's documented field names, not from a
 * mock of an intermediate DTO: a field rename in {@link DeliveryOutcomeListener} that the table
 * does not also get updated for shows up here as a broken assertion, not as a passing test of the
 * wrong thing.
 */
@ExtendWith(MockitoExtension.class)
class PlatformSenderContractTest {

    private static final UUID CAMPAIGN_ID = UUID.fromString("01960004-0000-7000-8000-0000000000a0");
    private static final UUID SEND_ID = UUID.fromString("01960004-0000-7000-8000-0000000000a1");
    private static final UUID PARTY = UUID.fromString("01960004-0000-7000-8000-0000000000a2");
    private static final String PROVIDER_MESSAGE_ID = "contract-prov-1";
    private static final Instant CLOCK_NOW = Instant.parse("2026-08-27T09:00:00Z");

    private final Clock clock = Clock.fixed(CLOCK_NOW, ZoneOffset.UTC);
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private ProcessedEventRepository processedEventRepository;

    @Mock
    private CampaignSendRepository sendRepository;

    @Mock
    private CampaignRepository campaignRepository;

    @Mock
    private MarketingFactPublisher factPublisher;

    @Mock
    private ObjectProvider<OutboxEventWriter> outboxProvider;

    @Mock
    private OutboxEventWriter outboxWriter;

    private DeliveryOutcomeListener listener;

    @BeforeEach
    void setUp() {
        listener = new DeliveryOutcomeListener(
                clock,
                objectMapper,
                processedEventRepository,
                sendRepository,
                campaignRepository,
                factPublisher,
                outboxProvider,
                "customer.commands.v1");
    }

    private static CampaignSend sentSend() {
        return CampaignSend.builder()
                .campaignSendId(SEND_ID)
                .campaignId(CAMPAIGN_ID)
                .recipientPartyId(PARTY)
                .channel(CampaignChannel.EMAIL)
                .status(SendStatus.SENT)
                .providerMessageId(PROVIDER_MESSAGE_ID)
                .queuedAt(Instant.parse("2026-08-27T08:00:00Z"))
                .sentAt(Instant.parse("2026-08-27T08:30:00Z"))
                .updatedAt(Instant.parse("2026-08-27T08:30:00Z"))
                .build();
    }

    private void givenSend(CampaignSend send, String eventId) {
        when(processedEventRepository.existsById(eventId)).thenReturn(false);
        when(sendRepository.findByProviderMessageId(PROVIDER_MESSAGE_ID)).thenReturn(Optional.of(send));
    }

    /** Only outcomes that emit a `marketing.events.v1` fact look the campaign up for its code. */
    private void givenCampaign() {
        when(campaignRepository.findById(CAMPAIGN_ID))
                .thenReturn(Optional.of(Campaign.builder()
                        .campaignId(CAMPAIGN_ID)
                        .code("CONTRACT-2026")
                        .name("Contract")
                        .audienceType(AudienceType.INDIVIDUAL)
                        .status(CampaignStatus.SENDING)
                        .build()));
    }

    /** Envelope shape per §2: standard domain envelope, `occurredAt` lives inside `payload`. */
    private static String envelope(String eventId, String eventType, String payloadJson) {
        return "{\"eventId\":\"" + eventId + "\",\"eventType\":\"" + eventType + "\",\"payload\":" + payloadJson + "}";
    }

    // --- §2 row 1: sender.message.delivered -> providerMessageId, occurredAt -> send DELIVERED ---

    @Test
    @DisplayName("§2 row 1 sender.message.delivered: documented fields move SENT to DELIVERED")
    void deliveredUsesDocumentedFields() {
        CampaignSend send = sentSend();
        givenCampaign();
        givenSend(send, "evt-delivered-1");

        listener.onSenderOutcome(envelope(
                "evt-delivered-1",
                DeliveryOutcomeListener.OUTCOME_DELIVERED,
                "{\"providerMessageId\":\"" + PROVIDER_MESSAGE_ID + "\",\"occurredAt\":\"2026-08-27T08:45:00Z\"}"));

        assertThat(send.getStatus()).isEqualTo(SendStatus.DELIVERED);
        assertThat(send.getDeliveredAt()).isEqualTo(Instant.parse("2026-08-27T08:45:00Z"));
        verify(factPublisher)
                .sendOutcome(eq(send), eq("CONTRACT-2026"), eq(MarketingCampaignSendOutcomeV1.EVENT_TYPE_DELIVERED));
        verify(processedEventRepository).save(any(ProcessedEvent.class));
    }

    @Test
    @DisplayName(
            "§2 prose: 'a missing or malformed value makes pos-marketing fall back to its own clock' — occurredAt absent")
    void occurredAtDefaultsToClockWhenAbsent() {
        CampaignSend send = sentSend();
        givenCampaign();
        givenSend(send, "evt-delivered-2");

        listener.onSenderOutcome(envelope(
                "evt-delivered-2",
                DeliveryOutcomeListener.OUTCOME_DELIVERED,
                "{\"providerMessageId\":\"" + PROVIDER_MESSAGE_ID + "\"}"));

        assertThat(send.getDeliveredAt()).isEqualTo(CLOCK_NOW);
    }

    @Test
    @DisplayName("§2 prose: a malformed occurredAt is also treated as absent and falls back to the clock")
    void occurredAtDefaultsToClockWhenMalformed() {
        CampaignSend send = sentSend();
        givenCampaign();
        givenSend(send, "evt-delivered-3");

        listener.onSenderOutcome(envelope(
                "evt-delivered-3",
                DeliveryOutcomeListener.OUTCOME_DELIVERED,
                "{\"providerMessageId\":\"" + PROVIDER_MESSAGE_ID + "\",\"occurredAt\":\"not-a-timestamp\"}"));

        assertThat(send.getDeliveredAt()).isEqualTo(CLOCK_NOW);
    }

    // --- §2 row 2: sender.message.bounced -> providerMessageId, reason, permanent (default true),
    // address, occurredAt -> send BOUNCED; hard bounce -> CRM suppression ---

    @Test
    @DisplayName(
            "§2 row 2 sender.message.bounced: 'permanent (bool, default true)' — absent permanent still suppresses")
    void bouncedPermanentDefaultsToTrue() {
        CampaignSend send = sentSend();
        givenCampaign();
        givenSend(send, "evt-bounced-1");
        when(outboxProvider.getIfAvailable()).thenReturn(outboxWriter);

        listener.onSenderOutcome(envelope(
                "evt-bounced-1",
                DeliveryOutcomeListener.OUTCOME_BOUNCED,
                "{\"providerMessageId\":\"" + PROVIDER_MESSAGE_ID + "\",\"reason\":\"mailbox does not exist\","
                        + "\"address\":\"jane@example.com\"}"));

        assertThat(send.getStatus()).isEqualTo(SendStatus.BOUNCED);
        assertThat(send.getFailureReason()).isEqualTo("mailbox does not exist");
        verify(factPublisher)
                .sendOutcome(eq(send), eq("CONTRACT-2026"), eq(MarketingCampaignSendOutcomeV1.EVENT_TYPE_BOUNCED));

        ArgumentCaptor<String> command = ArgumentCaptor.forClass(String.class);
        verify(outboxWriter).publishRaw(eq("customer.commands.v1"), eq(PARTY.toString()), command.capture());
        JsonNode json = objectMapper.readTree(command.getValue());
        assertThat(json.path("commandType").asString()).isEqualTo("customer.suppression.add-requested");
        assertThat(json.path("payload").path("address").asString()).isEqualTo("jane@example.com");
        assertThat(json.path("payload").path("reason").asString()).isEqualTo("HARD_BOUNCE");
    }

    @Test
    @DisplayName("§2 row 2 sender.message.bounced: explicit permanent=false records BOUNCED without suppression")
    void bouncedSoftDoesNotSuppress() {
        CampaignSend send = sentSend();
        givenCampaign();
        givenSend(send, "evt-bounced-2");

        listener.onSenderOutcome(envelope(
                "evt-bounced-2",
                DeliveryOutcomeListener.OUTCOME_BOUNCED,
                "{\"providerMessageId\":\"" + PROVIDER_MESSAGE_ID + "\",\"reason\":\"mailbox full\","
                        + "\"permanent\":false,\"address\":\"jane@example.com\"}"));

        assertThat(send.getStatus()).isEqualTo(SendStatus.BOUNCED);
        verifyNoInteractions(outboxWriter);
        verify(processedEventRepository).save(any(ProcessedEvent.class));
    }

    // --- §2 row 3: sender.message.complained -> providerMessageId, reason, address, occurredAt ---
    // -> send COMPLAINED; always -> CRM suppression ---

    @Test
    @DisplayName("§2 row 3 sender.message.complained: 'always -> CRM suppression', permanent absent")
    void complainedAlwaysSuppresses() {
        CampaignSend send = sentSend();
        send.setStatus(SendStatus.DELIVERED);
        givenCampaign();
        givenSend(send, "evt-complained-1");
        when(outboxProvider.getIfAvailable()).thenReturn(outboxWriter);

        listener.onSenderOutcome(envelope(
                "evt-complained-1",
                DeliveryOutcomeListener.OUTCOME_COMPLAINED,
                "{\"providerMessageId\":\"" + PROVIDER_MESSAGE_ID + "\",\"reason\":\"marked as spam\","
                        + "\"address\":\"jane@example.com\"}"));

        assertThat(send.getStatus()).isEqualTo(SendStatus.COMPLAINED);
        verify(factPublisher)
                .sendOutcome(eq(send), eq("CONTRACT-2026"), eq(MarketingCampaignSendOutcomeV1.EVENT_TYPE_COMPLAINED));

        ArgumentCaptor<String> command = ArgumentCaptor.forClass(String.class);
        verify(outboxWriter).publishRaw(anyString(), anyString(), command.capture());
        assertThat(objectMapper
                        .readTree(command.getValue())
                        .path("payload")
                        .path("reason")
                        .asString())
                .isEqualTo("SPAM_COMPLAINT");
    }

    @Test
    @DisplayName("§2 prose: address is REQUIRED on bounce/complaint for the suppression hand-off — absent skips it")
    void complaintWithoutAddressSkipsSuppression() {
        CampaignSend send = sentSend();
        givenCampaign();
        givenSend(send, "evt-complained-2");

        listener.onSenderOutcome(envelope(
                "evt-complained-2",
                DeliveryOutcomeListener.OUTCOME_COMPLAINED,
                "{\"providerMessageId\":\"" + PROVIDER_MESSAGE_ID + "\"}"));

        assertThat(send.getStatus()).isEqualTo(SendStatus.COMPLAINED);
        verifyNoInteractions(outboxWriter);
    }

    // --- §2 row 4: sender.message.opened -> providerMessageId, occurredAt -> stamps openedAt
    // (first only) ---

    @Test
    @DisplayName("§2 row 4 sender.message.opened: stamps openedAt on first occurrence, no fact")
    void openedStampsFirstOccurrenceOnly() {
        CampaignSend send = sentSend();
        send.setStatus(SendStatus.DELIVERED);
        givenSend(send, "evt-opened-1");

        listener.onSenderOutcome(envelope(
                "evt-opened-1",
                DeliveryOutcomeListener.OUTCOME_OPENED,
                "{\"providerMessageId\":\"" + PROVIDER_MESSAGE_ID + "\",\"occurredAt\":\"2026-08-27T09:15:00Z\"}"));

        assertThat(send.getOpenedAt()).isEqualTo(Instant.parse("2026-08-27T09:15:00Z"));
        assertThat(send.getClickedAt()).isNull();
        verifyNoInteractions(factPublisher, outboxWriter);

        // A second, later open must not overwrite the first-occurrence timestamp.
        when(processedEventRepository.existsById("evt-opened-2")).thenReturn(false);
        listener.onSenderOutcome(envelope(
                "evt-opened-2",
                DeliveryOutcomeListener.OUTCOME_OPENED,
                "{\"providerMessageId\":\"" + PROVIDER_MESSAGE_ID + "\",\"occurredAt\":\"2026-08-27T10:00:00Z\"}"));

        assertThat(send.getOpenedAt()).isEqualTo(Instant.parse("2026-08-27T09:15:00Z"));
    }

    // --- §2 row 5: sender.message.clicked -> providerMessageId, occurredAt -> stamps clickedAt
    // (+ implies open) ---

    @Test
    @DisplayName("§2 row 5 sender.message.clicked: stamps clickedAt and implies open, no fact")
    void clickedStampsAndImpliesOpen() {
        CampaignSend send = sentSend();
        send.setStatus(SendStatus.DELIVERED);
        givenSend(send, "evt-clicked-1");

        listener.onSenderOutcome(envelope(
                "evt-clicked-1",
                DeliveryOutcomeListener.OUTCOME_CLICKED,
                "{\"providerMessageId\":\"" + PROVIDER_MESSAGE_ID + "\",\"occurredAt\":\"2026-08-27T09:20:00Z\"}"));

        assertThat(send.getClickedAt()).isEqualTo(Instant.parse("2026-08-27T09:20:00Z"));
        assertThat(send.getOpenedAt()).isEqualTo(Instant.parse("2026-08-27T09:20:00Z"));
        assertThat(send.getStatus()).isEqualTo(SendStatus.DELIVERED);
        verifyNoInteractions(factPublisher, outboxWriter);
    }

    // --- §2 prose: "the sender may add outcome kinds this module doesn't consume" — the five
    // documented types are exhaustive; anything else must be ignored, not mishandled. ---

    @Test
    @DisplayName("§2: an eventType outside the five documented rows is ignored, not recorded or mishandled")
    void undocumentedEventTypeIsIgnored() {
        when(processedEventRepository.existsById(anyString())).thenReturn(false);

        listener.onSenderOutcome(envelope(
                "evt-unknown-1", "sender.message.queued", "{\"providerMessageId\":\"" + PROVIDER_MESSAGE_ID + "\"}"));

        verifyNoInteractions(sendRepository, factPublisher, outboxWriter);
        verify(processedEventRepository, never()).save(any(ProcessedEvent.class));
    }
}

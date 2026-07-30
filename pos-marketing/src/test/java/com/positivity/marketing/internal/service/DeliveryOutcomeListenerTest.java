package com.positivity.marketing.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
 * Pins the outcome listener's three obligations (Story #1150): send state reflects what the
 * provider reported, bounces and complaints feed CRM suppression, and none of it double-applies
 * under at-least-once delivery.
 */
@ExtendWith(MockitoExtension.class)
class DeliveryOutcomeListenerTest {

    private static final UUID CAMPAIGN_ID = UUID.fromString("01960004-0000-7000-8000-000000000060");
    private static final UUID SEND_ID = UUID.fromString("01960004-0000-7000-8000-000000000061");
    private static final UUID PARTY = UUID.fromString("01960004-0000-7000-8000-000000000062");
    private static final String PROVIDER_MESSAGE_ID = "prov-123";

    private final Clock clock = Clock.fixed(Instant.parse("2026-07-30T12:00:00Z"), ZoneOffset.UTC);
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
                .queuedAt(Instant.parse("2026-07-30T10:00:00Z"))
                .sentAt(Instant.parse("2026-07-30T11:00:00Z"))
                .updatedAt(Instant.parse("2026-07-30T11:00:00Z"))
                .build();
    }

    private void givenSend(CampaignSend send) {
        when(processedEventRepository.existsById(anyString())).thenReturn(false);
        when(sendRepository.findByProviderMessageId(PROVIDER_MESSAGE_ID)).thenReturn(Optional.of(send));
    }

    /** Only outcomes that emit a fact look the campaign up for its code. */
    private void givenCampaign() {
        when(campaignRepository.findById(CAMPAIGN_ID))
                .thenReturn(Optional.of(Campaign.builder()
                        .campaignId(CAMPAIGN_ID)
                        .code("SPRING-2026")
                        .name("Spring")
                        .audienceType(AudienceType.INDIVIDUAL)
                        .status(CampaignStatus.SENDING)
                        .build()));
    }

    private static String envelope(String eventId, String eventType, String payloadJson) {
        return "{\"eventId\":\"" + eventId + "\",\"eventType\":\"" + eventType + "\",\"payload\":" + payloadJson + "}";
    }

    @Test
    @DisplayName("a delivery report moves SENT to DELIVERED and emits the delivered fact")
    void deliveredUpdatesSend() {
        CampaignSend send = sentSend();
        givenCampaign();
        givenSend(send);

        listener.onSenderOutcome(envelope(
                "evt-1",
                DeliveryOutcomeListener.OUTCOME_DELIVERED,
                "{\"providerMessageId\":\"prov-123\",\"occurredAt\":\"2026-07-30T11:30:00Z\"}"));

        assertThat(send.getStatus()).isEqualTo(SendStatus.DELIVERED);
        assertThat(send.getDeliveredAt()).isEqualTo(Instant.parse("2026-07-30T11:30:00Z"));
        verify(sendRepository).save(send);
        verify(factPublisher)
                .sendOutcome(eq(send), eq("SPRING-2026"), eq(MarketingCampaignSendOutcomeV1.EVENT_TYPE_DELIVERED));
        verify(processedEventRepository).save(any(ProcessedEvent.class));
    }

    @Test
    @DisplayName("a duplicate eventId is skipped entirely")
    void duplicateEventIsSkipped() {
        when(processedEventRepository.existsById("evt-1")).thenReturn(true);

        listener.onSenderOutcome(
                envelope("evt-1", DeliveryOutcomeListener.OUTCOME_DELIVERED, "{\"providerMessageId\":\"prov-123\"}"));

        verifyNoInteractions(sendRepository, factPublisher);
    }

    @Test
    @DisplayName("a hard bounce suppresses the address in CRM and emits the bounced fact")
    void hardBounceFeedsSuppression() {
        CampaignSend send = sentSend();
        givenCampaign();
        givenSend(send);
        when(outboxProvider.getIfAvailable()).thenReturn(outboxWriter);

        listener.onSenderOutcome(envelope(
                "evt-2",
                DeliveryOutcomeListener.OUTCOME_BOUNCED,
                "{\"providerMessageId\":\"prov-123\",\"reason\":\"mailbox does not exist\","
                        + "\"permanent\":true,\"address\":\"jane@example.com\"}"));

        assertThat(send.getStatus()).isEqualTo(SendStatus.BOUNCED);
        assertThat(send.getFailureReason()).isEqualTo("mailbox does not exist");
        verify(factPublisher)
                .sendOutcome(eq(send), eq("SPRING-2026"), eq(MarketingCampaignSendOutcomeV1.EVENT_TYPE_BOUNCED));

        ArgumentCaptor<String> command = ArgumentCaptor.forClass(String.class);
        verify(outboxWriter).publishRaw(eq("customer.commands.v1"), eq(PARTY.toString()), command.capture());
        JsonNode json = objectMapper.readTree(command.getValue());
        assertThat(json.path("commandType").asString()).isEqualTo("customer.suppression.add-requested");
        // Reusing the outcome eventId makes the command replay-identical under redelivery.
        assertThat(json.path("eventId").asString()).isEqualTo("evt-2");
        assertThat(json.path("payload").path("channel").asString()).isEqualTo("EMAIL");
        assertThat(json.path("payload").path("address").asString()).isEqualTo("jane@example.com");
        assertThat(json.path("payload").path("partyId").asString()).isEqualTo(PARTY.toString());
        assertThat(json.path("payload").path("reason").asString()).isEqualTo("HARD_BOUNCE");
    }

    @Test
    @DisplayName("a soft bounce records BOUNCED but must not hard-block the address")
    void softBounceDoesNotSuppress() {
        CampaignSend send = sentSend();
        givenCampaign();
        givenSend(send);

        listener.onSenderOutcome(envelope(
                "evt-3",
                DeliveryOutcomeListener.OUTCOME_BOUNCED,
                "{\"providerMessageId\":\"prov-123\",\"reason\":\"mailbox full\","
                        + "\"permanent\":false,\"address\":\"jane@example.com\"}"));

        assertThat(send.getStatus()).isEqualTo(SendStatus.BOUNCED);
        verifyNoInteractions(outboxWriter);
    }

    @Test
    @DisplayName("a complaint after delivery still lands, and always suppresses")
    void complaintAfterDeliverySuppresses() {
        CampaignSend send = sentSend();
        givenCampaign();
        send.setStatus(SendStatus.DELIVERED);
        givenSend(send);
        when(outboxProvider.getIfAvailable()).thenReturn(outboxWriter);

        listener.onSenderOutcome(envelope(
                "evt-4",
                DeliveryOutcomeListener.OUTCOME_COMPLAINED,
                "{\"providerMessageId\":\"prov-123\",\"address\":\"jane@example.com\"}"));

        assertThat(send.getStatus()).isEqualTo(SendStatus.COMPLAINED);
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
    @DisplayName("a bounce without an address updates state but skips the suppression hand-off")
    void bounceWithoutAddressSkipsSuppression() {
        CampaignSend send = sentSend();
        givenCampaign();
        givenSend(send);

        listener.onSenderOutcome(
                envelope("evt-5", DeliveryOutcomeListener.OUTCOME_BOUNCED, "{\"providerMessageId\":\"prov-123\"}"));

        assertThat(send.getStatus()).isEqualTo(SendStatus.BOUNCED);
        verifyNoInteractions(outboxWriter);
        verify(processedEventRepository).save(any(ProcessedEvent.class));
    }

    @Test
    @DisplayName("a delivery report cannot resurrect a bounced send")
    void deliveryDoesNotDowngradeBounce() {
        CampaignSend send = sentSend();
        send.setStatus(SendStatus.BOUNCED);
        givenSend(send);

        listener.onSenderOutcome(
                envelope("evt-6", DeliveryOutcomeListener.OUTCOME_DELIVERED, "{\"providerMessageId\":\"prov-123\"}"));

        assertThat(send.getStatus()).isEqualTo(SendStatus.BOUNCED);
        verify(sendRepository, never()).save(any());
        // Still recorded as processed: the outcome was seen and deliberately ignored.
        verify(processedEventRepository).save(any(ProcessedEvent.class));
    }

    @Test
    @DisplayName("an outcome for an unknown providerMessageId is retried, not dropped")
    void unknownProviderMessageIdThrows() {
        when(processedEventRepository.existsById(anyString())).thenReturn(false);
        when(sendRepository.findByProviderMessageId("prov-999")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> listener.onSenderOutcome(envelope(
                        "evt-7", DeliveryOutcomeListener.OUTCOME_DELIVERED, "{\"providerMessageId\":\"prov-999\"}")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("prov-999");
        verify(processedEventRepository, never()).save(any());
    }

    @Test
    @DisplayName("opens and clicks stamp first-engagement timestamps; a click implies an open")
    void engagementStampsTimestamps() {
        CampaignSend send = sentSend();
        send.setStatus(SendStatus.DELIVERED);
        givenSend(send);

        listener.onSenderOutcome(envelope(
                "evt-8",
                DeliveryOutcomeListener.OUTCOME_CLICKED,
                "{\"providerMessageId\":\"prov-123\",\"occurredAt\":\"2026-07-30T11:45:00Z\"}"));

        assertThat(send.getClickedAt()).isEqualTo(Instant.parse("2026-07-30T11:45:00Z"));
        assertThat(send.getOpenedAt()).isEqualTo(Instant.parse("2026-07-30T11:45:00Z"));
        assertThat(send.getStatus()).isEqualTo(SendStatus.DELIVERED);
        verifyNoInteractions(factPublisher);
    }

    @Test
    @DisplayName("outcome kinds this module doesn't consume are skipped without a processed row")
    void irrelevantOutcomeIsSkipped() {
        when(processedEventRepository.existsById(anyString())).thenReturn(false);

        listener.onSenderOutcome(envelope("evt-9", "sender.message.queued", "{\"providerMessageId\":\"prov-123\"}"));

        verifyNoInteractions(sendRepository, factPublisher);
        verify(processedEventRepository, never()).save(any());
    }
}

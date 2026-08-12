package com.positivity.marketing.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.positivity.domainevents.DomainEventEnvelope;
import com.positivity.domainevents.marketing.MarketingCampaignScheduledV1;
import com.positivity.domainevents.marketing.MarketingCampaignSendOutcomeV1;
import com.positivity.domainevents.marketing.MarketingCampaignSentV1;
import com.positivity.marketing.internal.config.OutboxEventWriter;
import com.positivity.marketing.internal.entity.Campaign;
import com.positivity.marketing.internal.entity.CampaignSend;
import com.positivity.marketing.internal.enums.AudienceType;
import com.positivity.marketing.internal.enums.CampaignChannel;
import com.positivity.marketing.internal.enums.SendStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Unit tests for {@link MarketingFactPublisher}.
 *
 * <p>
 * Two properties matter to consumers of {@code marketing.events.v1}:
 *
 * <ul>
 * <li><b>Kafka being disabled is a no-op, not a failure.</b> The outbox writer
 * bean is simply absent in that configuration, so every method must return
 * quietly rather than force each caller to guard the call.</li>
 * <li><b>Send facts are keyed on the recipient party, not the campaign.</b>
 * pos-customer keys the resulting interaction per party, and per-party ordering
 * is what a customer timeline needs; keying on the campaign would serialize a
 * whole bulk send onto one partition and interleave parties arbitrarily.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MarketingFactPublisher — outbox envelopes for marketing facts")
class MarketingFactPublisherTest {

    private static final UUID CAMPAIGN_ID = UUID.fromString("01960004-0000-7000-8000-0000000000b1");
    private static final UUID SEGMENT_ID = UUID.fromString("01960004-0000-7000-8000-0000000000b2");
    private static final UUID PARTY_ID = UUID.fromString("01960004-0000-7000-8000-0000000000b3");
    private static final UUID SEND_ID = UUID.fromString("01960004-0000-7000-8000-0000000000b4");
    private static final UUID CONTACT_ID = UUID.fromString("01960004-0000-7000-8000-0000000000b5");
    private static final Instant NOW = Instant.parse("2026-08-11T09:00:00Z");
    private static final String TOPIC = "marketing.events.v1";

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    @Mock
    private ObjectProvider<OutboxEventWriter> outboxProvider;

    @Mock
    private OutboxEventWriter outboxWriter;

    private MarketingFactPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new MarketingFactPublisher(outboxProvider, clock, TOPIC);
    }

    private void kafkaEnabled() {
        when(outboxProvider.getIfAvailable()).thenReturn(outboxWriter);
    }

    private void kafkaDisabled() {
        when(outboxProvider.getIfAvailable()).thenReturn(null);
    }

    private DomainEventEnvelope<?> captureEnvelope() {
        ArgumentCaptor<DomainEventEnvelope<?>> captor = ArgumentCaptor.captor();
        verify(outboxWriter).publish(org.mockito.ArgumentMatchers.eq(TOPIC), captor.capture());
        return captor.getValue();
    }

    private static Campaign campaign() {
        return Campaign.builder()
                .campaignId(CAMPAIGN_ID)
                .code("SPRING")
                .audienceType(AudienceType.COMMERCIAL)
                .segmentId(SEGMENT_ID)
                .scheduledAt(NOW.plusSeconds(3_600))
                .build();
    }

    private static CampaignSend send() {
        return CampaignSend.builder()
                .campaignSendId(SEND_ID)
                .campaignId(CAMPAIGN_ID)
                .recipientPartyId(PARTY_ID)
                .contactId(CONTACT_ID)
                .channel(CampaignChannel.EMAIL)
                .status(SendStatus.BOUNCED)
                .failureReason("mailbox full")
                .build();
    }

    @Nested
    @DisplayName("when Kafka is disabled")
    class KafkaDisabled {

        @Test
        @DisplayName("every publish is a quiet no-op so callers need no guard of their own")
        void allMethodsAreNoOps() {
            kafkaDisabled();

            publisher.campaignScheduled(campaign());
            publisher.campaignSent(send(), "SPRING", "Subject");
            publisher.sendOutcome(send(), "SPRING", MarketingCampaignSendOutcomeV1.EVENT_TYPE_BOUNCED);

            verifyNoInteractions(outboxWriter);
        }
    }

    @Nested
    @DisplayName("campaignScheduled")
    class CampaignScheduled {

        @Test
        @DisplayName("publishes the campaign as its own aggregate")
        void publishesScheduledFact() {
            kafkaEnabled();

            publisher.campaignScheduled(campaign());

            DomainEventEnvelope<?> envelope = captureEnvelope();
            assertThat(envelope.eventType()).isEqualTo(MarketingCampaignScheduledV1.EVENT_TYPE);
            assertThat(envelope.aggregateId()).isEqualTo(CAMPAIGN_ID);
            assertThat(envelope.sourceService()).isEqualTo("pos-marketing");
            assertThat(envelope.schemaVersion()).isEqualTo(1);
            assertThat(envelope.occurredAtUtc()).isEqualTo(NOW);
            assertThat(envelope.payload())
                    .isEqualTo(new MarketingCampaignScheduledV1(
                            CAMPAIGN_ID, "SPRING", "COMMERCIAL", SEGMENT_ID, NOW.plusSeconds(3_600)));
        }
    }

    @Nested
    @DisplayName("campaignSent")
    class CampaignSent {

        @Test
        @DisplayName("keys the fact on the recipient party so a customer timeline stays ordered")
        void keyedOnRecipientParty() {
            kafkaEnabled();

            publisher.campaignSent(send(), "SPRING", "Time for a tune-up");

            DomainEventEnvelope<?> envelope = captureEnvelope();
            assertThat(envelope.eventType()).isEqualTo(MarketingCampaignSentV1.EVENT_TYPE);
            assertThat(envelope.aggregateId()).isEqualTo(PARTY_ID).isNotEqualTo(CAMPAIGN_ID);
            assertThat(envelope.payload())
                    .isEqualTo(new MarketingCampaignSentV1(
                            SEND_ID, CAMPAIGN_ID, "SPRING", PARTY_ID, CONTACT_ID, "EMAIL", "Time for a tune-up"));
        }

        @Test
        @DisplayName("carries a null subject through for an SMS send rather than inventing one")
        void nullSubjectIsPreserved() {
            kafkaEnabled();
            CampaignSend sms = send();
            sms.setChannel(CampaignChannel.SMS);

            publisher.campaignSent(sms, "SPRING", null);

            assertThat(captureEnvelope().payload())
                    .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.type(MarketingCampaignSentV1.class))
                    .satisfies(payload -> {
                        assertThat(payload.channel()).isEqualTo("SMS");
                        assertThat(payload.subject()).isNull();
                    });
        }
    }

    @Nested
    @DisplayName("sendOutcome")
    class SendOutcome {

        @Test
        @DisplayName("emits the caller's event type while reporting the send's own status")
        void eventTypeMirrorsTheTransition() {
            kafkaEnabled();

            publisher.sendOutcome(send(), "SPRING", MarketingCampaignSendOutcomeV1.EVENT_TYPE_BOUNCED);

            DomainEventEnvelope<?> envelope = captureEnvelope();
            assertThat(envelope.eventType()).isEqualTo(MarketingCampaignSendOutcomeV1.EVENT_TYPE_BOUNCED);
            assertThat(envelope.aggregateId()).isEqualTo(PARTY_ID);
            assertThat(envelope.payload())
                    .isEqualTo(new MarketingCampaignSendOutcomeV1(
                            SEND_ID, CAMPAIGN_ID, "SPRING", PARTY_ID, "EMAIL", "BOUNCED", "mailbox full"));
        }

        @Test
        @DisplayName("does not publish when the outbox writer is unavailable")
        void noOutboxNoPublish() {
            kafkaDisabled();

            publisher.sendOutcome(send(), "SPRING", MarketingCampaignSendOutcomeV1.EVENT_TYPE_DELIVERED);

            verify(outboxWriter, never()).publish(anyString(), any());
        }
    }
}

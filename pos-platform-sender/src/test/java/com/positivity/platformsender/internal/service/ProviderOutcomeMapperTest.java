package com.positivity.platformsender.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.domainevents.sender.SenderMessageOutcomeV1;
import com.positivity.platformsender.internal.service.ProviderOutcomeMapper.Ignored;
import com.positivity.platformsender.internal.service.ProviderOutcomeMapper.Mapped;
import com.positivity.platformsender.internal.service.ProviderOutcomeMapper.Mapping;
import com.positivity.platformsender.internal.service.ProviderOutcomeMapper.Unusable;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@link ProviderOutcomeMapper} against fixtures shaped like the events SES configuration-set event
 * publishing and AWS End User Messaging SMS event destinations deliver through SNS to SQS. Each
 * case pins one row of the module README's mapping table.
 */
@DisplayName("ProviderOutcomeMapper — SES and SMS events to FI-2 outcomes")
class ProviderOutcomeMapperTest {

    private static final UUID TENANT = UUID.fromString("01900000-0000-7000-8000-000000000002");
    private static final UUID MESSAGE_ID = UUID.fromString("01990000-0000-7000-8000-0000000000c1");
    private static final String SNS_MESSAGE_ID = "6f4c9e3a-1d2b-4c5d-8e9f-0a1b2c3d4e5f";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final ProviderOutcomeMapper mapper = new ProviderOutcomeMapper(JSON);

    /** An SES event-publishing record for the sender's tagged message. */
    private static String ses(String eventType, String detailField, String detail) {
        return """
                {"eventType":"%s",
                 "mail":{"timestamp":"2026-10-03T12:00:00.000Z","source":"no-reply@durionpos.org",
                         "messageId":"ses-1","destination":["ada@example.com"],
                         "tags":{"ses:configuration-set":["durion-email"],
                                 "pos-tenant-id":["%s"],"pos-message-id":["%s"],"pos-campaign":["SPRING24"]}}%s}
                """.formatted(
                        eventType, TENANT, MESSAGE_ID, detailField == null ? "" : ",\"" + detailField + "\":" + detail);
    }

    /** An End User Messaging SMS event for the sender's message. */
    private static String sms(String eventType, boolean isFinal, String description) {
        return """
                {"eventType":"%s","eventVersion":"1.0","eventTimestamp":1791115200000,"isFinal":%s,
                 "originationPhoneNumber":"+18445550100","destinationPhoneNumber":"+15550100100",
                 "isoCountryCode":"US","messageId":"sms-1","messageType":"PROMOTIONAL",
                 "messageStatus":"%s","messageStatusDescription":"%s",
                 "context":{"pos-tenant-id":"%s","pos-message-id":"%s","pos-campaign":"SPRING24"},
                 "totalMessageParts":1,"totalMessagePrice":0.00581,"totalCarrierFee":0.00302}
                """.formatted(eventType, isFinal, eventType.replace("TEXT_", ""), description, TENANT, MESSAGE_ID);
    }

    /** The event as SNS delivers it to SQS without raw message delivery. */
    private static String viaSns(String event) {
        ObjectNode notification = JSON.createObjectNode();
        notification.put("Type", "Notification");
        notification.put("MessageId", SNS_MESSAGE_ID);
        notification.put("TopicArn", "arn:aws:sns:us-east-1:123456789012:durion-sender-events");
        notification.put("Message", event);
        notification.put("Timestamp", "2026-10-03T12:00:01.000Z");
        return JSON.writeValueAsString(notification);
    }

    private Mapped mapped(String body) {
        Mapping mapping = mapper.map(body);
        assertThat(mapping).isInstanceOf(Mapped.class);
        return (Mapped) mapping;
    }

    @Nested
    @DisplayName("SES")
    class Ses {

        @Test
        @DisplayName("Delivery is delivered, under the tagged tenant, deduplicated by the SNS message id")
        void delivery() {
            Mapped mapped = mapped(viaSns(ses(
                    "Delivery",
                    "delivery",
                    "{\"timestamp\":\"2026-10-03T12:00:02.000Z\",\"recipients\":[\"ada@example.com\"]}")));

            assertThat(mapped.dedupeKey()).isEqualTo(SNS_MESSAGE_ID);
            assertThat(mapped.tenantId()).isEqualTo(TENANT);
            assertThat(mapped.eventType()).isEqualTo(SenderMessageOutcomeV1.EVENT_TYPE_DELIVERED);
            SenderMessageOutcomeV1 outcome = mapped.outcome();
            assertThat(outcome.messageId()).isEqualTo(MESSAGE_ID);
            assertThat(outcome.channel()).isEqualTo("EMAIL");
            assertThat(outcome.providerMessageId()).isEqualTo("ses-1");
            assertThat(outcome.occurredAt()).isEqualTo(Instant.parse("2026-10-03T12:00:02Z"));
            assertThat(outcome.permanent()).isNull();
            assertThat(outcome.address()).isNull();
        }

        @Test
        @DisplayName("a Permanent bounce is a hard bounce carrying the normalized address")
        void permanentBounce() {
            SenderMessageOutcomeV1 outcome =
                    mapped(viaSns(ses("Bounce", "bounce", """
                    {"bounceType":"Permanent","bounceSubType":"General","timestamp":"2026-10-03T12:00:03.000Z",
                     "bouncedRecipients":[{"emailAddress":"Ada@Example.com","action":"failed","status":"5.1.1",
                                           "diagnosticCode":"smtp; 550 5.1.1 user unknown"}]}
                    """))).outcome();

            assertThat(outcome.permanent()).isTrue();
            assertThat(outcome.address()).isEqualTo("ada@example.com");
            assertThat(outcome.reason()).isEqualTo("Permanent: General: smtp; 550 5.1.1 user unknown");
            assertThat(outcome.occurredAt()).isEqualTo(Instant.parse("2026-10-03T12:00:03Z"));
        }

        @ParameterizedTest
        @ValueSource(strings = {"Transient", "Undetermined"})
        @DisplayName("a Transient or Undetermined bounce is a soft bounce")
        void softBounce(String bounceType) {
            SenderMessageOutcomeV1 outcome =
                    mapped(ses("Bounce", "bounce", """
                    {"bounceType":"%s","bounceSubType":"MailboxFull","timestamp":"2026-10-03T12:00:03.000Z",
                     "bouncedRecipients":[{"emailAddress":"ada@example.com"}]}
                    """.formatted(bounceType))).outcome();

            assertThat(outcome.permanent()).isFalse();
        }

        @Test
        @DisplayName("a Complaint is complained, with the feedback type as reason")
        void complaint() {
            Mapped mapped = mapped(viaSns(ses("Complaint", "complaint", """
                    {"complainedRecipients":[{"emailAddress":"ada@example.com"}],
                     "timestamp":"2026-10-03T13:00:00.000Z","complaintFeedbackType":"abuse"}
                    """)));

            assertThat(mapped.eventType()).isEqualTo(SenderMessageOutcomeV1.EVENT_TYPE_COMPLAINED);
            assertThat(mapped.outcome().reason()).isEqualTo("abuse");
            assertThat(mapped.outcome().address()).isEqualTo("ada@example.com");
        }

        @Test
        @DisplayName("Open and Click are engagement")
        void engagement() {
            assertThat(mapped(ses("Open", "open", "{\"timestamp\":\"2026-10-03T14:00:00.000Z\"}"))
                            .eventType())
                    .isEqualTo(SenderMessageOutcomeV1.EVENT_TYPE_OPENED);
            assertThat(mapped(ses("Click", "click", "{\"timestamp\":\"2026-10-03T14:01:00.000Z\",\"link\":\"x\"}"))
                            .eventType())
                    .isEqualTo(SenderMessageOutcomeV1.EVENT_TYPE_CLICKED);
        }

        @Test
        @DisplayName("a Reject is a soft bounce: the address is not at fault")
        void reject() {
            Mapped mapped = mapped(ses("Reject", "reject", "{\"reason\":\"Bad content\"}"));

            assertThat(mapped.eventType()).isEqualTo(SenderMessageOutcomeV1.EVENT_TYPE_BOUNCED);
            assertThat(mapped.outcome().permanent()).isFalse();
            assertThat(mapped.outcome().reason()).isEqualTo("Reject: Bad content");
            assertThat(mapped.outcome().occurredAt()).isEqualTo(Instant.parse("2026-10-03T12:00:00Z"));
        }

        @ParameterizedTest
        @ValueSource(strings = {"Send", "DeliveryDelay", "Subscription"})
        @DisplayName("interim and unrelated SES events are ignored")
        void interimIgnored(String eventType) {
            assertThat(mapper.map(ses(eventType, null, null))).isInstanceOf(Ignored.class);
        }

        @Test
        @DisplayName("raw message delivery has no SNS id; the caller dedupes on the queue message id")
        void rawDelivery() {
            assertThat(mapped(ses("Delivery", "delivery", "{\"timestamp\":\"2026-10-03T12:00:02.000Z\"}"))
                            .dedupeKey())
                    .isNull();
        }

        @Test
        @DisplayName("an SES event without the sender's tags is unusable")
        void untagged() {
            String untagged = ses("Delivery", "delivery", "{\"timestamp\":\"2026-10-03T12:00:02.000Z\"}")
                    .replace("\"pos-tenant-id\":[\"" + TENANT + "\"],", "");

            assertThat(mapper.map(untagged)).isInstanceOf(Unusable.class);
        }

        @Test
        @DisplayName("an identity notification (notificationType, no eventType) is unusable")
        void identityNotification() {
            assertThat(mapper.map("{\"notificationType\":\"Bounce\",\"mail\":{\"messageId\":\"ses-1\"}}"))
                    .isInstanceOf(Unusable.class);
        }
    }

    @Nested
    @DisplayName("SMS")
    class Sms {

        @Test
        @DisplayName("TEXT_DELIVERED is delivered, at the event timestamp")
        void delivered() {
            Mapped mapped = mapped(viaSns(sms("TEXT_DELIVERED", true, "Message has been accepted by phone")));

            assertThat(mapped.eventType()).isEqualTo(SenderMessageOutcomeV1.EVENT_TYPE_DELIVERED);
            assertThat(mapped.tenantId()).isEqualTo(TENANT);
            assertThat(mapped.outcome().channel()).isEqualTo("SMS");
            assertThat(mapped.outcome().providerMessageId()).isEqualTo("sms-1");
            assertThat(mapped.outcome().occurredAt()).isEqualTo(Instant.ofEpochMilli(1791115200000L));
        }

        @Test
        @DisplayName("a final TEXT_SUCCESSFUL (no delivery receipts) is delivered; a non-final one is ignored")
        void successful() {
            assertThat(mapped(sms("TEXT_SUCCESSFUL", true, "Accepted by carrier"))
                            .eventType())
                    .isEqualTo(SenderMessageOutcomeV1.EVENT_TYPE_DELIVERED);
            assertThat(mapper.map(sms("TEXT_SUCCESSFUL", false, "Accepted by carrier")))
                    .isInstanceOf(Ignored.class);
        }

        @Test
        @DisplayName("TEXT_INVALID is a hard bounce carrying the E.164 number")
        void invalidIsHard() {
            SenderMessageOutcomeV1 outcome = mapped(sms("TEXT_INVALID", true, "Invalid destination phone number"))
                    .outcome();

            assertThat(outcome.permanent()).isTrue();
            assertThat(outcome.address()).isEqualTo("+15550100100");
            assertThat(outcome.reason()).isEqualTo("TEXT_INVALID: Invalid destination phone number");
        }

        @ParameterizedTest
        @ValueSource(
                strings = {
                    "TEXT_UNREACHABLE",
                    "TEXT_CARRIER_UNREACHABLE",
                    "TEXT_BLOCKED",
                    "TEXT_CARRIER_BLOCKED",
                    "TEXT_SPAM",
                    "TEXT_TTL_EXPIRED",
                    "TEXT_UNKNOWN",
                    "TEXT_INVALID_MESSAGE"
                })
        @DisplayName("every other failed SMS is a soft bounce")
        void softBounces(String eventType) {
            Mapped mapped = mapped(sms(eventType, true, "failed"));

            assertThat(mapped.eventType()).isEqualTo(SenderMessageOutcomeV1.EVENT_TYPE_BOUNCED);
            assertThat(mapped.outcome().permanent()).isFalse();
        }

        @ParameterizedTest
        @ValueSource(strings = {"TEXT_QUEUED", "TEXT_PENDING", "TEXT_SENT"})
        @DisplayName("interim SMS statuses are ignored")
        void interimIgnored(String eventType) {
            assertThat(mapper.map(sms(eventType, false, "in progress"))).isInstanceOf(Ignored.class);
        }

        @Test
        @DisplayName("an SMS event without the sender's context is unusable")
        void untagged() {
            String untagged = sms("TEXT_DELIVERED", true, "ok").replace("\"pos-tenant-id\":\"" + TENANT + "\",", "");

            assertThat(mapper.map(untagged)).isInstanceOf(Unusable.class);
        }
    }

    @Test
    @DisplayName("a body that is not JSON, or JSON that is neither shape, is unusable")
    void notAnEvent() {
        assertThat(mapper.map("not json")).isInstanceOf(Unusable.class);
        assertThat(mapper.map("{\"hello\":\"world\"}")).isInstanceOf(Unusable.class);
        assertThat(mapper.map("{\"Type\":\"Notification\",\"MessageId\":\"x\",\"Message\":\"not json\"}"))
                .isInstanceOf(Unusable.class);
    }
}

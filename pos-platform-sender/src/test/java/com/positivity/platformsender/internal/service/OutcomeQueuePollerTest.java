package com.positivity.platformsender.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.domainevents.sender.SenderMessageOutcomeV1;
import com.positivity.platformsender.internal.config.SenderProperties;
import com.positivity.platformsender.internal.service.ProviderOutcomeMapper.Ignored;
import com.positivity.platformsender.internal.service.ProviderOutcomeMapper.Mapped;
import com.positivity.platformsender.internal.service.ProviderOutcomeMapper.Unusable;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse;

/**
 * {@link OutcomeQueuePoller}: deletion is the acknowledgement. A relayed or meaningless message is
 * deleted; an unusable one, or one whose relay failed, stays for redelivery and the queue's
 * dead-letter redrive.
 */
@DisplayName("OutcomeQueuePoller — acknowledge only what was handled")
class OutcomeQueuePollerTest {

    private static final UUID TENANT = UUID.fromString("01900000-0000-7000-8000-000000000002");

    private final SqsClient sqs = mock(SqsClient.class);
    private final ProviderOutcomeMapper mapper = mock(ProviderOutcomeMapper.class);
    private final OutcomeRelay relay = mock(OutcomeRelay.class);
    private OutcomeQueuePoller poller;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        poller =
                new OutcomeQueuePoller(sqs, mapper, relay, TestSenderProperties.defaults(), mock(ObjectProvider.class));
    }

    private static Message message(String body) {
        return Message.builder()
                .messageId("sqs-1")
                .receiptHandle("receipt-1")
                .body(body)
                .build();
    }

    private static Mapped mapped(String dedupeKey) {
        return new Mapped(
                dedupeKey,
                TENANT,
                SenderMessageOutcomeV1.EVENT_TYPE_DELIVERED,
                new SenderMessageOutcomeV1(
                        UUID.randomUUID(), "EMAIL", "ses-1", Instant.parse("2026-10-03T12:00:00Z"), null, null, null));
    }

    @Test
    @DisplayName("long-polls the configured queue and relays then deletes each message")
    void relaysAndDeletes() {
        when(sqs.receiveMessage(any(ReceiveMessageRequest.class)))
                .thenReturn(ReceiveMessageResponse.builder()
                        .messages(message("delivery"))
                        .build());
        Mapped mapped = mapped("sns-1");
        when(mapper.map("delivery")).thenReturn(mapped);

        poller.poll();

        ArgumentCaptor<ReceiveMessageRequest> received = ArgumentCaptor.captor();
        verify(sqs).receiveMessage(received.capture());
        assertThat(received.getValue().queueUrl()).isEqualTo("https://sqs.us-east-1.amazonaws.com/123/outcomes");
        assertThat(received.getValue().waitTimeSeconds()).isEqualTo(20);
        assertThat(received.getValue().maxNumberOfMessages()).isEqualTo(10);
        verify(relay).relay("sns-1", mapped);
        ArgumentCaptor<DeleteMessageRequest> deleted = ArgumentCaptor.captor();
        verify(sqs).deleteMessage(deleted.capture());
        assertThat(deleted.getValue().receiptHandle()).isEqualTo("receipt-1");
    }

    @Test
    @DisplayName("without an SNS id the queue's own message id is the dedupe key")
    void fallsBackToQueueMessageId() {
        Mapped mapped = mapped(null);
        when(mapper.map("raw")).thenReturn(mapped);

        poller.handle(message("raw"));

        verify(relay).relay("sqs-1", mapped);
    }

    @Test
    @DisplayName("an ignored event is deleted without relaying")
    void ignoredIsDeleted() {
        when(mapper.map("send")).thenReturn(new Ignored("SES Send is not an FI-2 outcome"));

        poller.handle(message("send"));

        verify(relay, never()).relay(any(), any());
        verify(sqs).deleteMessage(any(DeleteMessageRequest.class));
    }

    @Test
    @DisplayName("an unusable event stays on the queue for the dead-letter redrive")
    void unusableStays() {
        when(mapper.map("junk")).thenReturn(new Unusable("not JSON"));

        poller.handle(message("junk"));

        verify(sqs, never()).deleteMessage(any(DeleteMessageRequest.class));
    }

    @Test
    @DisplayName("a failed relay leaves the message for redelivery")
    void failedRelayStays() {
        Mapped mapped = mapped("sns-1");
        when(mapper.map("delivery")).thenReturn(mapped);
        when(relay.relay(eq("sns-1"), any())).thenThrow(new IllegalStateException("database down"));

        poller.handle(message("delivery"));

        verify(sqs, never()).deleteMessage(any(DeleteMessageRequest.class));
    }

    @Test
    @DisplayName("a failed receive is logged and retried on the next poll")
    void failedReceive() {
        when(sqs.receiveMessage(any(ReceiveMessageRequest.class))).thenThrow(SdkClientException.create("timeout"));

        poller.poll();

        verify(mapper, never()).map(any());
    }

    @Test
    @DisplayName("enabling the poll without a queue URL fails at startup")
    @SuppressWarnings("unchecked")
    void requiresQueueUrl() {
        SenderProperties defaults = TestSenderProperties.defaults();
        SenderProperties noQueue = new SenderProperties(
                defaults.apiSecret(),
                defaults.transport(),
                defaults.aws(),
                defaults.email(),
                defaults.sms(),
                new SenderProperties.Outcomes(true, " ", 20, 10));

        assertThatIllegalStateException()
                .isThrownBy(() -> new OutcomeQueuePoller(sqs, mapper, relay, noQueue, mock(ObjectProvider.class)));
    }
}

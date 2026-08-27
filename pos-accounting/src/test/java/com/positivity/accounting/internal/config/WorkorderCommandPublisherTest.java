package com.positivity.accounting.internal.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.positivity.shared.id.UUIDv7Generator;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Unit tests for {@link WorkorderCommandPublisher} (#1537 D1): the regeneration flow needs a
 * generated {@code commandId} back from the publisher so it can persist it on the tracking row
 * before the workorder fact resolving it ever arrives.
 */
class WorkorderCommandPublisherTest {

    private static final UUID WORKORDER_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final String TOPIC = "workorder.commands.v1";

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);

    private final ObjectMapper objectMapper = new ObjectMapper();

    private WorkorderCommandPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new WorkorderCommandPublisher(kafkaTemplate, objectMapper);
        ReflectionTestUtils.setField(publisher, "workorderCommandsTopic", TOPIC);
        ReflectionTestUtils.setField(publisher, "sendTimeoutMs", 10_000L);
    }

    @SuppressWarnings("unchecked")
    private void stubSuccessfulSend() {
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));
    }

    @Test
    @DisplayName("Returns a generated UUIDv7 commandId and stamps it on the published envelope")
    void generatesAndReturnsCommandId() {
        stubSuccessfulSend();

        UUID commandId = publisher.requestInvoiceRegeneration(WORKORDER_ID, "idem-1", "alice");

        assertThat(commandId).isNotNull();
        assertThat(UUIDv7Generator.isUUIDv7(commandId)).isTrue();
    }

    @Test
    @DisplayName("Publishes the command envelope keyed by workorderId with commandId, commandType and payload")
    void publishesEnvelopeShape() {
        stubSuccessfulSend();

        UUID commandId = publisher.requestInvoiceRegeneration(WORKORDER_ID, "idem-1", "alice");

        org.mockito.ArgumentCaptor<String> body = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(kafkaTemplate).send(eq(TOPIC), eq(WORKORDER_ID.toString()), body.capture());

        JsonNode envelope = objectMapper.readTree(body.getValue());
        assertThat(envelope.path("commandType").stringValue())
                .isEqualTo(WorkorderCommandPublisher.INVOICE_REGENERATE_COMMAND_TYPE);
        assertThat(envelope.path("commandId").stringValue()).isEqualTo(commandId.toString());
        assertThat(envelope.path("payload").path("workorderId").stringValue()).isEqualTo(WORKORDER_ID.toString());
        assertThat(envelope.path("payload").path("idempotencyKey").stringValue())
                .isEqualTo("idem-1");
        assertThat(envelope.path("payload").path("requestedBy").stringValue()).isEqualTo("alice");
    }

    @Test
    @DisplayName("Throws when the broker does not acknowledge the send")
    void throwsOnSendFailure() {
        when(kafkaTemplate.send(anyString(), anyString(), any(String.class)))
                .thenThrow(new org.apache.kafka.common.errors.TimeoutException("no broker"));

        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> publisher.requestInvoiceRegeneration(WORKORDER_ID, "idem-1", "alice"));
    }
}

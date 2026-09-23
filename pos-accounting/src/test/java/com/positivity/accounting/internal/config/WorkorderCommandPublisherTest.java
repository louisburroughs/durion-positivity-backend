package com.positivity.accounting.internal.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.shared.id.UUIDv7Generator;
import com.positivity.tenancy.TenancyProperties;
import com.positivity.tenancy.TenantContext;
import com.positivity.tenancy.TenantResolver;
import com.positivity.tenancy.kafka.TenantKafkaHeaders;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
    /** Deliberately not the transitional default tenant, so a header carrying it proves propagation. */
    private static final UUID TENANT_ID = UUID.fromString("01900000-0000-7000-8000-0000000000b2");

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);

    private final ObjectMapper objectMapper = new ObjectMapper();

    private WorkorderCommandPublisher publisher;

    @BeforeEach
    void setUp() {
        TenantContext.bind(TENANT_ID);
        publisher =
                new WorkorderCommandPublisher(kafkaTemplate, objectMapper, new TenantResolver(new TenancyProperties()));
        ReflectionTestUtils.setField(publisher, "workorderCommandsTopic", TOPIC);
        ReflectionTestUtils.setField(publisher, "sendTimeoutMs", 10_000L);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @SuppressWarnings("unchecked")
    private void stubSuccessfulSend() {
        when(kafkaTemplate.send(any(ProducerRecord.class)))
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

        ProducerRecord<String, String> record = sentRecord();
        assertThat(record.topic()).isEqualTo(TOPIC);
        assertThat(record.key()).isEqualTo(WORKORDER_ID.toString());

        JsonNode envelope = objectMapper.readTree(record.value());
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
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenThrow(new org.apache.kafka.common.errors.TimeoutException("no broker"));

        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> publisher.requestInvoiceRegeneration(WORKORDER_ID, "idem-1", "alice"));
    }

    /**
     * #2147: pos-workorder's record interceptor binds the tenant from this header and falls back to
     * the transitional default when it is absent, so a bare record would regenerate the invoice
     * under the wrong tenant.
     */
    @Test
    @DisplayName("The command record carries the requesting tenant's header")
    void commandRecordCarriesTheRequestingTenant() {
        stubSuccessfulSend();

        publisher.requestInvoiceRegeneration(WORKORDER_ID, "idem-1", "alice");

        assertThat(TenantKafkaHeaders.read(sentRecord().headers())).contains(TENANT_ID);
    }

    @Test
    @DisplayName("With no tenant bound and no default configured, nothing is published")
    void noResolvableTenantPublishesNothing() {
        TenantContext.clear();

        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> publisher.requestInvoiceRegeneration(WORKORDER_ID, "idem-1", "alice"));

        verify(kafkaTemplate, never()).send(any(ProducerRecord.class));
    }

    @SuppressWarnings("unchecked")
    private ProducerRecord<String, String> sentRecord() {
        ArgumentCaptor<ProducerRecord<String, String>> captor = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate).send(captor.capture());
        return captor.getValue();
    }
}

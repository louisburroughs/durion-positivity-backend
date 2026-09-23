package com.positivity.workorder.internal.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.shared.dto.InvoiceCreationRequest;
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
import tools.jackson.databind.ObjectMapper;

/**
 * Pins the tenant header on {@link InvoiceCommandPublisher}'s records (#2147): pos-invoice's
 * record interceptor binds the tenant from it and falls back to the transitional default when it
 * is absent, so a bare record would draft the invoice under the wrong tenant.
 */
class InvoiceCommandPublisherTest {

    private static final String TOPIC = "invoice.commands.v1";
    private static final UUID WORKORDER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    /** Deliberately not the transitional default tenant, so a header carrying it proves propagation. */
    private static final UUID TENANT_ID = UUID.fromString("01900000-0000-7000-8000-0000000000b2");

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);

    private InvoiceCommandPublisher publisher;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        TenantContext.bind(TENANT_ID);
        publisher = new InvoiceCommandPublisher(
                kafkaTemplate, new ObjectMapper(), new TenantResolver(new TenancyProperties()));
        ReflectionTestUtils.setField(publisher, "invoiceCommandsTopic", TOPIC);
        ReflectionTestUtils.setField(publisher, "sendTimeoutMs", 10_000L);
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("the command record carries the requesting tenant's header, keyed on the workorder")
    @SuppressWarnings("unchecked")
    void commandRecordCarriesTheRequestingTenant() {
        publisher.requestInvoiceGeneration(request(), "idem-1");

        ArgumentCaptor<ProducerRecord<String, String>> captor = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate).send(captor.capture());
        ProducerRecord<String, String> record = captor.getValue();
        assertThat(record.topic()).isEqualTo(TOPIC);
        assertThat(record.key()).isEqualTo(WORKORDER_ID.toString());
        assertThat(TenantKafkaHeaders.read(record.headers())).contains(TENANT_ID);
    }

    @Test
    @DisplayName("with no tenant bound and no default configured, nothing is published")
    @SuppressWarnings("unchecked")
    void noResolvableTenantPublishesNothing() {
        TenantContext.clear();

        assertThatThrownBy(() -> publisher.requestInvoiceGeneration(request(), "idem-1"))
                .isInstanceOf(IllegalStateException.class);

        verify(kafkaTemplate, never()).send(any(ProducerRecord.class));
    }

    private static InvoiceCreationRequest request() {
        return InvoiceCreationRequest.builder().workorderId(WORKORDER_ID).build();
    }
}

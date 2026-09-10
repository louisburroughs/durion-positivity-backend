package com.positivity.tenancy.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.positivity.tenancy.TenancyProperties;
import com.positivity.tenancy.TenantContext;
import com.positivity.tenancy.TenantContextMissingException;
import com.positivity.tenancy.TenantHeaders;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class TenantRecordInterceptorTest {

    private static final UUID A = UUID.fromString("01900000-0000-7000-8000-000000000001");
    private static final UUID DEFAULT = UUID.fromString("01900000-0000-7000-8000-000000000009");

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private static ConsumerRecord<String, String> record(byte @org.jspecify.annotations.Nullable [] tenantHeader) {
        ConsumerRecord<String, String> record = new ConsumerRecord<>("location.events.v1", 0, 1L, "key", "{}");
        if (tenantHeader != null) {
            record.headers().add(TenantHeaders.KAFKA_TENANT_ID, tenantHeader);
        }
        return record;
    }

    @Test
    void bindsTheHeaderTenantAndClearsAfterTheRecord() {
        TenantRecordInterceptor<String, String> interceptor = new TenantRecordInterceptor<>(new TenancyProperties());
        ConsumerRecord<String, String> record = record(A.toString().getBytes(StandardCharsets.UTF_8));

        assertThat(interceptor.intercept(record, null)).isSameAs(record);
        assertThat(TenantContext.require()).isEqualTo(A);

        interceptor.afterRecord(record, null);
        assertThat(TenantContext.current()).isEmpty();
    }

    @Test
    void strictModeRefusesARecordWithoutAHeader() {
        TenantRecordInterceptor<String, String> interceptor = new TenantRecordInterceptor<>(new TenancyProperties());

        assertThatThrownBy(() -> interceptor.intercept(record(null), null))
                .isInstanceOf(TenantContextMissingException.class);
        assertThat(TenantContext.current()).isEmpty();
    }

    @Test
    void transitionalDefaultCoversLegacyProducers() {
        TenancyProperties properties = new TenancyProperties();
        properties.setDefaultTenantId(DEFAULT);
        TenantRecordInterceptor<String, String> interceptor = new TenantRecordInterceptor<>(properties);

        interceptor.intercept(record(null), null);
        assertThat(TenantContext.require()).isEqualTo(DEFAULT);
        interceptor.intercept(record("not-a-uuid".getBytes(StandardCharsets.UTF_8)), null);
        assertThat(TenantContext.require())
                .as("a malformed header counts as absent")
                .isEqualTo(DEFAULT);
    }

    @Test
    void producerHelperStampsTheHeader() {
        ProducerRecord<String, String> produced = TenantKafkaHeaders.record("location.events.v1", "k", "{}", A);
        assertThat(TenantKafkaHeaders.read(produced.headers())).contains(A);
    }
}

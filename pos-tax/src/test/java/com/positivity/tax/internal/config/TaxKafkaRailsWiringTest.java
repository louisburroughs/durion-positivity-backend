package com.positivity.tax.internal.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.tenancy.kafka.TenantRecordInterceptor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * CAP:550 S32c: pos-tax's Kafka rails are wired as a deployed profile runs them. {@code local-kafka} switches the
 * {@code @KafkaRails} beans on over the H2 {@code test} profile; listeners never start and the scheduled drains wait
 * out the test, so no broker is needed. The listener container factory carries the tenant {@code RecordInterceptor}
 * (ADR-0062 §3) and the module's dead-letter error handler (ADR-0044 §4).
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "spring.kafka.listener.auto-startup=false",
            "spring.kafka.producer.properties.max.block.ms=100",
            "pos.tax.outbox.poll-interval-ms=3600000",
            "pos.tax.manifest.poll-interval-ms=3600000"
        })
@ActiveProfiles({"test", "local-kafka"})
@DisplayName("pos-tax Kafka rails wiring (CAP:550 S32c)")
class TaxKafkaRailsWiringTest {

    @Autowired
    private ApplicationContext context;

    @Test
    @DisplayName("the outbox publisher, manifest, replay listener and error handler exist; listeners bind the tenant")
    void railsAreWired() {
        assertThat(context.getBeanNamesForType(OutboxPublisher.class)).hasSize(1);
        assertThat(context.getBeanNamesForType(ManifestPublisher.class)).hasSize(1);
        assertThat(context.getBeanNamesForType(TaxCommandListener.class)).hasSize(1);
        assertThat(context.getBeanNamesForType(OutboxEventWriter.class)).hasSize(1);
        DefaultErrorHandler errorHandler = context.getBean(DefaultErrorHandler.class);

        ConcurrentKafkaListenerContainerFactory<?, ?> factory =
                context.getBean("kafkaListenerContainerFactory", ConcurrentKafkaListenerContainerFactory.class);
        assertThat(ReflectionTestUtils.getField(factory, "recordInterceptor"))
                .isInstanceOf(TenantRecordInterceptor.class);
        assertThat(ReflectionTestUtils.getField(factory, "commonErrorHandler")).isSameAs(errorHandler);
    }
}

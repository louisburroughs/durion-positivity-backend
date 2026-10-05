package com.positivity.platformsender.tenancy;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.platformsender.internal.config.OutboxEventWriter;
import com.positivity.platformsender.internal.service.OutcomeQueuePoller;
import com.positivity.platformsender.internal.service.OutcomeRelay;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.TestPropertySource;

/**
 * Outcomes enabled under the broker-less {@code pg} profile must still start the context: the
 * relay and poller need the outbox writer, so they are {@code @KafkaRails} beans and stay out
 * together with it rather than failing wiring.
 */
@DisplayName("Outcomes enabled without the Kafka rails (Postgres)")
@TestPropertySource(
        properties = {
            "pos.platform-sender.outcomes.enabled=true",
            "pos.platform-sender.outcomes.queue-url=http://127.0.0.1:1/queue"
        })
class OutcomesWithoutKafkaRailsContextIT extends PostgresTenancyTestBase {

    @Autowired
    private ApplicationContext context;

    @Test
    @DisplayName("context starts and the outcome beans are absent along with the outbox writer")
    void contextStartsWithoutOutcomeBeans() {
        assertThat(context.getBeanNamesForType(OutboxEventWriter.class)).isEmpty();
        assertThat(context.getBeanNamesForType(OutcomeRelay.class)).isEmpty();
        assertThat(context.getBeanNamesForType(OutcomeQueuePoller.class)).isEmpty();
    }
}

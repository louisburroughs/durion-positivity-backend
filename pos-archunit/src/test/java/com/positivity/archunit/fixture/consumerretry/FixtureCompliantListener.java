package com.positivity.archunit.fixture.consumerretry;

import com.positivity.tenancy.kafka.RetryableConsumerFailures;
import org.springframework.kafka.annotation.KafkaListener;

/** The #2355 consumer shape: classify first, then handle as permanent. */
public class FixtureCompliantListener {

    @KafkaListener(topics = "fixture.consumer-retry.v1", groupId = "fixture")
    public void onEvent(String message) {
        try {
            apply(message);
        } catch (Exception e) {
            if (RetryableConsumerFailures.isRetryable(e)) {
                throw e;
            }
            // permanent: logged and dropped
        }
    }

    void apply(String message) {
        // stands in for the handler's database work
    }
}

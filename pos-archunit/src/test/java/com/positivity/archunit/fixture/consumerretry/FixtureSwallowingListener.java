package com.positivity.archunit.fixture.consumerretry;

import org.springframework.kafka.annotation.KafkaListener;

/** Swallows whatever its handler throws, retryable or not. */
public class FixtureSwallowingListener {

    @KafkaListener(topics = "fixture.consumer-retry.v1", groupId = "fixture")
    public void onEvent(String message) {
        try {
            apply(message);
        } catch (Exception e) {
            // logged and dropped
        }
    }

    /** A broad catch around nothing but JDK parsing guards no retryable work and is left alone. */
    public int parseOnly(String message) {
        try {
            return Integer.parseInt(message);
        } catch (Exception e) {
            return 0;
        }
    }

    void apply(String message) {
        // stands in for the handler's database work
    }
}

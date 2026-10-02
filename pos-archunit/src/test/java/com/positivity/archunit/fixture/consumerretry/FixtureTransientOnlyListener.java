package com.positivity.archunit.fixture.consumerretry;

import org.springframework.dao.TransientDataAccessException;
import org.springframework.kafka.annotation.KafkaListener;

/** The pre-#2355 consumer shape: only the transient type is rethrown. */
public class FixtureTransientOnlyListener {

    private final Runnable handler;

    public FixtureTransientOnlyListener(Runnable handler) {
        this.handler = handler;
    }

    @KafkaListener(topics = "fixture.consumer-retry.v1", groupId = "fixture")
    public void onEvent(String message) {
        try {
            handler.run();
            apply(message);
        } catch (TransientDataAccessException e) {
            throw e;
        } catch (IllegalStateException e) {
            apply("failed");
        }
    }

    void apply(String message) {
        // stands in for the handler's database work
    }
}

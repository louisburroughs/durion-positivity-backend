package com.positivity.archunit.fixture.kafkaenabled;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

public class FixtureMethodValueGate {

    @ConditionalOnProperty(value = "pos.fixture.kafka.enabled")
    public Object gatedByValue() {
        return new Object();
    }
}

package com.positivity.archunit.fixture.kafkaenabled;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

public class FixtureMethodPrefixNameGate {

    @ConditionalOnProperty(prefix = "pos.fixture.kafka.", name = "enabled")
    public Object gatedByPrefixAndName() {
        return new Object();
    }
}

package com.positivity.archunit.fixture.kafkaenabled;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@ConditionalOnProperty(prefix = "pos.fixture.feature", name = "enabled")
public class FixtureUnrelatedGate {

    @ConditionalOnProperty("pos.fixture.kafka.bootstrap-servers")
    public Object unrelated() {
        return new Object();
    }
}

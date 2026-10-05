package com.positivity.archunit.fixture.kafkaenabled;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@ConditionalOnProperty("pos.fixture.kafka.enabled")
public class FixtureClassValueGate {}

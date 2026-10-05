package com.positivity.archunit.fixture.kafkaenabled;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@ConditionalOnProperty(prefix = "pos.fixture.kafka", name = "enabled")
public class FixtureClassPrefixNameGate {}

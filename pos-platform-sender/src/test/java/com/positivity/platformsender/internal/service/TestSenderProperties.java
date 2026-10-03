package com.positivity.platformsender.internal.service;

import com.positivity.platformsender.internal.config.SenderProperties;

/** {@link SenderProperties} with the application defaults, for tests that build beans by hand. */
public final class TestSenderProperties {

    private TestSenderProperties() {}

    public static SenderProperties defaults() {
        return new SenderProperties(
                "test-secret",
                "log",
                new SenderProperties.Aws("us-east-1"),
                new SenderProperties.Email("Durion <no-reply@durionpos.org>", "durion-email"),
                new SenderProperties.Sms(null, "durion-sms", "PROMOTIONAL", "1"),
                new SenderProperties.Outcomes(true, "https://sqs.us-east-1.amazonaws.com/123/outcomes", 20, 10));
    }
}

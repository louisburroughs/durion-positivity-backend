package com.positivity.platformsender.internal.config;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code pos.platform-sender.*}: the shared secret, the transport choice and the AWS settings. The
 * Kafka topics and consumer groups are read where they are used, as {@code @Value} placeholders,
 * the way every other module's listeners read theirs.
 *
 * @param apiSecret the shared secret callers send as {@code X-Pos-Sender-Secret}; blank refuses
 *     every send
 * @param transport {@code log} (accept and log, contact nobody) or {@code aws}
 */
@ConfigurationProperties(prefix = "pos.platform-sender")
public record SenderProperties(
        @Nullable String apiSecret,
        @DefaultValue("log") String transport,
        @DefaultValue Aws aws,
        @DefaultValue Email email,
        @DefaultValue Sms sms,
        @DefaultValue Outcomes outcomes) {

    /** @param region AWS region of the SES identity, the SMS origination identity and the queue */
    public record Aws(@DefaultValue("us-east-1") String region) {}

    /**
     * @param fromAddress verified SES identity the email is sent from
     * @param configurationSet SES configuration set whose event destination feeds the outcomes queue
     */
    public record Email(
            @Nullable String fromAddress, @Nullable String configurationSet) {}

    /**
     * @param originationIdentity phone number, pool or sender id to send from; blank lets AWS choose
     * @param configurationSet End User Messaging configuration set feeding the outcomes queue
     * @param messageType {@code TRANSACTIONAL} or {@code PROMOTIONAL}
     * @param defaultCountryCode country calling code for a stored number that carries none
     */
    public record Sms(
            @Nullable String originationIdentity,
            @Nullable String configurationSet,
            @DefaultValue("PROMOTIONAL") String messageType,
            @DefaultValue("1") String defaultCountryCode) {}

    /**
     * @param enabled whether the SQS poll runs
     * @param queueUrl the queue the SES and SMS event destinations publish into (through SNS)
     * @param waitTimeSeconds long-poll wait, at most 20
     * @param maxMessages messages per receive, at most 10
     */
    public record Outcomes(
            @DefaultValue("false") boolean enabled,
            @Nullable String queueUrl,
            @DefaultValue("20") int waitTimeSeconds,
            @DefaultValue("10") int maxMessages) {}
}

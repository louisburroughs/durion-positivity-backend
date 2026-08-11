package com.positivity.marketing.internal.service;

import java.util.Locale;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Stand-in transport that records what <em>would</em> have been sent (Story #1149).
 *
 * <p>The real adapter is {@link com.positivity.marketing.internal.client.PlatformSenderClient}
 * (Story #1150), active on {@code pos.marketing.send.transport=platform-sender}. This stub stays
 * the default so environments without a deployed shared sender still exercise the whole
 * orchestration path — audience materialization, idempotency, the send-time consent and
 * suppression re-check, status transitions, and the {@code marketing.campaign.sent} fact —
 * without contacting a single real customer.
 *
 * <p>Selection is by explicit property rather than {@code @ConditionalOnMissingBean}: that
 * annotation is only evaluated for {@code @Bean} methods in auto-configuration, so on a plain
 * component it silently registers nothing and the context fails to start. The mutually exclusive
 * conditions make it impossible to have both or neither implementation.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "pos.marketing.send", name = "transport", havingValue = "stub", matchIfMissing = true)
public class LoggingMessageChannel implements MessageChannelPort {

    @Override
    public @NonNull SendOutcome send(@NonNull OutboundMessage message) {
        // Logs the party id and a length only, never the rendered body: the body is the
        // customer's message, and log aggregation is not where it belongs.
        log.info(
                "[stub sender] would deliver {} to party {} for campaign {} ({} chars)",
                message.channel(),
                message.recipientPartyId(),
                message.campaignCode(),
                message.body().length());
        return SendOutcome.accepted(
                "stub-" + message.channel().name().toLowerCase(Locale.ROOT) + "-" + message.recipientPartyId());
    }
}

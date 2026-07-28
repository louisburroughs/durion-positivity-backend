package com.positivity.marketing.internal.service;

import com.positivity.marketing.internal.enums.CampaignChannel;
import java.util.Locale;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Stand-in transport that records what <em>would</em> have been sent (Story #1149).
 *
 * <p>The real adapter is Story #1150, which is blocked on the shared sender contract
 * (FI-2, durion#369). Until it exists, this implementation lets the whole orchestration path —
 * audience materialization, idempotency, the send-time consent and suppression re-check, status
 * transitions, and the {@code marketing.campaign.sent} fact — run and be tested end to end
 * without contacting a single real customer.
 *
 * <p>Selection is by explicit property rather than {@code @ConditionalOnMissingBean}: that
 * annotation is only evaluated for {@code @Bean} methods in auto-configuration, so on a plain
 * component it silently registers nothing and the context fails to start. {@code transport}
 * defaults to {@code stub}; the FI-2 adapter will activate on {@code platform-sender}, and the
 * mutually exclusive conditions make it impossible to have both or neither.
 */
@Slf4j
@Component
@ConditionalOnProperty(
        prefix = "pos.marketing.send",
        name = "transport",
        havingValue = "stub",
        matchIfMissing = true)
public class LoggingMessageChannel implements MessageChannelPort {

    @Override
    public @NonNull SendOutcome send(
            @NonNull CampaignChannel channel,
            @NonNull UUID recipientPartyId,
            @Nullable String subject,
            @NonNull String body) {
        // Logs the party id and a length only, never the rendered body: the body is the
        // customer's message, and log aggregation is not where it belongs.
        log.info(
                "[stub sender] would deliver {} to party {} ({} chars); real transport arrives with FI-2 (durion#369)",
                channel,
                recipientPartyId,
                body.length());
        return SendOutcome.accepted(
                "stub-" + channel.name().toLowerCase(Locale.ROOT) + "-" + recipientPartyId);
    }
}

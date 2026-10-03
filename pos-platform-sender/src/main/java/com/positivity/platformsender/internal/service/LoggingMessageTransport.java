package com.positivity.platformsender.internal.service;

import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * {@code transport: log}: accepts every message and logs that it would have been sent, contacting
 * nobody. The default, so a local or Compose stack never reaches a real inbox or phone. Logs the
 * address hash, never the address.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "pos.platform-sender", name = "transport", havingValue = "log", matchIfMissing = true)
public class LoggingMessageTransport implements MessageTransport {

    static final String PROVIDER_ID_PREFIX = "log-";

    @Override
    public @NonNull TransportResult send(@NonNull OutboundMessage message) {
        log.info(
                "[log transport] would send {} message {} for campaign {} to address {}",
                message.channel(),
                message.messageId(),
                message.campaignCode(),
                AddressNormalizer.hash(message.address()).substring(0, 12));
        return TransportResult.accepted(PROVIDER_ID_PREFIX + message.messageId());
    }
}

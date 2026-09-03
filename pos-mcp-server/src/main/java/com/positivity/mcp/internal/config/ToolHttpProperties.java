package com.positivity.mcp.internal.config;

import java.time.Duration;
import org.jspecify.annotations.NonNull;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Connect/read timeouts for {@code loadBalancedRestClientBuilder} (#1660): the facade RestClient
 * previously carried no timeout at all, so a stalled downstream held the whole chat turn until
 * something else gave up — and the recorded cause was whatever that something else was, not a
 * named timeout.
 *
 * @param connectTimeout TCP connect timeout, default 2s.
 * @param readTimeout socket read timeout, default 30s — comfortably above the 6.6-13.5s whole-turn
 *     times the gate measured (docs/gate-runs/wave-2), so a healthy multi-round chat turn is never
 *     cut short by this bound.
 */
@ConfigurationProperties(prefix = "pos.tools.http")
public record ToolHttpProperties(
        @NonNull Duration connectTimeout, @NonNull Duration readTimeout) {
    public ToolHttpProperties {
        if (connectTimeout == null) {
            connectTimeout = Duration.ofSeconds(2);
        }
        if (readTimeout == null) {
            readTimeout = Duration.ofSeconds(30);
        }
    }
}

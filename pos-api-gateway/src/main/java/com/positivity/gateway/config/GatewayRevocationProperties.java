package com.positivity.gateway.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for the gateway's token-revocation check (#1883).
 *
 * <p>Prefix {@code pos.gateway.security.revocation-check}.
 */
@ConfigurationProperties(prefix = "pos.gateway.security.revocation-check")
public class GatewayRevocationProperties {

    /**
     * When true, every authenticated request consults the shared revocation key space before it
     * is forwarded. Defaults to true: leaving revocation unenforced at the boundary is the gap
     * #1883 exists to close, so it has to be switched off deliberately, never by omission.
     */
    private boolean enabled = true;

    /**
     * How long one revocation lookup may take before it is abandoned and the request is forwarded
     * unchecked (fail-open). This bounds the latency a slow or half-open Redis can add to every
     * authenticated request; it is not a connection timeout.
     */
    private Duration timeout = Duration.ofMillis(150);

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public void setTimeout(Duration timeout) {
        this.timeout = timeout;
    }
}

package com.positivity.gateway.config;

import java.net.InetSocketAddress;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.cloud.gateway.support.ipresolver.XForwardedRemoteAddressResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Rate limits the anonymous organization directory (ADR-0062 §3), the one public route whose whole
 * purpose is to answer questions about which tenants exist. Everything else behind the gateway is
 * either authenticated or a credential exchange, so this is the only route that needs a per-caller
 * ceiling.
 *
 * <p>Redis-backed rather than in-process, so the ceiling holds across gateway instances; the
 * gateway already has a configured Redis for the {@code jwt:revoked:{jti}} checks.
 *
 * <p><strong>It fails open.</strong> {@link RedisRateLimiter} answers "allowed" when the Lua call
 * errors, which is deliberate here and matches the stance the gateway already takes on token
 * revocation: that check fails open and the Redis health indicator is disabled on purpose, so a
 * Redis outage cannot take the platform's ingress out of rotation. A limiter on the same Redis that
 * failed closed would turn a Redis blip into a total login outage — strictly worse than the
 * enumeration it exists to slow down.
 */
@Configuration
public class TenantSearchRateLimitConfig {

    /** Sustained requests per second per client; a typeahead sends far fewer than this. */
    static final int REPLENISH_RATE_PER_SECOND = 1;

    /** Headroom for the burst a user creates by typing a name quickly. */
    static final int BURST_CAPACITY = 10;

    /**
     * Trusts one proxy hop: with the gateway behind a load balancer the client is the last entry
     * {@code X-Forwarded-For} carries, and with nothing in front the header is stripped (the
     * {@code trusted-proxies} hardening) and the peer address is the client either way.
     *
     * <p>The naive alternative — always the socket's remote address — would see the load balancer's
     * single address for every request and throttle the entire platform into one bucket.
     */
    private static final XForwardedRemoteAddressResolver ADDRESS_RESOLVER =
            XForwardedRemoteAddressResolver.maxTrustedIndex(1);

    @Bean
    public RedisRateLimiter tenantSearchRateLimiter() {
        return new RedisRateLimiter(REPLENISH_RATE_PER_SECOND, BURST_CAPACITY);
    }

    @Bean
    public KeyResolver clientIpKeyResolver() {
        return exchange -> Mono.just(clientIp(exchange));
    }

    /** The caller's address, or {@code "unknown"} when the exchange carries none. */
    static String clientIp(ServerWebExchange exchange) {
        InetSocketAddress address = ADDRESS_RESOLVER.resolve(exchange);
        return address == null || address.getAddress() == null
                ? "unknown"
                : address.getAddress().getHostAddress();
    }
}

package com.positivity.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.ratelimit.RateLimiter;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.cloud.gateway.support.ConfigurationService;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Flux;

/**
 * The two things that can go wrong with a rate limiter on a public route, both of which would be
 * worse than the enumeration it slows down: refusing everyone when Redis blinks, and treating every
 * caller behind the load balancer as one client.
 */
class TenantSearchRateLimitConfigTest {

    private final TenantSearchRateLimitConfig config = new TenantSearchRateLimitConfig();

    @Test
    @DisplayName("Redis unavailable → allowed, so a Redis outage cannot block login")
    @SuppressWarnings("unchecked")
    void failsOpenWhenRedisIsDown() {
        ReactiveStringRedisTemplate redis = mock(ReactiveStringRedisTemplate.class);
        when(redis.execute(any(RedisScript.class), anyList(), anyList()))
                .thenReturn(Flux.error(new RedisConnectionFailureException("no connection")));
        RedisRateLimiter limiter =
                new RedisRateLimiter(redis, mock(RedisScript.class), mock(ConfigurationService.class));
        limiter.setApplicationContext(new org.springframework.context.support.StaticApplicationContext());
        RedisRateLimiter.Config routeConfig = new RedisRateLimiter.Config()
                .setReplenishRate(TenantSearchRateLimitConfig.REPLENISH_RATE_PER_SECOND)
                .setBurstCapacity(TenantSearchRateLimitConfig.BURST_CAPACITY);
        limiter.getConfig().put("tenant-search", routeConfig);

        RateLimiter.Response response =
                limiter.isAllowed("tenant-search", "203.0.113.7").block();

        assertThat(response).isNotNull();
        assertThat(response.isAllowed())
                .as("a Redis failure must not refuse the login form's organization search")
                .isTrue();
    }

    @Test
    @DisplayName("two clients behind one proxy get two buckets, not one")
    void distinguishesClientsBehindAProxy() {
        String first = TenantSearchRateLimitConfig.clientIp(exchangeForwardedFor("203.0.113.7"));
        String second = TenantSearchRateLimitConfig.clientIp(exchangeForwardedFor("198.51.100.4"));

        assertThat(first).isEqualTo("203.0.113.7");
        assertThat(second).isEqualTo("198.51.100.4");
        assertThat(first).isNotEqualTo(second);
    }

    @Test
    @DisplayName("with no forwarded header the peer address is the client")
    void fallsBackToThePeerAddress() {
        MockServerWebExchange exchange =
                MockServerWebExchange.from(MockServerHttpRequest.get("/security-service/v1/auth/tenants")
                        .remoteAddress(new java.net.InetSocketAddress("192.0.2.11", 41234)));

        assertThat(TenantSearchRateLimitConfig.clientIp(exchange)).isEqualTo("192.0.2.11");
    }

    @Test
    @DisplayName("an exchange with no address at all is keyed, not crashed")
    void unknownAddressIsKeyed() {
        MockServerWebExchange exchange =
                MockServerWebExchange.from(MockServerHttpRequest.get("/security-service/v1/auth/tenants"));

        assertThat(TenantSearchRateLimitConfig.clientIp(exchange)).isEqualTo("unknown");
    }

    @Test
    @DisplayName("the burst allows a typeahead without letting a scraper run free")
    void burstIsSizedForTyping() {
        assertThat(TenantSearchRateLimitConfig.BURST_CAPACITY).isEqualTo(10);
        assertThat(TenantSearchRateLimitConfig.REPLENISH_RATE_PER_SECOND).isEqualTo(1);
        assertThat(config.tenantSearchRateLimiter()).isNotNull();
    }

    private static MockServerWebExchange exchangeForwardedFor(String clientIp) {
        return MockServerWebExchange.from(MockServerHttpRequest.get("/security-service/v1/auth/tenants")
                .header("X-Forwarded-For", clientIp)
                .remoteAddress(new java.net.InetSocketAddress("10.0.0.5", 40000)));
    }
}

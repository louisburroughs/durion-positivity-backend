package com.positivity.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/**
 * Pins the {@code spring.cloud.gateway.server.webflux.trusted-proxies} regex.
 *
 * <p>Since the CVE-2025-41235 hardening, Spring Cloud Gateway strips inbound
 * {@code X-Forwarded-*}/{@code Forwarded} headers and appends none of its own unless the peer's
 * remote address matches this pattern. If the property disappears or stops matching the compose
 * network / host proxy addresses, downstream absolute-URL reconstruction silently regresses to
 * internal container URLs (bulk-loader TUS {@code Location}, #833/#834) — so the expected matching
 * behavior is asserted here against the real application.yml value.
 *
 * <p>The gateway evaluates the pattern as a full match against the peer's host address string,
 * which is mirrored here via {@link java.util.regex.Matcher#matches()}.
 */
class TrustedProxiesConfigTest {

    private static Pattern trustedProxies;

    @BeforeAll
    static void loadPattern() throws IOException {
        List<PropertySource<?>> sources =
                new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"));
        Object value = sources.stream()
                .map(s -> s.getProperty("spring.cloud.gateway.server.webflux.trusted-proxies"))
                .filter(v -> v != null)
                .findFirst()
                .orElse(null);
        assertThat(value)
                .as("trusted-proxies must be configured, otherwise the gateway forwards no X-Forwarded-* headers")
                .isNotNull();
        trustedProxies = Pattern.compile(value.toString());
    }

    @Test
    @DisplayName("trusts loopback and RFC1918 peers (compose networks, host nginx, frontend SSR proxy)")
    void trustsInternalPeers() {
        assertThat(List.of(
                        "127.0.0.1",
                        "0:0:0:0:0:0:0:1",
                        "::1",
                        "10.0.3.7",
                        "172.16.0.1",
                        "172.22.0.11",
                        "172.31.255.254",
                        "192.168.1.10"))
                .allSatisfy(
                        address -> assertThat(trustedProxies.matcher(address).matches())
                                .as("expected trusted peer: %s", address)
                                .isTrue());
    }

    @Test
    @DisplayName("does not trust public or out-of-range addresses")
    void rejectsExternalPeers() {
        assertThat(List.of(
                        "203.0.113.9",
                        "8.8.8.8",
                        // 172.x outside the 172.16.0.0/12 private block
                        "172.15.0.1",
                        "172.32.0.1",
                        // prefix/suffix junk must not full-match
                        "1172.22.0.11",
                        "192.168.1.10.evil.example"))
                .allSatisfy(
                        address -> assertThat(trustedProxies.matcher(address).matches())
                                .as("expected untrusted peer: %s", address)
                                .isFalse());
    }
}

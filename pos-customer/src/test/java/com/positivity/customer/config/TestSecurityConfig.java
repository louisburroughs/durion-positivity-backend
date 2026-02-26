package com.positivity.customer.config;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

import com.positivity.security.common.GatewayAuthoritiesFilter;

/**
 * Test-only security configuration for pos-customer contract integration tests.
 *
 * <p>
 * Ensures a test-profile {@link GatewayAuthoritiesFilter} bean is available and
 * preferred by type, so gateway header-based auth behavior is active in
 * contract
 * tests. This preserves the production security chain while enabling both
 * authorized (200) and unauthorized (403) scenarios per ADR-0011 and ADR-0014.
 */
@TestConfiguration
@Profile("test")
public class TestSecurityConfig {

    @Bean
    @Primary
    public GatewayAuthoritiesFilter testGatewayAuthoritiesFilter() {
        return new GatewayAuthoritiesFilter();
    }
}
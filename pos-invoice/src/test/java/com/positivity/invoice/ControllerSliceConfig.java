package com.positivity.invoice;

import com.positivity.security.common.GatewayAuthoritiesFilter;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;

/**
 * Shared beans for {@code @WebMvcTest} controller slices in this module (issue #1723).
 *
 * <p>Slices were unusable here until #1723: {@code PosInvoiceApplication} declared
 * {@code @EnableJpaRepositories} directly, which a web slice cannot exclude, so every attempt
 * failed with {@code NoSuchBeanDefinitionException: entityManagerFactory}. The error-handling
 * tests added by #1694 were written against the module's full context instead, leaving the
 * codebase with two shapes for the same kind of test. With the annotation gone, this module uses
 * the same shape as the other twelve:
 *
 * <pre>{@code
 * @WebMvcTest(SomeController.class)
 * @Import({GatewaySecurityConfig.class, WebCommonErrorAutoConfiguration.class,
 *          ControllerSliceConfig.class})
 * }</pre>
 *
 * <p>{@code GatewaySecurityConfig} is imported rather than this module's {@code SecurityConfig}
 * because the latter also declares {@code RestClient} beans a controller slice has no use for;
 * the authentication behaviour under test lives in the shared config.
 */
@TestConfiguration
public class ControllerSliceConfig {

    /** Fixed so advice timestamps are deterministic; ADR-0013 forbids ad-hoc clock reads in tests. */
    public static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2026-09-04T12:00:00Z"), ZoneOffset.UTC);

    @Bean
    public Clock clock() {
        return TEST_CLOCK;
    }

    /**
     * MockMvc's auto-configuration registers every {@code Filter} bean directly, which would run
     * {@code GatewayAuthoritiesFilter} (a {@code OncePerRequestFilter}) BEFORE the security chain
     * and mark the request already-filtered; the in-chain instance then skips and
     * {@code SecurityContextHolderFilter} wipes the authentication, failing every request with
     * 401. Disabling the direct registration puts the filter exactly where production runs it —
     * inside {@code gatewaySecurityFilterChain}.
     */
    @Bean
    public FilterRegistrationBean<GatewayAuthoritiesFilter> gatewayAuthoritiesFilterRegistration(
            GatewayAuthoritiesFilter gatewayAuthoritiesFilter) {
        var registration = new FilterRegistrationBean<>(gatewayAuthoritiesFilter);
        registration.setEnabled(false);
        return registration;
    }
}

package com.positivity.accounting;

import com.positivity.security.common.GatewayAuthoritiesFilter;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Shared scaffolding for {@code @WebMvcTest} controller slices in this module (issue #1723).
 *
 * <p>Slices were unusable here until #1723: {@code PosAccountingApplication} carried a redundant
 * explicit {@code @ComponentScan} alongside {@code @SpringBootApplication}, which overrode
 * {@code @WebMvcTest}'s {@code TypeExcludeFilter} and pulled the whole application into what
 * should have been a controller slice. The error-handling test added by #1694 was written
 * against {@link BaseIntegrationTest} instead, leaving the codebase with two shapes for the same
 * kind of test. With the annotation gone, this module uses the same shape as the other twelve.
 *
 * <p>Concrete tests annotate themselves:
 *
 * <pre>{@code
 * @WebMvcTest(SomeController.class)
 * @Import({GatewaySecurityConfig.class, WebCommonErrorAutoConfiguration.class,
 *          BaseControllerSliceTest.SliceConfig.class})
 * class SomeControllerErrorHandlingTest extends BaseControllerSliceTest { ... }
 * }</pre>
 *
 * <p>Unlike {@link BaseIntegrationTest}, MockMvc is injected by the slice rather than built from
 * the {@code WebApplicationContext} in a {@code @BeforeEach}: {@code @WebMvcTest} already applies
 * the Spring Security setup that base class adds by hand.
 */
public abstract class BaseControllerSliceTest {

    /** Fixed so advice timestamps are deterministic; ADR-0013 forbids ad-hoc clock reads in tests. */
    public static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2026-09-04T12:00:00Z"), ZoneOffset.UTC);

    /** Gateway header values — mirrors what pos-api-gateway injects after JWT validation. */
    protected static final String TEST_USER = "testuser";

    @Autowired
    protected MockMvc mockMvc;

    protected MockHttpServletRequestBuilder withAuth(MockHttpServletRequestBuilder builder, String authorities) {
        return builder.header("X-User", TEST_USER).header("X-Authorities", authorities);
    }

    @TestConfiguration
    public static class SliceConfig {

        @Bean
        public Clock clock() {
            return TEST_CLOCK;
        }

        /**
         * MockMvc's auto-configuration registers every {@code Filter} bean directly, which would
         * run {@code GatewayAuthoritiesFilter} (a {@code OncePerRequestFilter}) BEFORE the
         * security chain and mark the request already-filtered; the in-chain instance then skips
         * and {@code SecurityContextHolderFilter} wipes the authentication, failing every request
         * with 401. Disabling the direct registration puts the filter exactly where production
         * runs it — inside {@code gatewaySecurityFilterChain}.
         */
        @Bean
        public FilterRegistrationBean<GatewayAuthoritiesFilter> gatewayAuthoritiesFilterRegistration(
                GatewayAuthoritiesFilter gatewayAuthoritiesFilter) {
            var registration = new FilterRegistrationBean<>(gatewayAuthoritiesFilter);
            registration.setEnabled(false);
            return registration;
        }
    }
}

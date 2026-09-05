package com.positivity.order;

import com.positivity.security.common.GatewayAuthoritiesFilter;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Shared scaffolding for {@code @WebMvcTest} controller slices in this module (issue #1723).
 *
 * <p>Slices were unusable here until #1723: {@code PosOrderApplication} declared
 * {@code @EnableJpaRepositories} directly, which a web slice cannot exclude, so every attempt
 * failed with {@code NoSuchBeanDefinitionException: entityManagerFactory}. The error-handling
 * tests added by #1694 were written against the module's full-context base instead, leaving the
 * codebase with two shapes for the same kind of test. With the annotation gone, this base gives
 * this module the same shape the other twelve use.
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
 * <p>{@code GatewaySecurityConfig} is imported rather than this module's {@code SecurityConfig}
 * because the latter also declares {@code @LoadBalanced RestClient} beans that a controller slice
 * has no use for; the authentication behaviour under test lives in the shared config.
 */
public abstract class BaseControllerSliceTest {

    /** Fixed so advice timestamps are deterministic; ADR-0013 forbids ad-hoc UUID/clock reads in tests. */
    public static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2026-09-04T12:00:00Z"), ZoneOffset.UTC);

    private static final UUID DEFAULT_TEST_USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Autowired
    protected MockMvc mockMvc;

    /** Authenticates the request the way the API gateway does: X-User / X-Authorities headers. */
    protected MockHttpServletRequestBuilder withGatewayAuth(MockHttpServletRequestBuilder requestBuilder) {
        return withGatewayAuth(requestBuilder, "*");
    }

    protected MockHttpServletRequestBuilder withGatewayAuth(
            MockHttpServletRequestBuilder requestBuilder, String authorities) {
        String username = DEFAULT_TEST_USER_ID.toString();
        return requestBuilder
                .header("X-User", username)
                .header("X-Authorities", authorities)
                .header("Authorization", "Bearer " + buildUnsignedJwtWithUserId(username));
    }

    private String buildUnsignedJwtWithUserId(String username) {
        String headerJson = "{\"alg\":\"none\",\"typ\":\"JWT\"}";
        String payloadJson = "{\"userId\":\"" + username + "\"}";
        return base64UrlEncode(headerJson) + "." + base64UrlEncode(payloadJson) + ".signature";
    }

    private String base64UrlEncode(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
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

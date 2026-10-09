package com.positivity.tax.internal.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import tools.jackson.databind.ObjectMapper;

/**
 * CAP:550 S32c AC 1 (secret) and AC 3 (actor binding): the tax-registration writes accept only pos-accounting's
 * per-caller secret, and bind the forwarded {@code X-User-Id} as the principal.
 */
@DisplayName("FrontDoorSecretFilter — only pos-accounting reaches the registration writes")
class FrontDoorSecretFilterTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-08T12:00:00Z"), ZoneOffset.UTC);
    private static final String PATH = "/v1/tax/registrations";
    private static final String SECRET = "test-only-front-door-secret";
    private static final String ACTOR = "01990000-0000-7000-8000-0000000000e1";
    private static final String TENANT = "01990000-0000-7000-8000-0000000000f1";

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private static MockHttpServletRequest request(String path, String secret, String actor, String tenant) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        request.setRequestURI(path);
        if (secret != null) {
            request.addHeader(FrontDoorSecretFilter.SECRET_HEADER, secret);
        }
        if (actor != null) {
            request.addHeader(FrontDoorSecretFilter.ACTOR_HEADER, actor);
        }
        if (tenant != null) {
            request.addHeader("X-Tenant-Id", tenant);
        }
        return request;
    }

    private static FrontDoorSecretFilter filter(String configured) {
        return new FrontDoorSecretFilter(configured, CLOCK, new ObjectMapper());
    }

    @Test
    @DisplayName("[M] a blank configured secret refuses every request, even one that sends the same blank")
    void blankConfiguredSecretRefusesAll() throws Exception {
        for (String blank : new String[] {"", " ", null}) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();

            filter(blank).doFilter(request(PATH, blank, ACTOR, TENANT), response, chain);

            assertThat(response.getStatus()).isEqualTo(401);
            assertThat(response.getContentAsString()).contains(FrontDoorSecretFilter.CODE_SECRET_MISSING);
            assertThat(chain.getRequest()).as("the chain never ran").isNull();
        }
    }

    @Test
    @DisplayName("[M] a missing or wrong secret is 401 and the chain never runs")
    void missingOrWrongSecretRefused() throws Exception {
        for (String provided : new String[] {null, "", "wrong", SECRET + "x"}) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();

            filter(SECRET).doFilter(request(PATH + "/" + ACTOR, provided, ACTOR, TENANT), response, chain);

            assertThat(response.getStatus()).as("secret %s", provided).isEqualTo(401);
            assertThat(response.getContentAsString()).contains(FrontDoorSecretFilter.CODE_SECRET_INVALID);
            assertThat(chain.getRequest()).isNull();
        }
    }

    @Test
    @DisplayName("the right secret without the forwarded actor or tenant is 401")
    void missingForwardedContextRefused() throws Exception {
        String[][] cases = {{null, TENANT}, {"not-a-uuid", TENANT}, {ACTOR, null}};
        for (String[] headers : cases) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();

            filter(SECRET).doFilter(request(PATH, SECRET, headers[0], headers[1]), response, chain);

            assertThat(response.getStatus()).isEqualTo(401);
            assertThat(response.getContentAsString()).contains(FrontDoorSecretFilter.CODE_CONTEXT_MISSING);
            assertThat(chain.getRequest()).isNull();
        }
    }

    @Test
    @DisplayName("AC 3: the matching secret binds the forwarded X-User-Id as the principal")
    void matchingSecretBindsTheForwardedActor() throws Exception {
        AtomicReference<Authentication> seen = new AtomicReference<>();
        MockFilterChain chain = new MockFilterChain(
                new jakarta.servlet.http.HttpServlet() {},
                (req, res, next) -> seen.set(SecurityContextHolder.getContext().getAuthentication()));

        filter(SECRET).doFilter(request(PATH, SECRET, ACTOR, TENANT), new MockHttpServletResponse(), chain);

        assertThat(seen.get()).isNotNull();
        assertThat(seen.get().isAuthenticated()).isTrue();
        assertThat(seen.get().getName()).isEqualTo(ACTOR);
        assertThat(seen.get().getAuthorities())
                .as("no permission is granted, only the front door")
                .isEmpty();
    }

    @Test
    @DisplayName("paths outside the registration writes are not this filter's business")
    void otherPathsPassThrough() throws Exception {
        MockFilterChain chain = new MockFilterChain();

        filter(SECRET).doFilter(request("/v1/tax/calculate", null, null, null), new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }
}

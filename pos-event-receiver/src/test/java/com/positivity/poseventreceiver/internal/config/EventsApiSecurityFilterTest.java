package com.positivity.poseventreceiver.internal.config;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.ServletException;
import java.io.IOException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Unit tests for {@link EventsApiSecurityFilter}.
 *
 * <p>
 * This filter is the only authentication in front of the event-ingestion API.
 * {@code pos-event-receiver} deliberately does not validate JWTs — doing so
 * would make it depend on {@code pos-security-service}, which depends on it at
 * startup — so a shared secret guards every mutating request instead.
 *
 * <p>
 * The tests below fix the exact shape of that gate, because each of its four
 * pass-through branches is a deliberate hole and widening one by accident would
 * silently expose the write path: actuator endpoints, safe HTTP methods, paths
 * outside {@code /v1/events} and {@code /v1/eventTypes}, and the
 * no-secret-configured development mode.
 */
@DisplayName("EventsApiSecurityFilter — shared-secret gate on the ingestion API")
class EventsApiSecurityFilterTest {

    private static final String SECRET_HEADER = "X-Events-Api-Secret";
    private static final String SECRET = "correct-horse-battery-staple";

    private final MockFilterChain chain = new MockFilterChain();
    private final MockHttpServletResponse response = new MockHttpServletResponse();

    private static MockHttpServletRequest request(String method, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setRequestURI(path);
        return request;
    }

    private void filter(String configuredSecret, MockHttpServletRequest request) throws ServletException, IOException {
        new EventsApiSecurityFilter(configuredSecret).doFilter(request, response, chain);
    }

    private void assertPassedThrough() {
        assertThat(chain.getRequest())
                .as("request should have been passed down the chain")
                .isNotNull();
        assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
    }

    private void assertRejected() {
        assertThat(chain.getRequest()).as("request must not reach the chain").isNull();
        assertThat(response.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
    }

    @Nested
    @DisplayName("with a secret configured")
    class SecurityEnabled {

        @Test
        @DisplayName("allows a mutating request carrying the correct secret")
        void allowsRequestWithCorrectSecret() throws Exception {
            MockHttpServletRequest request = request("POST", "/v1/events");
            request.addHeader(SECRET_HEADER, SECRET);

            filter(SECRET, request);

            assertPassedThrough();
        }

        @Test
        @DisplayName("rejects a mutating request with no secret header")
        void rejectsRequestWithoutSecretHeader() throws Exception {
            filter(SECRET, request("POST", "/v1/events"));

            assertRejected();
        }

        @Test
        @DisplayName("rejects a mutating request carrying the wrong secret")
        void rejectsRequestWithWrongSecret() throws Exception {
            MockHttpServletRequest request = request("POST", "/v1/events");
            request.addHeader(SECRET_HEADER, "not-the-secret");

            filter(SECRET, request);

            assertRejected();
        }

        @Test
        @DisplayName("rejection carries a JSON body and does not leak the expected secret")
        void rejectionBodyIsJsonAndLeaksNothing() throws Exception {
            filter(SECRET, request("POST", "/v1/eventTypes/ORDER_CREATE"));

            assertThat(response.getContentType()).isEqualTo("application/json");
            assertThat(response.getContentAsString()).contains("Unauthorized").doesNotContain(SECRET);
        }

        @ParameterizedTest(name = "{0} /v1/eventTypes is rejected without a secret")
        @ValueSource(strings = {"POST", "PUT", "DELETE", "PATCH"})
        @DisplayName("guards every mutating method, not just POST")
        void rejectsEveryMutatingMethod(String method) throws Exception {
            filter(SECRET, request(method, "/v1/eventTypes/ORDER_CREATE"));

            assertRejected();
        }

        @ParameterizedTest(name = "{0} is allowed without a secret")
        @ValueSource(strings = {"GET", "HEAD", "OPTIONS"})
        @DisplayName("lets safe methods through unauthenticated — reads are public by design")
        void allowsSafeMethodsWithoutSecret(String method) throws Exception {
            filter(SECRET, request(method, "/v1/events"));

            assertPassedThrough();
        }

        @ParameterizedTest(name = "{0} is allowed without a secret")
        @ValueSource(strings = {"/actuator/health", "/actuator/prometheus"})
        @DisplayName("lets actuator endpoints through unauthenticated")
        void allowsActuatorWithoutSecret(String path) throws Exception {
            filter(SECRET, request("POST", path));

            assertPassedThrough();
        }

        @ParameterizedTest(name = "{0} is outside the guarded prefixes")
        @ValueSource(strings = {"/v1/other", "/internal/thing", "/", "/v2/events"})
        @DisplayName("only guards the /v1/events and /v1/eventTypes prefixes")
        void allowsPathsOutsideGuardedPrefixes(String path) throws Exception {
            filter(SECRET, request("POST", path));

            assertPassedThrough();
        }

        @Test
        @DisplayName("prefix matching is literal — /v1/eventsomething is also guarded")
        void guardsAnyPathStartingWithTheEventsPrefix() throws Exception {
            filter(SECRET, request("POST", "/v1/events/emit"));

            assertRejected();
        }
    }

    @Nested
    @DisplayName("with no secret configured (development mode)")
    class SecurityDisabled {

        @ParameterizedTest(name = "configured secret = [{0}] disables the gate")
        @NullAndEmptySource
        @ValueSource(strings = {"   "})
        @DisplayName("allows mutating requests through when no secret is configured")
        void allowsEverythingWhenNoSecretConfigured(String configuredSecret) throws Exception {
            filter(configuredSecret, request("POST", "/v1/events"));

            assertPassedThrough();
        }

        @Test
        @DisplayName("ignores a supplied secret header when none is configured")
        void ignoresSuppliedSecretWhenNoneConfigured() throws Exception {
            MockHttpServletRequest request = request("POST", "/v1/events");
            request.addHeader(SECRET_HEADER, "anything-at-all");

            filter("", request);

            assertPassedThrough();
        }
    }
}

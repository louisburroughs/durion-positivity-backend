package com.positivity.securityservice.internal.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.events.EventsApiConstants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClient;

/**
 * Unit tests for {@link EventTypeInitializer}.
 *
 * <p>
 * Verifies that all security event types are registered at startup via REST
 * PUT,
 * that the shared API secret header is applied when configured, and that
 * individual
 * registration failures do not abort the startup sequence.
 *
 * <p>
 * Uses {@link Answers#RETURNS_SELF} for the fluent RestClient chain mock so
 * that
 * {@code uri()}, {@code contentType()}, {@code body()}, and {@code header()}
 * all
 * return the same mock without requiring per-method stubs.
 *
 * <p>
 * Issue: AUTH-008
 *
 * @since AUTH-008
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SecurityEventTypeInitializerTest — AUTH-008 event-type registration")
class SecurityEventTypeInitializerTest {

    @Mock
    private RestClient.Builder restClientBuilder;

    @Mock
    private RestClient restClient;

    /**
     * Fluent-chain mock for the RestClient request pipeline.
     * {@code RETURNS_SELF} makes all compatible fluent methods (uri, contentType,
     * body, header) return this mock automatically, eliminating brittle per-method
     * stubs for the chain and keeping the setup minimal.
     */
    @Mock(answer = Answers.RETURNS_SELF)
    private RestClient.RequestBodyUriSpec requestBodyUriSpec;

    @Mock
    private RestClient.ResponseSpec responseSpec;

    /** No-secret initializer created in setUp() for basic tests. */
    private EventTypeInitializer sut;

    @BeforeEach
    void setUp() {
        when(restClientBuilder.baseUrl(anyString())).thenReturn(restClientBuilder);
        when(restClientBuilder.build()).thenReturn(restClient);

        // route put() to the RETURNS_SELF chain mock
        lenient().when(restClient.put()).thenReturn(requestBodyUriSpec);
        // RETURNS_SELF handles uri(), contentType(), body(), header() automatically
        lenient().when(requestBodyUriSpec.retrieve()).thenReturn(responseSpec);
        lenient().when(responseSpec.toBodilessEntity()).thenReturn(null);

        sut = new EventTypeInitializer(restClientBuilder, "http://localhost:8085", "");
    }

    // =========================================================
    // T_SETI1 — run() submits a PUT for every registered event type
    // =========================================================

    @Nested
    @DisplayName("T_SETI1: run() registers all 31 event types")
    class RegistersAllEventTypes {

        @Test
        @DisplayName("T_SETI1 — SecurityEventTypes.all() defines exactly 31 entries")
        void securityEventTypes_definesExactly31Types() {
            assertThat(EventTypes.all())
                    .as("SecurityEventTypes.all() must define exactly 31 event type registrations")
                    .hasSize(31);
        }

        @Test
        @DisplayName("T_SETI1b — run(null) invokes PUT once for each security event type")
        void run_registersAllEventTypes() {
            sut.run(null);

            int expectedCount = EventTypes.all().size();
            verify(restClient, times(expectedCount)).put();
        }
    }

    // =========================================================
    // T_SETI3 — run() adds secret header when apiSecret is non-empty
    // =========================================================

    @Nested
    @DisplayName("T_SETI3: run() sets secret header when apiSecret is non-empty")
    class SecretHeaderBehavior {

        @Test
        @DisplayName("T_SETI3 — run() calls header(SECRET_HEADER, secret) for every event type when secret is set")
        void run_withSecret_setsSecretHeader() {
            String secret = "test-api-secret-value";
            EventTypeInitializer sutWithSecret =
                    new EventTypeInitializer(restClientBuilder, "http://localhost:8085", secret);

            sutWithSecret.run(null);

            int expectedCount = EventTypes.all().size();
            // Issue AUTH-008: verify that the shared secret header is applied for each
            // registration
            verify(requestBodyUriSpec, times(expectedCount)).header(EventsApiConstants.SECRET_HEADER, secret);
        }

        @Test
        @DisplayName("T_SETI3b — run() does NOT set secret header when apiSecret is blank")
        void run_withoutSecret_doesNotSetSecretHeader() {
            // sut was constructed with "" apiSecret in setUp()
            sut.run(null);

            // Issue AUTH-008: secret header must not be sent when no apiSecret is
            // configured
            verify(requestBodyUriSpec, never()).header(eq(EventsApiConstants.SECRET_HEADER), anyString());
        }
    }

    // =========================================================
    // T_SETI4 — run() tolerates REST failures (non-fatal startup)
    // =========================================================

    @Nested
    @DisplayName("T_SETI4: run() tolerates REST failures — non-fatal startup semantics")
    class FaultTolerance {

        @Test
        @DisplayName("T_SETI4 — run() does not propagate RuntimeException from individual registration failure")
        void run_whenRegistrationFails_doesNotPropagateException() {
            // Issue AUTH-008: startup must succeed even if event-service is unavailable
            when(responseSpec.toBodilessEntity()).thenThrow(new RuntimeException("Connection refused"));

            assertThatNoException().isThrownBy(() -> sut.run(null));
        }

        @Test
        @DisplayName("T_SETI4b — run() continues registering remaining types after one failure")
        void run_whenOneRegistrationFails_continuesWithRemaining() {
            // Fail on the very first call, succeed on subsequent calls
            when(responseSpec.toBodilessEntity())
                    .thenThrow(new RuntimeException("transient error"))
                    .thenReturn(null);

            assertThatNoException().isThrownBy(() -> sut.run(null));

            // All 31 PUT requests were still attempted despite the first failure
            int expectedCount = EventTypes.all().size();
            verify(restClient, times(expectedCount)).put();
        }
    }
}

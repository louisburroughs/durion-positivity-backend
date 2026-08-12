package com.positivity.workorder.internal.config;

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
 * The initializer runs as an {@code ApplicationRunner} at startup and PUTs every
 * entry of {@link EventTypes} to {@code pos-event-receiver}. Two properties of
 * that contract matter operationally:
 *
 * <ul>
 * <li>the shared-secret header is sent when {@code pos.events.api-secret} is
 * configured and omitted when it is blank, and</li>
 * <li>a failing registration is swallowed — the event receiver being down must
 * never block this service from starting.</li>
 * </ul>
 *
 * <p>
 * The fluent {@code RestClient} chain is mocked with {@link Answers#RETURNS_SELF}
 * so {@code uri()}, {@code contentType()}, {@code body()}, and {@code header()}
 * all return the same mock without per-method stubs.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EventTypeInitializer — startup event-type registration")
class EventTypeInitializerTest {

    private static final String BASE_URL = "http://pos-event-receiver:8080";

    @Mock
    private RestClient.Builder restClientBuilder;

    @Mock
    private RestClient restClient;

    @Mock(answer = Answers.RETURNS_SELF)
    private RestClient.RequestBodyUriSpec requestBodyUriSpec;

    @Mock
    private RestClient.ResponseSpec responseSpec;

    /** Initializer built with a blank api-secret. */
    private EventTypeInitializer sut;

    @BeforeEach
    void setUp() {
        when(restClientBuilder.baseUrl(anyString())).thenReturn(restClientBuilder);
        when(restClientBuilder.build()).thenReturn(restClient);

        lenient().when(restClient.put()).thenReturn(requestBodyUriSpec);
        lenient().when(requestBodyUriSpec.retrieve()).thenReturn(responseSpec);
        lenient().when(responseSpec.toBodilessEntity()).thenReturn(null);

        sut = new EventTypeInitializer(restClientBuilder, BASE_URL, "", true);
    }

    @Test
    @DisplayName("run() PUTs one registration per declared event type")
    void run_registersEveryEventType() {
        sut.run(null);

        verify(restClient, times(EventTypes.ALL_EVENT_TYPES.size())).put();
    }

    @Test
    @DisplayName("run() sends the shared-secret header when an api-secret is configured")
    void run_withSecret_sendsSecretHeader() {
        String secret = "test-api-secret-value";
        EventTypeInitializer sutWithSecret = new EventTypeInitializer(restClientBuilder, BASE_URL, secret, true);

        sutWithSecret.run(null);

        verify(requestBodyUriSpec, times(EventTypes.ALL_EVENT_TYPES.size()))
                .header(EventsApiConstants.SECRET_HEADER, secret);
    }

    @Test
    @DisplayName("run() omits the shared-secret header when the api-secret is blank")
    void run_withoutSecret_omitsSecretHeader() {
        sut.run(null);

        verify(requestBodyUriSpec, never()).header(eq(EventsApiConstants.SECRET_HEADER), anyString());
    }

    @Test
    @DisplayName("run() does not propagate a registration failure — startup must not block on the event receiver")
    void run_whenRegistrationFails_doesNotPropagate() {
        when(responseSpec.toBodilessEntity()).thenThrow(new RuntimeException("Connection refused"));

        assertThatNoException().isThrownBy(() -> sut.run(null));
    }

    @Test
    @DisplayName("run() keeps registering the remaining types after one failure")
    void run_whenOneRegistrationFails_continuesWithRemaining() {
        when(responseSpec.toBodilessEntity())
                .thenThrow(new RuntimeException("transient error"))
                .thenReturn(null);

        assertThatNoException().isThrownBy(() -> sut.run(null));

        assertThat(EventTypes.ALL_EVENT_TYPES).isNotEmpty();
        verify(restClient, times(EventTypes.ALL_EVENT_TYPES.size())).put();
    }
}

package com.positivity.image.internal.exception;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.image.internal.dto.StoreImageRequest;
import com.positivity.shared.error.ApiError;
import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.bind.annotation.ExceptionHandler;

/**
 * A malformed store request is the caller's problem, and says so (CAP-324 #1257).
 *
 * <p>A 500 for a bad body is worse than untidy: it tells the caller to retry a request that will
 * fail identically, and it files a client error wherever server errors are alerted on.
 */
@DisplayName("ImageExceptionHandler — a bad request is a 400, not a 500 (#1257)")
class ImageExceptionHandlerTest {

    private static final Instant NOW = Instant.parse("2026-08-16T10:00:00Z");

    private final ImageExceptionHandler handler = new ImageExceptionHandler(Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    @DisplayName("content that is not base64 answers 400 in the standard envelope")
    void invalidBase64IsABadRequest() {
        StoreImageRequest request =
                new StoreImageRequest("tread.jpg", "image/jpeg", "not base64 at all!!", null, List.of());

        ResponseEntity<ApiError> response =
                handler.handleBadRequest(catchImageValidation(request::decodedContent), servletRequest());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("IMAGE_REQUEST_INVALID");
        assertThat(response.getBody().message()).contains("base64");
        assertThat(response.getBody().status()).isEqualTo(400);
        assertThat(response.getBody().timestamp()).isEqualTo(NOW.toString());
    }

    @Test
    @DisplayName("an empty body answers 400, since refusing it is the point")
    void emptyContentIsABadRequest() {
        ResponseEntity<ApiError> response = handler.handleBadRequest(
                new ImageValidationException("refusing to store an empty image; it would never be retried"),
                servletRequest());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
    }

    @Test
    @DisplayName("a supplied correlation id is echoed rather than replaced")
    void correlationIdIsPreserved() {
        MockHttpServletRequest request = servletRequest();
        request.addHeader("X-Correlation-Id", "corr-from-caller");

        ResponseEntity<ApiError> response = handler.handleBadRequest(new ImageValidationException("bad"), request);

        // Replacing it would break the one thread a caller has for following its own request
        // through the platform.
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().correlationId()).isEqualTo("corr-from-caller");
        assertThat(response.getHeaders().getFirst("X-Correlation-Id")).isEqualTo("corr-from-caller");
    }

    @Test
    @DisplayName("a record pointing at content nobody holds is a 500, not a client error")
    void brokenStateIsAServerError() {
        // A broken row must not send whoever hit it looking at their own request.
        ResponseEntity<ApiError> response = handler.handleBrokenState(
                new IllegalStateException("Image 7 references content abc which is not stored"), servletRequest());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("IMAGE_STATE_INVALID");
    }

    private static MockHttpServletRequest servletRequest() {
        return new MockHttpServletRequest("POST", "/v1/images");
    }

    private static ImageValidationException catchImageValidation(Runnable action) {
        try {
            action.run();
            throw new AssertionError("expected an ImageValidationException");
        } catch (ImageValidationException e) {
            return e;
        }
    }

    // ---------------------------------------------------------------
    // X-Correlation-Id header (ADR-0017 §4, #1729) — proves the single `build`
    // helper puts the correlation id in both the body and the header for EVERY
    // @ExceptionHandler method, and guards against a future handler forgetting it.
    // ---------------------------------------------------------------

    @Nested
    @DisplayName("X-Correlation-Id header (ADR-0017 §4, #1729)")
    class XCorrelationIdHeader {

        @FunctionalInterface
        interface HandlerInvocation {
            ResponseEntity<ApiError> invoke(MockHttpServletRequest request);
        }

        /** One entry per {@code @ExceptionHandler} method on {@link ImageExceptionHandler}. */
        private static Stream<Named<HandlerInvocation>> handlerInvocations() {
            ImageExceptionHandler h = new ImageExceptionHandler(Clock.fixed(NOW, ZoneOffset.UTC));

            return Stream.of(
                    Named.of("handleBadRequest", (HandlerInvocation)
                            request -> h.handleBadRequest(new ImageValidationException("bad content"), request)),
                    Named.of("handleBrokenState", (HandlerInvocation) request -> h.handleBrokenState(
                            new IllegalStateException("Image 7 references content abc which is not stored"), request)));
        }

        @ParameterizedTest
        @MethodSource("handlerInvocations")
        @DisplayName("echoes the inbound X-Correlation-Id in both header and body")
        void echoesInboundCorrelationId(HandlerInvocation invocation) {
            MockHttpServletRequest request = servletRequest();
            request.addHeader("X-Correlation-Id", "corr-from-caller");

            ResponseEntity<ApiError> response = invocation.invoke(request);

            assertThat(response.getHeaders().getFirst("X-Correlation-Id")).isEqualTo("corr-from-caller");
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getHeaders().getFirst("X-Correlation-Id"))
                    .isEqualTo(response.getBody().correlationId());
        }

        @ParameterizedTest
        @MethodSource("handlerInvocations")
        @DisplayName("generates a non-blank X-Correlation-Id, consistent between header and body, when absent")
        void generatesCorrelationIdWhenAbsent(HandlerInvocation invocation) {
            ResponseEntity<ApiError> response = invocation.invoke(servletRequest());

            String header = response.getHeaders().getFirst("X-Correlation-Id");
            assertThat(header).isNotBlank();
            assertThat(response.getBody()).isNotNull();
            assertThat(header).isEqualTo(response.getBody().correlationId());
        }

        @Test
        @DisplayName("every @ExceptionHandler method on ImageExceptionHandler has a matching MethodSource entry")
        void everyHandlerMethodIsCovered() {
            long handlerMethodCount = Arrays.stream(ImageExceptionHandler.class.getDeclaredMethods())
                    .filter((Method method) -> method.isAnnotationPresent(ExceptionHandler.class))
                    .count();
            long methodSourceEntryCount = handlerInvocations().count();

            assertThat(methodSourceEntryCount)
                    .as("A new @ExceptionHandler method was added to ImageExceptionHandler without a matching "
                            + "entry in XCorrelationIdHeader#handlerInvocations() in ImageExceptionHandlerTest — "
                            + "add one so the X-Correlation-Id header contract stays proven for every handler")
                    .isEqualTo(handlerMethodCount);
        }
    }
}

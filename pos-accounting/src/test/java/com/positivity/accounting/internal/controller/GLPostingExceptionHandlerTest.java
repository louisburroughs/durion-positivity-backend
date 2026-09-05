package com.positivity.accounting.internal.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.exception.GLPostingException;
import com.positivity.shared.error.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;

/**
 * Unit tests for {@link GLPostingExceptionHandler}.
 *
 * <p>Before issue #1729, {@code handleGLPostingException} hardcoded a {@code null} correlation id
 * in the {@link ApiError} body and never set the {@code X-Correlation-Id} response header at all.
 * The handler now takes an {@link HttpServletRequest} and routes through a private {@code build}
 * helper (matching the shape of the sibling {@link APPaymentExceptionHandler} in this package)
 * that resolves the correlation id by echoing the inbound header or generating a UUIDv7, and sets
 * it in both the body and the header.
 */
class GLPostingExceptionHandlerTest {

    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC);
    private static final String CORRELATION_ID_HEADER = "X-Correlation-Id";
    private static final String CORRELATION_ID = "test-correlation-id-0001";

    private static HttpServletRequest requestWithHeader(String value) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader(CORRELATION_ID_HEADER)).thenReturn(value);
        return request;
    }

    private static HttpServletRequest requestWithoutHeader() {
        return requestWithHeader(null);
    }

    @Test
    @DisplayName("maps GLPostingException to 409 GL_POSTING_FAILED")
    void mapsToConflictWithGLPostingFailedCode() {
        GLPostingExceptionHandler handler = new GLPostingExceptionHandler(TEST_CLOCK);
        GLPostingException ex = new GLPostingException("unbalanced GL posting");

        ResponseEntity<ApiError> response = handler.handleGLPostingException(ex, requestWithHeader(CORRELATION_ID));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        ApiError body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.code()).isEqualTo("GL_POSTING_FAILED");
        assertThat(body.message()).isEqualTo("unbalanced GL posting");
    }

    @Nested
    @DisplayName("X-Correlation-Id header (ADR-0017 §4, #1729)")
    class XCorrelationIdHeader {

        @FunctionalInterface
        interface HandlerInvocation {
            ResponseEntity<ApiError> invoke(HttpServletRequest request);
        }

        /**
         * One entry per {@code @ExceptionHandler} method on {@link GLPostingExceptionHandler}.
         * Uses a standalone handler instance so this factory method can stay static, as required
         * by {@code @MethodSource} outside a {@code PER_CLASS} test instance lifecycle.
         */
        private static Stream<Named<HandlerInvocation>> handlerInvocations() {
            GLPostingExceptionHandler handler = new GLPostingExceptionHandler(TEST_CLOCK);

            return Stream.of(Named.of("handleGLPostingException", (HandlerInvocation) request ->
                    handler.handleGLPostingException(new GLPostingException("unbalanced GL posting"), request)));
        }

        @ParameterizedTest
        @MethodSource("handlerInvocations")
        @DisplayName("echoes the inbound X-Correlation-Id in both header and body")
        void echoesInboundCorrelationId(HandlerInvocation invocation) {
            ResponseEntity<ApiError> response = invocation.invoke(requestWithHeader(CORRELATION_ID));

            assertThat(response.getHeaders().getFirst(CORRELATION_ID_HEADER)).isEqualTo(CORRELATION_ID);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getHeaders().getFirst(CORRELATION_ID_HEADER))
                    .isEqualTo(response.getBody().correlationId());
        }

        @ParameterizedTest
        @MethodSource("handlerInvocations")
        @DisplayName("generates a non-blank X-Correlation-Id, consistent between header and body, when absent")
        void generatesCorrelationIdWhenAbsent(HandlerInvocation invocation) {
            ResponseEntity<ApiError> response = invocation.invoke(requestWithoutHeader());

            String header = response.getHeaders().getFirst(CORRELATION_ID_HEADER);
            assertThat(header).isNotBlank();
            assertThat(response.getBody()).isNotNull();
            assertThat(header).isEqualTo(response.getBody().correlationId());
        }

        @ParameterizedTest
        @MethodSource("handlerInvocations")
        @DisplayName("generates a fresh X-Correlation-Id when the inbound header is blank")
        void generatesCorrelationIdWhenInboundIsBlank(HandlerInvocation invocation) {
            ResponseEntity<ApiError> response = invocation.invoke(requestWithHeader("   "));

            String header = response.getHeaders().getFirst(CORRELATION_ID_HEADER);
            assertThat(header).isNotBlank();
            assertThat(header).isNotEqualTo("   ");
            assertThat(response.getBody()).isNotNull();
            assertThat(header).isEqualTo(response.getBody().correlationId());
        }

        @Test
        @DisplayName("every @ExceptionHandler method on GLPostingExceptionHandler has a matching MethodSource entry")
        void everyHandlerMethodIsCovered() {
            long handlerMethodCount = Arrays.stream(GLPostingExceptionHandler.class.getDeclaredMethods())
                    .filter(method -> method.isAnnotationPresent(ExceptionHandler.class))
                    .count();
            long methodSourceEntryCount = handlerInvocations().count();

            assertThat(methodSourceEntryCount)
                    .as("A new @ExceptionHandler method was added to GLPostingExceptionHandler without a matching "
                            + "entry in XCorrelationIdHeader#handlerInvocations() in GLPostingExceptionHandlerTest "
                            + "— add one so the X-Correlation-Id header contract stays proven for every handler")
                    .isEqualTo(handlerMethodCount);
        }
    }
}

package com.positivity.mcp.internal.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.mcp.internal.exception.InvalidAuditEventTypeException;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
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

/** Unit tests for {@link AuditExceptionHandler}. */
class AuditExceptionHandlerTest {

    private final AuditExceptionHandler sut = new AuditExceptionHandler();

    @Test
    @DisplayName("handleInvalidAuditEventType returns 400 INVALID_EVENT_TYPE with the exception message")
    void handleInvalidAuditEventType_returns400WithMessage() {
        InvalidAuditEventTypeException ex = new InvalidAuditEventTypeException("Unknown event type: FOO");
        MockHttpServletRequest request = new MockHttpServletRequest();

        ResponseEntity<Map<String, Object>> response = sut.handleInvalidAuditEventType(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("status")).isEqualTo("ERROR");
        assertThat(response.getBody().get("code")).isEqualTo("INVALID_EVENT_TYPE");
        assertThat(response.getBody().get("message")).isEqualTo("Unknown event type: FOO");
    }

    // ---------------------------------------------------------------
    // X-Correlation-Id header (ADR-0017 §4, #1729) — proves the single `respond`
    // helper puts the correlation id in the response header for EVERY
    // @ExceptionHandler method, and guards against a future handler forgetting it.
    // ---------------------------------------------------------------

    @Nested
    @DisplayName("X-Correlation-Id header (ADR-0017 §4, #1729)")
    class XCorrelationIdHeader {

        @FunctionalInterface
        interface HandlerInvocation {
            ResponseEntity<Map<String, Object>> invoke(MockHttpServletRequest request);
        }

        /** One entry per {@code @ExceptionHandler} method on {@link AuditExceptionHandler}. */
        private static Stream<Named<HandlerInvocation>> handlerInvocations() {
            AuditExceptionHandler handler = new AuditExceptionHandler();

            return Stream.of(Named.of(
                    "handleInvalidAuditEventType", (HandlerInvocation) request -> handler.handleInvalidAuditEventType(
                            new InvalidAuditEventTypeException("Unknown event type: FOO"), request)));
        }

        @ParameterizedTest
        @MethodSource("handlerInvocations")
        @DisplayName("echoes the inbound X-Correlation-Id in the header")
        void echoesInboundCorrelationId(HandlerInvocation invocation) {
            MockHttpServletRequest request = new MockHttpServletRequest();
            UUID inbound = UUID.fromString("00000000-0000-7000-8000-000000000201");
            request.addHeader(NltiCorrelationIdSupport.CORRELATION_ID_HEADER, inbound.toString());

            ResponseEntity<Map<String, Object>> response = invocation.invoke(request);

            assertThat(response.getHeaders().getFirst(NltiCorrelationIdSupport.CORRELATION_ID_HEADER))
                    .isEqualTo(inbound.toString());
        }

        @ParameterizedTest
        @MethodSource("handlerInvocations")
        @DisplayName("generates a non-blank X-Correlation-Id when absent")
        void generatesCorrelationIdWhenAbsent(HandlerInvocation invocation) {
            MockHttpServletRequest request = new MockHttpServletRequest();

            ResponseEntity<Map<String, Object>> response = invocation.invoke(request);

            String header = response.getHeaders().getFirst(NltiCorrelationIdSupport.CORRELATION_ID_HEADER);
            assertThat(header).isNotBlank();
            assertThat(UUID.fromString(header).version()).isEqualTo(7);
        }

        @Test
        @DisplayName("every @ExceptionHandler method on AuditExceptionHandler has a matching MethodSource entry")
        void everyHandlerMethodIsCovered() {
            long handlerMethodCount = Arrays.stream(AuditExceptionHandler.class.getDeclaredMethods())
                    .filter(method -> method.isAnnotationPresent(ExceptionHandler.class))
                    .count();
            long methodSourceEntryCount = handlerInvocations().count();

            assertThat(methodSourceEntryCount)
                    .as("A new @ExceptionHandler method was added to AuditExceptionHandler without a matching "
                            + "entry in XCorrelationIdHeader#handlerInvocations() in AuditExceptionHandlerTest — "
                            + "add one so the X-Correlation-Id header contract stays proven for every handler")
                    .isEqualTo(handlerMethodCount);
        }
    }
}

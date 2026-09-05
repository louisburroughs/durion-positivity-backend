package com.positivity.referencemock.internal.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Arrays;
import java.util.Map;
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

/** Unit tests for {@link VendorErrorAdvice}. */
class VendorErrorAdviceTest {

    private static final String CORRELATION_ID = "test-correlation-id";

    private final VendorErrorAdvice sut = new VendorErrorAdvice();

    private HttpServletRequest requestWithHeader() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("X-Correlation-Id")).thenReturn(CORRELATION_ID);
        return request;
    }

    private HttpServletRequest requestWithoutHeader() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("X-Correlation-Id")).thenReturn(null);
        return request;
    }

    private HttpServletRequest requestWithBlankHeader() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("X-Correlation-Id")).thenReturn("   ");
        return request;
    }

    @Nested
    @DisplayName("handleAny")
    class HandleAny {

        @Test
        @DisplayName("returns 500 VENDOR_INTERNAL_ERROR with a vendor-shaped body and a referenceId")
        void returns500WithVendorShapedBody() {
            RuntimeException ex = new RuntimeException("boom");

            ResponseEntity<Map<String, Object>> response = sut.handleAny(ex, requestWithHeader());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().get("error")).isEqualTo("VENDOR_INTERNAL_ERROR");
            assertThat(response.getBody().get("message"))
                    .isEqualTo("The labor guide service encountered an unexpected error.");
            assertThat(response.getBody().get("referenceId")).isNotNull();
        }
    }

    // ---------------------------------------------------------------
    // X-Correlation-Id header (ADR-0017 §4, #1729) — proves the single `respond`
    // helper puts the correlation id in the response header for EVERY
    // @ExceptionHandler method, and guards against a future handler forgetting it.
    // This module has no ApiError body, so only the header is asserted.
    // ---------------------------------------------------------------

    @Nested
    @DisplayName("X-Correlation-Id header (ADR-0017 §4, #1729)")
    class XCorrelationIdHeader {

        @FunctionalInterface
        interface HandlerInvocation {
            ResponseEntity<Map<String, Object>> invoke(HttpServletRequest request);
        }

        private static Stream<Named<HandlerInvocation>> handlerInvocations() {
            VendorErrorAdvice handler = new VendorErrorAdvice();

            return Stream.of(Named.of("handleAny", (HandlerInvocation)
                    request -> handler.handleAny(new RuntimeException("boom"), request)));
        }

        @ParameterizedTest
        @MethodSource("handlerInvocations")
        @DisplayName("echoes the inbound X-Correlation-Id in the header")
        void echoesInboundCorrelationId(HandlerInvocation invocation) {
            ResponseEntity<Map<String, Object>> response = invocation.invoke(requestWithHeader());

            assertThat(response.getHeaders().getFirst("X-Correlation-Id")).isEqualTo(CORRELATION_ID);
        }

        @ParameterizedTest
        @MethodSource("handlerInvocations")
        @DisplayName("generates a non-blank X-Correlation-Id when absent")
        void generatesCorrelationIdWhenAbsent(HandlerInvocation invocation) {
            ResponseEntity<Map<String, Object>> response = invocation.invoke(requestWithoutHeader());

            assertThat(response.getHeaders().getFirst("X-Correlation-Id")).isNotBlank();
        }

        @ParameterizedTest
        @MethodSource("handlerInvocations")
        @DisplayName("generates a non-blank X-Correlation-Id when blank")
        void generatesCorrelationIdWhenBlank(HandlerInvocation invocation) {
            ResponseEntity<Map<String, Object>> response = invocation.invoke(requestWithBlankHeader());

            String header = response.getHeaders().getFirst("X-Correlation-Id");
            assertThat(header).isNotBlank();
            assertThat(header).isNotEqualTo("   ");
        }

        @Test
        @DisplayName("every @ExceptionHandler method on VendorErrorAdvice has a matching MethodSource entry")
        void everyHandlerMethodIsCovered() {
            long handlerMethodCount = Arrays.stream(VendorErrorAdvice.class.getDeclaredMethods())
                    .filter(method -> method.isAnnotationPresent(ExceptionHandler.class))
                    .count();
            long methodSourceEntryCount = handlerInvocations().count();

            assertThat(methodSourceEntryCount)
                    .as("A new @ExceptionHandler method was added to VendorErrorAdvice without a matching "
                            + "entry in XCorrelationIdHeader#handlerInvocations() in VendorErrorAdviceTest — "
                            + "add one so the X-Correlation-Id header contract stays proven for every handler")
                    .isEqualTo(handlerMethodCount);
        }
    }
}

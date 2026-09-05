package com.positivity.documents.internal.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.positivity.documents.internal.exception.RenderingException;
import com.positivity.documents.internal.exception.TemplateNotFoundException;
import com.positivity.documents.internal.exception.UnsupportedFormatException;
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
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;

/**
 * Unit tests for {@link DocumentExceptionHandler} — proves the shared {@code build} helper
 * carries the correlation id in both the {@link ApiError} body and the {@code X-Correlation-Id}
 * response header for every {@code @ExceptionHandler} method (ADR-0017 §4, issue #1729).
 */
class DocumentExceptionHandlerTest {

    private static final String CORRELATION_HEADER = "X-Correlation-Id";
    private static final Instant NOW = Instant.parse("2026-08-11T09:00:00Z");

    /** Target for {@link org.springframework.core.MethodParameter} reflection — never invoked. */
    private void bindingTargetMethod() {}

    // ---------------------------------------------------------------
    // X-Correlation-Id header (ADR-0017 §4, #1729) — proves every @ExceptionHandler
    // method routes through the single `build` helper in DocumentExceptionHandler and
    // guards against a future handler forgetting it.
    // ---------------------------------------------------------------

    @Nested
    @DisplayName("X-Correlation-Id header (ADR-0017 §4, #1729)")
    class XCorrelationIdHeader {

        @FunctionalInterface
        interface HandlerInvocation {
            ResponseEntity<ApiError> invoke(HttpServletRequest request);
        }

        /**
         * One entry per {@code @ExceptionHandler} method on {@link DocumentExceptionHandler}.
         * Uses a standalone handler instance so this factory method can stay static, as
         * required by {@code @MethodSource} outside a {@code PER_CLASS} test instance
         * lifecycle.
         */
        private static Stream<Named<HandlerInvocation>> handlerInvocations() {
            Clock fixedClock = Clock.fixed(NOW, ZoneOffset.UTC);
            DocumentExceptionHandler handler = new DocumentExceptionHandler(fixedClock);

            return Stream.of(
                    Named.of("handleUnsupportedFormat", (HandlerInvocation) request -> handler.handleUnsupportedFormat(
                            new UnsupportedFormatException("Unsupported format: FOO"), request)),
                    Named.of("handleTemplateNotFound", (HandlerInvocation) request -> handler.handleTemplateNotFound(
                            new TemplateNotFoundException("Template not found: bar"), request)),
                    Named.of("handleValidation", (HandlerInvocation) request -> {
                        BindingResult binding = new BeanPropertyBindingResult(new Object(), "request");
                        binding.addError(new FieldError("request", "format", "must not be blank"));
                        MethodArgumentNotValidException ex;
                        try {
                            ex = new MethodArgumentNotValidException(
                                    new org.springframework.core.MethodParameter(
                                            DocumentExceptionHandlerTest.class.getDeclaredMethod("bindingTargetMethod"),
                                            -1),
                                    binding);
                        } catch (NoSuchMethodException e) {
                            throw new IllegalStateException(e);
                        }
                        return handler.handleValidation(ex, request);
                    }),
                    Named.of("handleRendering", (HandlerInvocation) request ->
                            handler.handleRendering(RenderingException.malformedInput("bad markup"), request)));
        }

        @ParameterizedTest
        @MethodSource("handlerInvocations")
        @DisplayName("echoes the inbound X-Correlation-Id in both header and body")
        void echoesInboundCorrelationId(HandlerInvocation invocation) {
            HttpServletRequest request = mock(HttpServletRequest.class);
            when(request.getHeader(CORRELATION_HEADER)).thenReturn("trace-1");

            ResponseEntity<ApiError> response = invocation.invoke(request);

            assertThat(response.getHeaders().getFirst(CORRELATION_HEADER)).isEqualTo("trace-1");
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().correlationId()).isEqualTo("trace-1");
        }

        @ParameterizedTest
        @MethodSource("handlerInvocations")
        @DisplayName("generates a non-blank X-Correlation-Id, consistent between header and body, when absent")
        void generatesCorrelationIdWhenAbsent(HandlerInvocation invocation) {
            HttpServletRequest request = mock(HttpServletRequest.class);
            when(request.getHeader(CORRELATION_HEADER)).thenReturn(null);

            ResponseEntity<ApiError> response = invocation.invoke(request);

            String header = response.getHeaders().getFirst(CORRELATION_HEADER);
            assertThat(header).isNotBlank();
            assertThat(response.getBody()).isNotNull();
            assertThat(header).isEqualTo(response.getBody().correlationId());
        }

        @ParameterizedTest
        @MethodSource("handlerInvocations")
        @DisplayName("generates a fresh X-Correlation-Id when the inbound header is blank")
        void generatesCorrelationIdWhenInboundIsBlank(HandlerInvocation invocation) {
            HttpServletRequest request = mock(HttpServletRequest.class);
            when(request.getHeader(CORRELATION_HEADER)).thenReturn("   ");

            ResponseEntity<ApiError> response = invocation.invoke(request);

            String header = response.getHeaders().getFirst(CORRELATION_HEADER);
            assertThat(header).isNotBlank();
            assertThat(header).isNotEqualTo("   ");
            assertThat(response.getBody()).isNotNull();
            assertThat(header).isEqualTo(response.getBody().correlationId());
        }

        @Test
        @DisplayName("every @ExceptionHandler method on DocumentExceptionHandler has a matching MethodSource entry")
        void everyHandlerMethodIsCovered() {
            long handlerMethodCount = Arrays.stream(DocumentExceptionHandler.class.getDeclaredMethods())
                    .filter(method -> method.isAnnotationPresent(ExceptionHandler.class))
                    .count();
            long methodSourceEntryCount = handlerInvocations().count();

            assertThat(methodSourceEntryCount)
                    .as("A new @ExceptionHandler method was added to DocumentExceptionHandler without a matching "
                            + "entry in XCorrelationIdHeader#handlerInvocations() in DocumentExceptionHandlerTest — "
                            + "add one so the X-Correlation-Id header contract stays proven for every handler")
                    .isEqualTo(handlerMethodCount);
        }
    }
}

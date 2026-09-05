package com.positivity.tax.internal.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.positivity.shared.error.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Collections;
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

/** Unit tests for {@link TaxExceptionHandler}. */
class TaxExceptionHandlerTest {

    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC);
    private static final String CORRELATION_ID = "test-correlation-id";

    private final TaxExceptionHandler sut = new TaxExceptionHandler(TEST_CLOCK);

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
    @DisplayName("handleRateLookupUnsupported")
    class HandleRateLookupUnsupported {

        @Test
        @DisplayName("returns 501 TAX_RATE_LOOKUP_UNSUPPORTED with the exception message")
        void returns501WithMessage() {
            TaxRateLookupUnsupportedException ex =
                    new TaxRateLookupUnsupportedException("Active provider does not support rate-only lookup");

            ResponseEntity<ApiError> response = sut.handleRateLookupUnsupported(ex, requestWithHeader());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_IMPLEMENTED);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().code()).isEqualTo("TAX_RATE_LOOKUP_UNSUPPORTED");
            assertThat(response.getBody().message()).isEqualTo("Active provider does not support rate-only lookup");
        }
    }

    @Nested
    @DisplayName("handleConstraintViolation")
    class HandleConstraintViolation {

        @Test
        @DisplayName("returns 400 VALIDATION_ERROR with a fixed message")
        void returns400WithFixedMessage() {
            ConstraintViolationException ex =
                    new ConstraintViolationException("bad countryCode", Collections.emptySet());

            ResponseEntity<ApiError> response = sut.handleConstraintViolation(ex, requestWithHeader());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().code()).isEqualTo("VALIDATION_ERROR");
            assertThat(response.getBody().message()).isEqualTo("Request validation failed");
        }
    }

    // ---------------------------------------------------------------
    // X-Correlation-Id header (ADR-0017 §4, #1729) — TaxExceptionHandler was already fully
    // compliant (both handlers route through the single `build` helper, which sets the header
    // and echoes/generates via UUIDv7Generator); this nested class proves the contract holds for
    // every handler and guards against a future handler bypassing `build`.
    // ---------------------------------------------------------------

    @Nested
    @DisplayName("X-Correlation-Id header (ADR-0017 §4, #1729)")
    class XCorrelationIdHeader {

        @FunctionalInterface
        interface HandlerInvocation {
            ResponseEntity<ApiError> invoke(HttpServletRequest request);
        }

        private static Stream<Named<HandlerInvocation>> handlerInvocations() {
            TaxExceptionHandler handler = new TaxExceptionHandler(TEST_CLOCK);

            return Stream.of(
                    Named.of("handleRateLookupUnsupported", (HandlerInvocation)
                            request -> handler.handleRateLookupUnsupported(
                                    new TaxRateLookupUnsupportedException("Rate lookup unsupported"), request)),
                    Named.of("handleConstraintViolation", (HandlerInvocation)
                            request -> handler.handleConstraintViolation(
                                    new ConstraintViolationException("bad countryCode", Collections.emptySet()),
                                    request)));
        }

        @ParameterizedTest
        @MethodSource("handlerInvocations")
        @DisplayName("echoes the inbound X-Correlation-Id in both header and body")
        void echoesInboundCorrelationId(HandlerInvocation invocation) {
            ResponseEntity<ApiError> response = invocation.invoke(requestWithHeader());

            assertThat(response.getHeaders().getFirst("X-Correlation-Id")).isEqualTo(CORRELATION_ID);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getHeaders().getFirst("X-Correlation-Id"))
                    .isEqualTo(response.getBody().correlationId());
        }

        @ParameterizedTest
        @MethodSource("handlerInvocations")
        @DisplayName("generates a non-blank X-Correlation-Id, consistent between header and body, when absent")
        void generatesCorrelationIdWhenAbsent(HandlerInvocation invocation) {
            ResponseEntity<ApiError> response = invocation.invoke(requestWithoutHeader());

            String header = response.getHeaders().getFirst("X-Correlation-Id");
            assertThat(header).isNotBlank();
            assertThat(response.getBody()).isNotNull();
            assertThat(header).isEqualTo(response.getBody().correlationId());
        }

        @ParameterizedTest
        @MethodSource("handlerInvocations")
        @DisplayName("generates a non-blank X-Correlation-Id when blank")
        void generatesCorrelationIdWhenBlank(HandlerInvocation invocation) {
            ResponseEntity<ApiError> response = invocation.invoke(requestWithBlankHeader());

            String header = response.getHeaders().getFirst("X-Correlation-Id");
            assertThat(header).isNotBlank();
            assertThat(header).isNotEqualTo("   ");
        }

        @Test
        @DisplayName("every @ExceptionHandler method on TaxExceptionHandler has a matching MethodSource entry")
        void everyHandlerMethodIsCovered() {
            long handlerMethodCount = Arrays.stream(TaxExceptionHandler.class.getDeclaredMethods())
                    .filter(method -> method.isAnnotationPresent(ExceptionHandler.class))
                    .count();
            long methodSourceEntryCount = handlerInvocations().count();

            assertThat(methodSourceEntryCount)
                    .as("A new @ExceptionHandler method was added to TaxExceptionHandler without a matching "
                            + "entry in XCorrelationIdHeader#handlerInvocations() in TaxExceptionHandlerTest — "
                            + "add one so the X-Correlation-Id header contract stays proven for every handler")
                    .isEqualTo(handlerMethodCount);
        }
    }
}

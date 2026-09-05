package com.positivity.bulkloader.internal.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.bulkloader.internal.exception.JobOwnershipViolationException;
import com.positivity.bulkloader.internal.exception.TusOffsetConflictException;
import com.positivity.bulkloader.internal.exception.TusUploadExpiredException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.core.MethodParameter;
import org.springframework.http.ProblemDetail;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;

/**
 * Unit tests for {@link BulkLoaderExceptionHandler} — proves the single {@code problem} helper
 * puts the correlation id in both the {@link ProblemDetail} body ({@code correlationId} extension
 * property) and the {@code X-Correlation-Id} response header for every {@code @ExceptionHandler}
 * method (ADR-0017 §4, issue #1729).
 */
class BulkLoaderExceptionHandlerTest {

    private static final String CORRELATION_ID = "018f0a1b-2c3d-7e4f-8a9b-0c1d2e3fcc05";

    private static MockHttpServletRequest request() {
        return new MockHttpServletRequest("GET", "/v1/bulk-loader/jobs");
    }

    private static MockHttpServletRequest requestWithCorrelationId(String value) {
        MockHttpServletRequest request = request();
        request.addHeader("X-Correlation-Id", value);
        return request;
    }

    /** Any real method parameter works: the handler only reads the binding result. */
    private static MethodParameter methodParameter() throws NoSuchMethodException {
        return new MethodParameter(BulkLoaderExceptionHandlerTest.class.getDeclaredMethod("sample", String.class), 0);
    }

    @SuppressWarnings("unused")
    private static void sample(String value) {
        // Signature-only holder for MethodParameter.
    }

    // ---------------------------------------------------------------
    // X-Correlation-Id header (ADR-0017 §4, #1729) — proves the single `problem`
    // helper puts the correlation id in both the body and the header for EVERY
    // @ExceptionHandler method, and guards against a future handler forgetting it.
    //
    // BulkLoaderExceptionHandler was already fully compliant when this sweep landed:
    // every handler routes through `problem`, which sets the header on the servlet
    // response and the `correlationId` extension property on the body. No refactor
    // was needed here — this nested class only adds the regression guard.
    // ---------------------------------------------------------------

    @Nested
    @DisplayName("X-Correlation-Id header (ADR-0017 §4, #1729)")
    class XCorrelationIdHeader {

        @FunctionalInterface
        interface HandlerInvocation {
            ProblemDetail invoke(MockHttpServletRequest request, MockHttpServletResponse response);
        }

        /**
         * One entry per {@code @ExceptionHandler} method on {@link BulkLoaderExceptionHandler}.
         * Uses a standalone handler instance so this factory method can stay static, as required
         * by {@code @MethodSource} outside a {@code PER_CLASS} test instance lifecycle.
         */
        private static Stream<Named<HandlerInvocation>> handlerInvocations() throws NoSuchMethodException {
            BulkLoaderExceptionHandler sut = new BulkLoaderExceptionHandler();

            BindingResult bindingResult = new BeanPropertyBindingResult(new Object(), "request");
            bindingResult.addError(new org.springframework.validation.FieldError(
                    "request", "fileName", null, false, null, null, "fileName is required"));
            MethodArgumentNotValidException methodArgumentNotValidException =
                    new MethodArgumentNotValidException(methodParameter(), bindingResult);

            return Stream.of(
                    Named.of("handleOwnershipViolation", (HandlerInvocation)
                            (request, response) -> sut.handleOwnershipViolation(
                                    new JobOwnershipViolationException("job-1"), request, response)),
                    Named.of("handleNotFound", (HandlerInvocation) (request, response) ->
                            sut.handleNotFound(new NoSuchElementException("Job not found"), request, response)),
                    Named.of("handleConflict", (HandlerInvocation) (request, response) ->
                            sut.handleConflict(new IllegalStateException("Job already completed"), request, response)),
                    Named.of("handleTusConflict", (HandlerInvocation) (request, response) ->
                            sut.handleTusConflict(new TusOffsetConflictException(10L, 5L), request, response)),
                    Named.of("handleTusExpired", (HandlerInvocation) (request, response) ->
                            sut.handleTusExpired(new TusUploadExpiredException(UUID.randomUUID()), request, response)),
                    Named.of("handleValidation", (HandlerInvocation) (request, response) ->
                            sut.handleValidation(methodArgumentNotValidException, request, response)));
        }

        @ParameterizedTest
        @MethodSource("handlerInvocations")
        @DisplayName("echoes the inbound X-Correlation-Id in both header and body")
        void echoesInboundCorrelationId(HandlerInvocation invocation) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            ProblemDetail problemDetail = invocation.invoke(requestWithCorrelationId(CORRELATION_ID), response);

            assertThat(response.getHeader("X-Correlation-Id")).isEqualTo(CORRELATION_ID);
            assertThat(problemDetail.getProperties()).isNotNull();
            assertThat(problemDetail.getProperties().get("correlationId")).isEqualTo(CORRELATION_ID);
        }

        @ParameterizedTest
        @MethodSource("handlerInvocations")
        @DisplayName("generates a non-blank X-Correlation-Id, consistent between header and body, when absent")
        void generatesCorrelationIdWhenAbsent(HandlerInvocation invocation) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            ProblemDetail problemDetail = invocation.invoke(request(), response);

            String header = response.getHeader("X-Correlation-Id");
            assertThat(header).isNotBlank();
            assertThat(problemDetail.getProperties()).isNotNull();
            assertThat(header).isEqualTo(problemDetail.getProperties().get("correlationId"));
        }

        @Test
        @DisplayName("every @ExceptionHandler method on BulkLoaderExceptionHandler has a matching MethodSource entry")
        void everyHandlerMethodIsCovered() throws NoSuchMethodException {
            long handlerMethodCount = Arrays.stream(BulkLoaderExceptionHandler.class.getDeclaredMethods())
                    .filter((Method method) -> method.isAnnotationPresent(ExceptionHandler.class))
                    .count();
            long methodSourceEntryCount = handlerInvocations().count();

            assertThat(methodSourceEntryCount)
                    .as("A new @ExceptionHandler method was added to BulkLoaderExceptionHandler without a matching "
                            + "entry in XCorrelationIdHeader#handlerInvocations() in BulkLoaderExceptionHandlerTest "
                            + "— add one so the X-Correlation-Id header contract stays proven for every handler")
                    .isEqualTo(handlerMethodCount);
        }
    }
}

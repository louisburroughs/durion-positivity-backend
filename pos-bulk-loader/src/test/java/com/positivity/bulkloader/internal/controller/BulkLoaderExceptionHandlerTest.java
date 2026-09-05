package com.positivity.bulkloader.internal.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.positivity.bulkloader.internal.exception.JobOwnershipViolationException;
import com.positivity.bulkloader.internal.exception.TusOffsetConflictException;
import com.positivity.bulkloader.internal.exception.TusUploadExpiredException;
import com.positivity.shared.error.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;

/**
 * Unit tests for {@link BulkLoaderExceptionHandler} — proves the single {@code envelope}/{@code
 * handleValidation} response-building paths put the correlation id in both the {@link ApiError}
 * body ({@code correlationId}) and the {@code X-Correlation-Id} response header for every {@code
 * @ExceptionHandler} method (ADR-0017 §4, issue #1729), on top of #1716's move from {@code
 * ProblemDetail} to the {@link ApiError} envelope.
 */
class BulkLoaderExceptionHandlerTest {

    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC);
    private static final String CORRELATION_ID = "018f0a1b-2c3d-7e4f-8a9b-0c1d2e3fcc05";

    private final BulkLoaderExceptionHandler sut = new BulkLoaderExceptionHandler(fixedClockProvider());

    private static ObjectProvider<Clock> fixedClockProvider() {
        @SuppressWarnings("unchecked")
        ObjectProvider<Clock> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable(any())).thenReturn(TEST_CLOCK);
        return provider;
    }

    private static HttpServletRequest requestWithHeader() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("X-Correlation-Id")).thenReturn(CORRELATION_ID);
        return request;
    }

    private static HttpServletRequest requestWithoutHeader() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("X-Correlation-Id")).thenReturn(null);
        return request;
    }

    private static HttpServletRequest requestWithBlankHeader() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("X-Correlation-Id")).thenReturn("   ");
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

    private static MethodArgumentNotValidException methodArgumentNotValidException() throws NoSuchMethodException {
        BindingResult bindingResult = new BeanPropertyBindingResult(new Object(), "request");
        bindingResult.addError(new FieldError("request", "fileName", null, false, null, null, "fileName is required"));
        return new MethodArgumentNotValidException(methodParameter(), bindingResult);
    }

    @Nested
    @DisplayName("handleOwnershipViolation")
    class HandleOwnershipViolation {

        @Test
        @DisplayName("returns 403 FORBIDDEN and sets the correlation id header")
        void returns403AndSetsHeader() {
            JobOwnershipViolationException ex = new JobOwnershipViolationException("job-1");
            MockHttpServletResponse response = new MockHttpServletResponse();

            ResponseEntity<ApiError> result = sut.handleOwnershipViolation(ex, requestWithHeader(), response);

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(result.getBody()).isNotNull();
            assertThat(result.getBody().code()).isEqualTo("FORBIDDEN");
            assertThat(result.getBody().message()).isEqualTo("Access denied");
            assertThat(result.getHeaders().getFirst("X-Correlation-Id")).isEqualTo(CORRELATION_ID);
        }
    }

    @Nested
    @DisplayName("handleNotFound")
    class HandleNotFound {

        @Test
        @DisplayName("returns 404 BULK_JOB_NOT_FOUND and sets the correlation id header")
        void returns404AndSetsHeader() {
            NoSuchElementException ex = new NoSuchElementException("Job not found");
            MockHttpServletResponse response = new MockHttpServletResponse();

            ResponseEntity<ApiError> result = sut.handleNotFound(ex, requestWithHeader(), response);

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(result.getBody()).isNotNull();
            assertThat(result.getBody().code()).isEqualTo("BULK_JOB_NOT_FOUND");
            assertThat(result.getBody().message()).isEqualTo("Job not found");
            assertThat(result.getHeaders().getFirst("X-Correlation-Id")).isEqualTo(CORRELATION_ID);
        }
    }

    @Nested
    @DisplayName("handleConflict")
    class HandleConflict {

        @Test
        @DisplayName("returns 409 BULK_JOB_INVALID_STATE and sets the correlation id header")
        void returns409AndSetsHeader() {
            IllegalStateException ex = new IllegalStateException("Job already completed");
            MockHttpServletResponse response = new MockHttpServletResponse();

            ResponseEntity<ApiError> result = sut.handleConflict(ex, requestWithHeader(), response);

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(result.getBody()).isNotNull();
            assertThat(result.getBody().code()).isEqualTo("BULK_JOB_INVALID_STATE");
            assertThat(result.getHeaders().getFirst("X-Correlation-Id")).isEqualTo(CORRELATION_ID);
        }
    }

    @Nested
    @DisplayName("handleTusConflict")
    class HandleTusConflict {

        @Test
        @DisplayName("returns 409 TUS_OFFSET_CONFLICT, sets Tus-Resumable, and sets the correlation id header")
        void returns409AndSetsHeaders() {
            TusOffsetConflictException ex = new TusOffsetConflictException(10L, 5L);
            MockHttpServletResponse response = new MockHttpServletResponse();

            ResponseEntity<ApiError> result = sut.handleTusConflict(ex, requestWithHeader(), response);

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(result.getBody()).isNotNull();
            assertThat(result.getBody().code()).isEqualTo("TUS_OFFSET_CONFLICT");
            assertThat(result.getHeaders().getFirst("X-Correlation-Id")).isEqualTo(CORRELATION_ID);
            assertThat(response.getHeader("Tus-Resumable")).isEqualTo("1.0.0");
        }
    }

    @Nested
    @DisplayName("handleTusExpired")
    class HandleTusExpired {

        @Test
        @DisplayName("returns 410 TUS_UPLOAD_EXPIRED, sets Tus-Resumable, and sets the correlation id header")
        void returns410AndSetsHeaders() {
            TusUploadExpiredException ex = new TusUploadExpiredException(UUID.randomUUID());
            MockHttpServletResponse response = new MockHttpServletResponse();

            ResponseEntity<ApiError> result = sut.handleTusExpired(ex, requestWithHeader(), response);

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.GONE);
            assertThat(result.getBody()).isNotNull();
            assertThat(result.getBody().code()).isEqualTo("TUS_UPLOAD_EXPIRED");
            assertThat(result.getHeaders().getFirst("X-Correlation-Id")).isEqualTo(CORRELATION_ID);
            assertThat(response.getHeader("Tus-Resumable")).isEqualTo("1.0.0");
        }
    }

    @Nested
    @DisplayName("handleValidation")
    class HandleValidation {

        @Test
        @DisplayName("returns 400 VALIDATION_ERROR with field errors and sets the correlation id header")
        void returns400WithFieldErrorsAndSetsHeader() throws Exception {
            MethodArgumentNotValidException ex = methodArgumentNotValidException();
            MockHttpServletResponse response = new MockHttpServletResponse();

            ResponseEntity<ApiError> result = sut.handleValidation(ex, requestWithHeader(), response);

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            ApiError body = result.getBody();
            assertThat(body).isNotNull();
            assertThat(body.code()).isEqualTo("VALIDATION_ERROR");
            assertThat(body.message()).isEqualTo("Request validation failed");
            assertThat(body.fieldErrors()).hasSize(1);
            assertThat(body.fieldErrors().get(0).field()).isEqualTo("fileName");
            assertThat(body.fieldErrors().get(0).message()).isEqualTo("fileName is required");
            assertThat(result.getHeaders().getFirst("X-Correlation-Id")).isEqualTo(CORRELATION_ID);
        }
    }

    // ---------------------------------------------------------------
    // X-Correlation-Id header (ADR-0017 §4, #1729) — proves the single response-building path
    // for every @ExceptionHandler method puts the correlation id in both the body and the
    // header, and guards against a future handler forgetting it.
    // ---------------------------------------------------------------

    @Nested
    @DisplayName("X-Correlation-Id header (ADR-0017 §4, #1729)")
    class XCorrelationIdHeader {

        @FunctionalInterface
        interface HandlerInvocation {
            ResponseEntity<ApiError> invoke(HttpServletRequest request, MockHttpServletResponse response);
        }

        /**
         * One entry per {@code @ExceptionHandler} method on {@link BulkLoaderExceptionHandler}.
         * Uses a standalone handler instance so this factory method can stay static, as required
         * by {@code @MethodSource} outside a {@code PER_CLASS} test instance lifecycle.
         */
        private static Stream<Named<HandlerInvocation>> handlerInvocations() throws NoSuchMethodException {
            BulkLoaderExceptionHandler handler = new BulkLoaderExceptionHandler(fixedClockProvider());
            MethodArgumentNotValidException methodArgumentNotValidException = methodArgumentNotValidException();

            return Stream.of(
                    Named.of("handleOwnershipViolation", (HandlerInvocation)
                            (request, response) -> handler.handleOwnershipViolation(
                                    new JobOwnershipViolationException("job-1"), request, response)),
                    Named.of("handleNotFound", (HandlerInvocation) (request, response) ->
                            handler.handleNotFound(new NoSuchElementException("Job not found"), request, response)),
                    Named.of("handleConflict", (HandlerInvocation) (request, response) -> handler.handleConflict(
                            new IllegalStateException("Job already completed"), request, response)),
                    Named.of("handleTusConflict", (HandlerInvocation) (request, response) ->
                            handler.handleTusConflict(new TusOffsetConflictException(10L, 5L), request, response)),
                    Named.of("handleTusExpired", (HandlerInvocation) (request, response) -> handler.handleTusExpired(
                            new TusUploadExpiredException(UUID.randomUUID()), request, response)),
                    Named.of("handleValidation", (HandlerInvocation) (request, response) ->
                            handler.handleValidation(methodArgumentNotValidException, request, response)));
        }

        @ParameterizedTest
        @MethodSource("handlerInvocations")
        @DisplayName("echoes the inbound X-Correlation-Id in both header and body")
        void echoesInboundCorrelationId(HandlerInvocation invocation) {
            ResponseEntity<ApiError> result = invocation.invoke(requestWithHeader(), new MockHttpServletResponse());

            assertThat(result.getHeaders().getFirst("X-Correlation-Id")).isEqualTo(CORRELATION_ID);
            assertThat(result.getBody()).isNotNull();
            assertThat(result.getHeaders().getFirst("X-Correlation-Id"))
                    .isEqualTo(result.getBody().correlationId());
        }

        @ParameterizedTest
        @MethodSource("handlerInvocations")
        @DisplayName("generates a non-blank X-Correlation-Id, consistent between header and body, when absent")
        void generatesCorrelationIdWhenAbsent(HandlerInvocation invocation) {
            ResponseEntity<ApiError> result = invocation.invoke(requestWithoutHeader(), new MockHttpServletResponse());

            String header = result.getHeaders().getFirst("X-Correlation-Id");
            assertThat(header).isNotBlank();
            assertThat(result.getBody()).isNotNull();
            assertThat(header).isEqualTo(result.getBody().correlationId());
        }

        @ParameterizedTest
        @MethodSource("handlerInvocations")
        @DisplayName("generates a fresh X-Correlation-Id when the inbound header is blank")
        void generatesCorrelationIdWhenBlank(HandlerInvocation invocation) {
            ResponseEntity<ApiError> result =
                    invocation.invoke(requestWithBlankHeader(), new MockHttpServletResponse());

            String header = result.getHeaders().getFirst("X-Correlation-Id");
            assertThat(header).isNotBlank();
            assertThat(header).isNotEqualTo("   ");
            assertThat(result.getBody()).isNotNull();
            assertThat(header).isEqualTo(result.getBody().correlationId());
        }

        @Test
        @DisplayName("every @ExceptionHandler method on BulkLoaderExceptionHandler has a matching MethodSource entry")
        void everyHandlerMethodIsCovered() throws NoSuchMethodException {
            long handlerMethodCount = Arrays.stream(BulkLoaderExceptionHandler.class.getDeclaredMethods())
                    .filter(method -> method.isAnnotationPresent(ExceptionHandler.class))
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

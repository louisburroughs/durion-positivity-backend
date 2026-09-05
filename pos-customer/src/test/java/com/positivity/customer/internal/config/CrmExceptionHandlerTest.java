package com.positivity.customer.internal.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.customer.internal.exception.CrmDuplicateResourceException;
import com.positivity.customer.internal.exception.CrmResourceNotFoundException;
import com.positivity.customer.internal.exception.CrmTooManyRequestsException;
import com.positivity.customer.internal.exception.CrmUnprocessableEntityException;
import com.positivity.customer.internal.exception.CrmValidationException;
import com.positivity.customer.internal.exception.DuplicateRedemptionException;
import com.positivity.shared.error.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;

/**
 * Unit tests for {@link CrmExceptionHandler} (ADR-0017 error envelope).
 *
 * <p>
 * Two distinctions here are load-bearing rather than cosmetic. A structurally
 * valid request the domain refuses is a 422, separate from a malformed body's
 * 400, so a caller can tell "your JSON is wrong" from "your JSON is fine but
 * this is not allowed". And two responses deliberately withhold detail: a 403
 * says only that permission is missing, and the public inquiry form's 429 stays
 * generic, because a response naming the exact limit or remaining quota would
 * tell an abuser how to pace themselves.
 *
 * <p>
 * The correlation id is echoed onto both the body and the
 * {@code X-Correlation-Id} response header, preserving an inbound id so a trace
 * started upstream survives the error; a blank inbound header counts as absent.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("CrmExceptionHandler — ApiError envelope and correlation")
class CrmExceptionHandlerTest {

    private static final String CORRELATION_HEADER = "X-Correlation-Id";
    private static final Instant NOW = Instant.parse("2026-08-11T09:00:00Z");
    private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-0000000000e1");

    @Mock
    private HttpServletRequest request;

    @Mock
    private HttpServletResponse response;

    private CrmExceptionHandler handler;

    @BeforeEach
    void setUp() {
        handler = new CrmExceptionHandler(Clock.fixed(NOW, ZoneOffset.UTC));
        when(request.getRequestURI()).thenReturn("/v1/crm/accounts");
        when(request.getHeader(CORRELATION_HEADER)).thenReturn("trace-1");
    }

    private void assertEnvelope(ResponseEntity<ApiError> result, HttpStatus status, String code) {
        assertThat(result.getStatusCode()).isEqualTo(status);
        assertThat(result.getBody()).isNotNull();
        assertThat(result.getBody().code()).isEqualTo(code);
        assertThat(result.getBody().status()).isEqualTo(status.value());
        assertThat(result.getBody().timestamp()).isEqualTo(NOW.toString());
        assertThat(result.getBody().correlationId()).isEqualTo("trace-1");
        verify(response).setHeader(CORRELATION_HEADER, "trace-1");
    }

    @Test
    @DisplayName("maps a permission failure to 403 without naming the missing authority")
    void accessDenied() {
        ResponseEntity<ApiError> result =
                handler.handleAccessDenied(new AccessDeniedException("missing crm:party:view"), request, response);

        assertEnvelope(result, HttpStatus.FORBIDDEN, "PERMISSION_DENIED");
        // The required authority is a detail of the authorization model; it stays in the log.
        assertThat(result.getBody().message()).doesNotContain("crm:party:view");
    }

    @Test
    @DisplayName("maps a replayed promotion redemption to 409 DUPLICATE_REDEMPTION")
    void duplicateRedemption() {
        assertEnvelope(
                handler.handleDuplicateRedemption(new DuplicateRedemptionException(ID, ID), request, response),
                HttpStatus.CONFLICT,
                "DUPLICATE_REDEMPTION");
    }

    @Test
    @DisplayName("maps a missing resource to 404 RESOURCE_NOT_FOUND, keeping the domain's message")
    void notFound() {
        ResponseEntity<ApiError> result =
                handler.handleNotFound(new CrmResourceNotFoundException("Party", ID), request, response);

        assertEnvelope(result, HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");
        assertThat(result.getBody().message()).contains(ID.toString());
    }

    @Test
    @DisplayName("maps a duplicate resource to 409 DUPLICATE_RESOURCE")
    void duplicateResource() {
        assertEnvelope(
                handler.handleDuplicateResource(new CrmDuplicateResourceException("Segment", "VIP"), request, response),
                HttpStatus.CONFLICT,
                "DUPLICATE_RESOURCE");
    }

    @Test
    @DisplayName("maps a refused-but-well-formed request to 422, distinct from a malformed body's 400")
    void unprocessable() {
        ResponseEntity<ApiError> result = handler.handleUnprocessable(
                new CrmUnprocessableEntityException("predicate references an unknown attribute"), request, response);

        assertEnvelope(result, HttpStatus.UNPROCESSABLE_CONTENT, "UNPROCESSABLE_CONTENT");
        // 422 vs 400 is what lets a caller tell "your JSON is wrong" from "this is not allowed".
        assertThat(result.getBody().message()).isEqualTo("predicate references an unknown attribute");
    }

    @Test
    @DisplayName("maps a rate-limited inquiry to 429")
    void tooManyRequests() {
        assertEnvelope(
                handler.handleTooManyRequests(new CrmTooManyRequestsException("Too many requests"), request, response),
                HttpStatus.TOO_MANY_REQUESTS,
                "TOO_MANY_REQUESTS");
    }

    @Test
    @DisplayName("maps a module validation failure to 400 VALIDATION_ERROR")
    void validation() {
        ResponseEntity<ApiError> result =
                handler.handleValidation(new CrmValidationException("page must be >= 0"), request, response);

        assertEnvelope(result, HttpStatus.BAD_REQUEST, "VALIDATION_ERROR");
        assertThat(result.getBody().message()).isEqualTo("page must be >= 0");
    }

    @Test
    @DisplayName("reports every rejected field, defaulting a null message rather than emitting null")
    void bodyValidationListsFieldErrors() throws NoSuchMethodException {
        BindingResult binding = new BeanPropertyBindingResult(new Object(), "request");
        binding.addError(new FieldError("request", "displayName", "must not be blank"));
        binding.addError(new FieldError("request", "partyType", null));
        MethodArgumentNotValidException ex = new MethodArgumentNotValidException(
                new org.springframework.core.MethodParameter(
                        CrmExceptionHandlerTest.class.getDeclaredMethod("bodyValidationListsFieldErrors"), -1),
                binding);

        ResponseEntity<ApiError> result = handler.handleMethodArgumentNotValid(ex, request, response);

        assertEnvelope(result, HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
        assertThat(result.getBody().fieldErrors())
                .extracting(ApiError.FieldError::field, ApiError.FieldError::message)
                .containsExactly(tuple("displayName", "must not be blank"), tuple("partyType", "Invalid value"));
    }

    @Test
    @DisplayName("mints a correlation id when the caller sent none, and echoes it on the header")
    void mintsCorrelationIdWhenAbsent() {
        when(request.getHeader(CORRELATION_HEADER)).thenReturn(null);

        ResponseEntity<ApiError> result =
                handler.handleValidation(new CrmValidationException("bad"), request, response);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(response).setHeader(org.mockito.ArgumentMatchers.eq(CORRELATION_HEADER), captor.capture());
        assertThat(result.getBody()).isNotNull();
        assertThat(result.getBody().correlationId()).isNotBlank().isEqualTo(captor.getValue());
    }

    @Test
    @DisplayName("treats a blank inbound correlation id as absent rather than echoing it")
    void blankCorrelationIdIsReplaced() {
        when(request.getHeader(CORRELATION_HEADER)).thenReturn("   ");

        ResponseEntity<ApiError> result =
                handler.handleValidation(new CrmValidationException("bad"), request, response);

        assertThat(result.getBody()).isNotNull();
        assertThat(result.getBody().correlationId()).isNotBlank().isNotEqualTo("   ");
    }

    @Test
    @DisplayName("still produces an envelope when there is no request to read a path or id from")
    void nullRequestIsTolerated() {
        // The advice is also reachable from error dispatches that carry no request object; a
        // NullPointerException here would replace the domain's error with a 500.
        ResponseEntity<ApiError> result =
                handler.handleNotFound(new CrmResourceNotFoundException("Party", ID), null, response);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(result.getBody()).isNotNull();
        assertThat(result.getBody().correlationId()).isNotBlank();
    }

    /** Target for {@link MethodParameter} reflection in {@link XCorrelationIdHeader} — never invoked. */
    private void bindingTargetMethod() {}

    // ---------------------------------------------------------------
    // X-Correlation-Id header (ADR-0017 §4, #1729) — proves every @ExceptionHandler
    // method routes through the single response.setHeader(...) path in CrmExceptionHandler
    // and guards against a future handler forgetting it.
    // ---------------------------------------------------------------

    @Nested
    @DisplayName("X-Correlation-Id header (ADR-0017 §4, #1729)")
    class XCorrelationIdHeader {

        @FunctionalInterface
        interface HandlerInvocation {
            ResponseEntity<ApiError> invoke(HttpServletRequest request, HttpServletResponse response);
        }

        /**
         * One entry per {@code @ExceptionHandler} method on {@link CrmExceptionHandler}. Uses a
         * standalone handler instance (not the outer test's {@code handler}) so this factory
         * method can stay static, as required by {@code @MethodSource} outside a
         * {@code PER_CLASS} test instance lifecycle.
         */
        private static Stream<Named<HandlerInvocation>> handlerInvocations() {
            Clock fixedClock = Clock.fixed(NOW, ZoneOffset.UTC);
            CrmExceptionHandler h = new CrmExceptionHandler(fixedClock);

            return Stream.of(
                    Named.of("handleAccessDenied", (HandlerInvocation) (request, response) -> h.handleAccessDenied(
                            new AccessDeniedException("missing crm:party:view"), request, response)),
                    Named.of("handleDuplicateRedemption", (HandlerInvocation) (request, response) ->
                            h.handleDuplicateRedemption(new DuplicateRedemptionException(ID, ID), request, response)),
                    Named.of("handleNotFound", (HandlerInvocation) (request, response) ->
                            h.handleNotFound(new CrmResourceNotFoundException("Party", ID), request, response)),
                    Named.of("handleDuplicateResource", (HandlerInvocation)
                            (request, response) -> h.handleDuplicateResource(
                                    new CrmDuplicateResourceException("Segment", "VIP"), request, response)),
                    Named.of("handleUnprocessable", (HandlerInvocation) (request, response) -> h.handleUnprocessable(
                            new CrmUnprocessableEntityException("predicate references an unknown attribute"),
                            request,
                            response)),
                    Named.of(
                            "handleTooManyRequests", (HandlerInvocation) (request, response) -> h.handleTooManyRequests(
                                    new CrmTooManyRequestsException("Too many requests"), request, response)),
                    Named.of("handleMethodArgumentNotValid", (HandlerInvocation) (request, response) -> {
                        BindingResult binding = new BeanPropertyBindingResult(new Object(), "request");
                        binding.addError(new FieldError("request", "displayName", "must not be blank"));
                        MethodArgumentNotValidException ex;
                        try {
                            ex = new MethodArgumentNotValidException(
                                    new org.springframework.core.MethodParameter(
                                            CrmExceptionHandlerTest.class.getDeclaredMethod("bindingTargetMethod"), -1),
                                    binding);
                        } catch (NoSuchMethodException e) {
                            throw new IllegalStateException(e);
                        }
                        return h.handleMethodArgumentNotValid(ex, request, response);
                    }),
                    Named.of("handleValidation", (HandlerInvocation) (request, response) ->
                            h.handleValidation(new CrmValidationException("page must be >= 0"), request, response)));
        }

        @ParameterizedTest
        @MethodSource("handlerInvocations")
        @DisplayName("echoes the inbound X-Correlation-Id in both header and body")
        void echoesInboundCorrelationId(HandlerInvocation invocation) {
            HttpServletRequest req = mock(HttpServletRequest.class);
            HttpServletResponse resp = mock(HttpServletResponse.class);
            when(req.getRequestURI()).thenReturn("/v1/crm/accounts");
            when(req.getHeader(CORRELATION_HEADER)).thenReturn("trace-1");

            ResponseEntity<ApiError> result = invocation.invoke(req, resp);

            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            verify(resp).setHeader(eq(CORRELATION_HEADER), captor.capture());
            assertThat(captor.getValue()).isEqualTo("trace-1");
            assertThat(result.getBody()).isNotNull();
            assertThat(result.getBody().correlationId()).isEqualTo("trace-1");
        }

        @ParameterizedTest
        @MethodSource("handlerInvocations")
        @DisplayName("generates a non-blank X-Correlation-Id, consistent between header and body, when absent")
        void generatesCorrelationIdWhenAbsent(HandlerInvocation invocation) {
            HttpServletRequest req = mock(HttpServletRequest.class);
            HttpServletResponse resp = mock(HttpServletResponse.class);
            when(req.getRequestURI()).thenReturn("/v1/crm/accounts");
            when(req.getHeader(CORRELATION_HEADER)).thenReturn(null);

            ResponseEntity<ApiError> result = invocation.invoke(req, resp);

            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            verify(resp).setHeader(eq(CORRELATION_HEADER), captor.capture());
            assertThat(captor.getValue()).isNotBlank();
            assertThat(result.getBody()).isNotNull();
            assertThat(result.getBody().correlationId()).isEqualTo(captor.getValue());
        }

        @ParameterizedTest
        @MethodSource("handlerInvocations")
        @DisplayName("generates a fresh X-Correlation-Id when the inbound header is blank")
        void generatesCorrelationIdWhenInboundIsBlank(HandlerInvocation invocation) {
            HttpServletRequest req = mock(HttpServletRequest.class);
            HttpServletResponse resp = mock(HttpServletResponse.class);
            when(req.getRequestURI()).thenReturn("/v1/crm/accounts");
            when(req.getHeader(CORRELATION_HEADER)).thenReturn("   ");

            ResponseEntity<ApiError> result = invocation.invoke(req, resp);

            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            verify(resp).setHeader(eq(CORRELATION_HEADER), captor.capture());
            assertThat(captor.getValue()).isNotBlank();
            assertThat(captor.getValue()).isNotEqualTo("   ");
            assertThat(result.getBody()).isNotNull();
            assertThat(result.getBody().correlationId()).isEqualTo(captor.getValue());
        }

        @Test
        @DisplayName("every @ExceptionHandler method on CrmExceptionHandler has a matching MethodSource entry")
        void everyHandlerMethodIsCovered() {
            long handlerMethodCount = Arrays.stream(CrmExceptionHandler.class.getDeclaredMethods())
                    .filter(method -> method.isAnnotationPresent(ExceptionHandler.class))
                    .count();
            long methodSourceEntryCount = handlerInvocations().count();

            assertThat(methodSourceEntryCount)
                    .as("A new @ExceptionHandler method was added to CrmExceptionHandler without a matching entry "
                            + "in XCorrelationIdHeader#handlerInvocations() in CrmExceptionHandlerTest — add one so "
                            + "the X-Correlation-Id header contract stays proven for every handler")
                    .isEqualTo(handlerMethodCount);
        }
    }
}

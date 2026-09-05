package com.positivity.marketing.internal.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.marketing.internal.exception.MarketingDuplicateResourceException;
import com.positivity.marketing.internal.exception.MarketingResourceNotFoundException;
import com.positivity.marketing.internal.exception.MarketingUnprocessableEntityException;
import com.positivity.shared.error.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
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
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;

/**
 * Unit tests for {@link MarketingExceptionHandler} (ADR-0017 error envelope).
 *
 * <p>
 * Two things are worth pinning here. The <b>status and code pairing</b> is the
 * contract clients branch on — a duplicate campaign code has to read as 409
 * with {@code DUPLICATE_RESOURCE}, not as a generic 400 — and the <b>correlation
 * id is echoed onto both the body and the response header</b>, since that id is
 * how a report of "it failed" is turned into a log search. An inbound
 * correlation id is preserved rather than replaced, so a trace started upstream
 * survives the error.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MarketingExceptionHandler — ApiError envelope and correlation")
class MarketingExceptionHandlerTest {

    private static final Instant NOW = Instant.parse("2026-08-11T09:00:00Z");
    private static final String CORRELATION_HEADER = "X-Correlation-Id";

    @Mock
    private HttpServletRequest request;

    @Mock
    private HttpServletResponse response;

    private MarketingExceptionHandler handler;

    @BeforeEach
    void setUp() {
        handler = new MarketingExceptionHandler(Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private void inboundCorrelationId(String value) {
        when(request.getHeader(CORRELATION_HEADER)).thenReturn(value);
    }

    private String capturedCorrelationHeader() {
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(response).setHeader(org.mockito.ArgumentMatchers.eq(CORRELATION_HEADER), captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("maps a missing resource to 404 RESOURCE_NOT_FOUND")
    void notFound() {
        inboundCorrelationId("trace-1");

        ResponseEntity<ApiError> result = handler.handleNotFound(
                new MarketingResourceNotFoundException("Campaign", UUID.randomUUID()), request, response);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(result.getBody()).isNotNull();
        assertThat(result.getBody().code()).isEqualTo("RESOURCE_NOT_FOUND");
        assertThat(result.getBody().status()).isEqualTo(404);
        assertThat(result.getBody().timestamp()).isEqualTo(NOW.toString());
        // An upstream trace must survive the error rather than being replaced.
        assertThat(result.getBody().correlationId()).isEqualTo("trace-1");
        assertThat(capturedCorrelationHeader()).isEqualTo("trace-1");
    }

    @Test
    @DisplayName("maps a duplicate to 409 DUPLICATE_RESOURCE")
    void duplicate() {
        inboundCorrelationId(null);

        ResponseEntity<ApiError> result = handler.handleDuplicate(
                new MarketingDuplicateResourceException("Campaign", "SPRING"), request, response);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(result.getBody()).isNotNull();
        assertThat(result.getBody().code()).isEqualTo("DUPLICATE_RESOURCE");
        // No inbound id: one is minted so the failure is still traceable.
        assertThat(result.getBody().correlationId()).isNotBlank();
        assertThat(capturedCorrelationHeader()).isEqualTo(result.getBody().correlationId());
    }

    @Test
    @DisplayName("maps a rejected state change to 422 UNPROCESSABLE_ENTITY")
    void unprocessable() {
        inboundCorrelationId("  ");

        ResponseEntity<ApiError> result = handler.handleUnprocessable(
                new MarketingUnprocessableEntityException("Campaign has no segment bound"), request, response);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(result.getBody()).isNotNull();
        assertThat(result.getBody().code()).isEqualTo("UNPROCESSABLE_ENTITY");
        assertThat(result.getBody().message()).isEqualTo("Campaign has no segment bound");
        // A blank inbound header is treated as absent, not echoed back as blank.
        assertThat(result.getBody().correlationId()).isNotBlank();
    }

    @Test
    @DisplayName("maps a permission failure to 403 without leaking why")
    void accessDenied() {
        inboundCorrelationId("trace-2");

        ResponseEntity<ApiError> result = handler.handleAccessDenied(
                new AccessDeniedException("missing marketing:campaign:send"), request, response);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(result.getBody()).isNotNull();
        assertThat(result.getBody().code()).isEqualTo("PERMISSION_DENIED");
        // The required authority is a detail of the authorization model; it stays in the log.
        assertThat(result.getBody().message()).doesNotContain("marketing:campaign:send");
    }

    @Test
    @DisplayName("reports every rejected field, defaulting a null message rather than emitting null")
    void bodyValidationListsFieldErrors() throws NoSuchMethodException {
        inboundCorrelationId("trace-4");
        BindingResult binding = new BeanPropertyBindingResult(new Object(), "request");
        binding.addError(new FieldError("request", "name", "must not be blank"));
        binding.addError(new FieldError("request", "channel", null));
        MethodArgumentNotValidException ex = new MethodArgumentNotValidException(
                new org.springframework.core.MethodParameter(
                        MarketingExceptionHandlerTest.class.getDeclaredMethod("bodyValidationListsFieldErrors"), -1),
                binding);

        ResponseEntity<ApiError> result = handler.handleMethodArgumentNotValid(ex, request, response);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(result.getBody()).isNotNull();
        assertThat(result.getBody().code()).isEqualTo("VALIDATION_FAILED");
        assertThat(result.getBody().correlationId()).isEqualTo("trace-4");
        List<ApiError.FieldError> fieldErrors = result.getBody().fieldErrors();
        assertThat(fieldErrors)
                .extracting(ApiError.FieldError::field, ApiError.FieldError::message)
                .containsExactly(
                        org.assertj.core.api.Assertions.tuple("name", "must not be blank"),
                        org.assertj.core.api.Assertions.tuple("channel", "Invalid value"));
    }

    // ---------------------------------------------------------------
    // X-Correlation-Id header (ADR-0017 §4, #1729) — every handler already routes
    // the correlation id onto both the ApiError body and the response header
    // (via the shared `error` helper, or directly for the field-error path); this
    // guards against a future handler forgetting it.
    // ---------------------------------------------------------------

    @Nested
    @DisplayName("X-Correlation-Id header (ADR-0017 §4, #1729)")
    class XCorrelationIdHeader {

        @FunctionalInterface
        interface HandlerInvocation {
            ResponseEntity<ApiError> invoke(HttpServletRequest request, HttpServletResponse response);
        }

        /** One entry per {@code @ExceptionHandler} method on {@link MarketingExceptionHandler}. */
        private static Stream<Named<HandlerInvocation>> handlerInvocations() {
            MarketingExceptionHandler h = new MarketingExceptionHandler(Clock.fixed(NOW, ZoneOffset.UTC));

            return Stream.of(
                    Named.of("handleAccessDenied", (HandlerInvocation) (request, response) -> h.handleAccessDenied(
                            new AccessDeniedException("missing marketing:campaign:send"), request, response)),
                    Named.of("handleNotFound", (HandlerInvocation) (request, response) -> h.handleNotFound(
                            new MarketingResourceNotFoundException("Campaign", UUID.randomUUID()), request, response)),
                    Named.of("handleDuplicate", (HandlerInvocation) (request, response) -> h.handleDuplicate(
                            new MarketingDuplicateResourceException("Campaign", "SPRING"), request, response)),
                    Named.of("handleUnprocessable", (HandlerInvocation) (request, response) -> h.handleUnprocessable(
                            new MarketingUnprocessableEntityException("Campaign has no segment bound"),
                            request,
                            response)),
                    Named.of("handleMethodArgumentNotValid", (HandlerInvocation) (request, response) -> {
                        BindingResult binding = new BeanPropertyBindingResult(new Object(), "request");
                        binding.addError(new FieldError("request", "name", "must not be blank"));
                        MethodArgumentNotValidException ex;
                        try {
                            ex = new MethodArgumentNotValidException(
                                    new MethodParameter(
                                            MarketingExceptionHandlerTest.class.getDeclaredMethod(
                                                    "bodyValidationListsFieldErrors"),
                                            -1),
                                    binding);
                        } catch (NoSuchMethodException e) {
                            throw new IllegalStateException(e);
                        }
                        return h.handleMethodArgumentNotValid(ex, request, response);
                    }));
        }

        @ParameterizedTest
        @MethodSource("handlerInvocations")
        @DisplayName("echoes the inbound X-Correlation-Id in both header and body")
        void echoesInboundCorrelationId(HandlerInvocation invocation) {
            HttpServletRequest request = mock(HttpServletRequest.class);
            HttpServletResponse response = mock(HttpServletResponse.class);
            when(request.getHeader(CORRELATION_HEADER)).thenReturn("corr-from-caller");

            ResponseEntity<ApiError> result = invocation.invoke(request, response);

            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            verify(response).setHeader(eq(CORRELATION_HEADER), captor.capture());
            assertThat(captor.getValue()).isEqualTo("corr-from-caller");
            assertThat(result.getBody()).isNotNull();
            assertThat(captor.getValue()).isEqualTo(result.getBody().correlationId());
        }

        @ParameterizedTest
        @MethodSource("handlerInvocations")
        @DisplayName("generates a non-blank X-Correlation-Id, consistent between header and body, when absent")
        void generatesCorrelationIdWhenAbsent(HandlerInvocation invocation) {
            HttpServletRequest request = mock(HttpServletRequest.class);
            HttpServletResponse response = mock(HttpServletResponse.class);
            when(request.getHeader(CORRELATION_HEADER)).thenReturn(null);

            ResponseEntity<ApiError> result = invocation.invoke(request, response);

            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            verify(response).setHeader(eq(CORRELATION_HEADER), captor.capture());
            assertThat(captor.getValue()).isNotBlank();
            assertThat(result.getBody()).isNotNull();
            assertThat(captor.getValue()).isEqualTo(result.getBody().correlationId());
        }

        @Test
        @DisplayName("every @ExceptionHandler method on MarketingExceptionHandler has a matching MethodSource entry")
        void everyHandlerMethodIsCovered() {
            long handlerMethodCount = Arrays.stream(MarketingExceptionHandler.class.getDeclaredMethods())
                    .filter((Method method) -> method.isAnnotationPresent(ExceptionHandler.class))
                    .count();
            long methodSourceEntryCount = handlerInvocations().count();

            assertThat(methodSourceEntryCount)
                    .as("A new @ExceptionHandler method was added to MarketingExceptionHandler without a matching "
                            + "entry in XCorrelationIdHeader#handlerInvocations() in MarketingExceptionHandlerTest — "
                            + "add one so the X-Correlation-Id header contract stays proven for every handler")
                    .isEqualTo(handlerMethodCount);
        }
    }
}

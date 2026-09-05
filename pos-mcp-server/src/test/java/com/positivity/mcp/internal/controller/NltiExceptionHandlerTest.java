package com.positivity.mcp.internal.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.positivity.mcp.internal.dto.NltiRequestDTO;
import com.positivity.mcp.internal.exception.InvalidDocumentMetadataException;
import com.positivity.mcp.internal.exception.RateLimitExceededException;
import com.positivity.mcp.internal.exception.SessionOwnershipViolationException;
import com.positivity.mcp.internal.exception.WritePlanConflictException;
import com.positivity.mcp.internal.exception.WritePlanExecutionException;
import com.positivity.mcp.internal.exception.WritePlanExpiredException;
import com.positivity.mcp.internal.exception.WritePlanNotFoundException;
import com.positivity.mcp.internal.exception.WritePlanStaleException;
import com.positivity.mcp.internal.service.NltiRequestService;
import com.positivity.mcp.internal.service.NltiWorkflowStateService;
import com.positivity.shared.error.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Tests for {@link NltiExceptionHandler} exception mapping paths.
 *
 * <p>
 * Uses a {@code @WebMvcTest} slice with the service mocked to throw specific
 * exceptions, verifying that {@link NltiExceptionHandler} maps each exception
 * to the correct HTTP status code and error body.
 *
 * Issue: NLTI-001
 */
@WebMvcTest(NltiController.class)
@ActiveProfiles("test")
class NltiExceptionHandlerTest {
    private static final UUID INBOUND_CORRELATION_ID = UUID.fromString("00000000-0000-7000-8000-000000000120");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private NltiRequestService nltiRequestService;

    @MockitoBean
    private NltiWorkflowStateService workflowStateService;

    @MockitoBean
    private com.positivity.mcp.internal.service.NltiWritePlanService writePlanService;

    private String validRequestBody() throws Exception {
        return objectMapper.writeValueAsString(new NltiRequestDTO("test prompt for handler", null, null));
    }

    // ─── RateLimitExceededException → 429 TOO_MANY_REQUESTS ─────────────────

    @Test
    @WithMockUser(authorities = "nlti:request:submit")
    @DisplayName("RateLimitExceededException from service → 429 with RATE_LIMIT_EXCEEDED code")
    void submitRequest_whenRateLimitExceeded_returns429WithRateLimitExceededCode() throws Exception {
        when(nltiRequestService.submit(any(), any())).thenThrow(new RateLimitExceededException("Rate limit exceeded"));

        mockMvc.perform(post("/v1/nlt/requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequestBody())
                        .header(NltiCorrelationIdSupport.CORRELATION_ID_HEADER, INBOUND_CORRELATION_ID.toString()))
                .andExpect(status().is(HttpStatus.TOO_MANY_REQUESTS.value()))
                .andExpect(jsonPath("$.status").isNumber())
                .andExpect(jsonPath("$.code").value("RATE_LIMIT_EXCEEDED"))
                .andExpect(jsonPath("$.message").value("Rate limit exceeded"))
                .andExpect(jsonPath("$.correlationId").value(INBOUND_CORRELATION_ID.toString()))
                .andExpect(result -> assertThat(
                                result.getResponse().getHeader(NltiCorrelationIdSupport.CORRELATION_ID_HEADER))
                        .isEqualTo(INBOUND_CORRELATION_ID.toString()));
    }

    @Test
    @WithMockUser(authorities = "nlti:request:submit")
    @DisplayName("invalid inbound X-Correlation-Id + service error → response uses controller-resolved UUID")
    void submitRequest_whenCorrelationHeaderInvalidAndServiceThrows_usesControllerResolvedCorrelationId()
            throws Exception {
        AtomicReference<UUID> capturedCorrelationId = new AtomicReference<>();
        when(nltiRequestService.submit(any(), any())).thenAnswer(invocation -> {
            capturedCorrelationId.set(invocation.getArgument(1, UUID.class));
            throw new RateLimitExceededException("Rate limit exceeded");
        });

        mockMvc.perform(post("/v1/nlt/requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequestBody())
                        .header(NltiCorrelationIdSupport.CORRELATION_ID_HEADER, "not-a-uuid"))
                .andExpect(status().is(HttpStatus.TOO_MANY_REQUESTS.value()))
                .andExpect(jsonPath("$.status").isNumber())
                .andExpect(jsonPath("$.code").value("RATE_LIMIT_EXCEEDED"))
                .andExpect(jsonPath("$.message").value("Rate limit exceeded"))
                .andExpect(result -> {
                    String responseBody = result.getResponse().getContentAsString();
                    String responseCorrelationId = objectMapper
                            .readTree(responseBody)
                            .path("correlationId")
                            .asText();
                    assertThat(capturedCorrelationId.get()).isNotNull();
                    assertThat(responseCorrelationId)
                            .isEqualTo(capturedCorrelationId.get().toString());
                    assertThat(result.getResponse().getHeader(NltiCorrelationIdSupport.CORRELATION_ID_HEADER))
                            .isEqualTo(capturedCorrelationId.get().toString());
                });
    }

    @Test
    @WithMockUser(authorities = "nlti:request:submit")
    @DisplayName("SessionOwnershipViolationException from service → 403 with SESSION_ACCESS_DENIED code")
    void submitRequest_whenSessionOwnershipViolation_returns403WithSessionAccessDeniedCode() throws Exception {
        when(nltiRequestService.submit(any(), any()))
                .thenThrow(new SessionOwnershipViolationException("Provided sessionId is not owned by subject"));

        mockMvc.perform(post("/v1/nlt/requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequestBody()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").isNumber())
                .andExpect(jsonPath("$.code").value("SESSION_ACCESS_DENIED"))
                .andExpect(jsonPath("$.correlationId").isNotEmpty());
    }

    // ─── UnsupportedOperationException → 501 NOT_IMPLEMENTED ─────────────────

    @Test
    @WithMockUser(authorities = "nlti:request:submit")
    @DisplayName("UnsupportedOperationException from service → 501 with NOT_IMPLEMENTED code")
    void submitRequest_whenUnsupportedOperation_returns501WithNotImplementedCode() throws Exception {
        when(nltiRequestService.submit(any(), any()))
                .thenThrow(new UnsupportedOperationException("feature not yet implemented"));

        mockMvc.perform(post("/v1/nlt/requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequestBody()))
                .andExpect(status().is(HttpStatus.NOT_IMPLEMENTED.value()))
                .andExpect(jsonPath("$.status").isNumber())
                .andExpect(jsonPath("$.code").value("NOT_IMPLEMENTED"))
                .andExpect(jsonPath("$.correlationId").isNotEmpty());
    }

    // ─── ConstraintViolationException → 400 BAD_REQUEST ─────────────────────

    @Test
    @WithMockUser(authorities = "nlti:request:submit")
    @DisplayName("ConstraintViolationException from service → 400 with VALIDATION_ERROR code")
    void submitRequest_whenConstraintViolation_returns400WithValidationErrorCode() throws Exception {
        when(nltiRequestService.submit(any(), any()))
                .thenThrow(new ConstraintViolationException("field must not be blank", Set.of()));

        mockMvc.perform(post("/v1/nlt/requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequestBody()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").isNumber())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.correlationId").isNotEmpty());
    }

    // ─── Test slice configuration ─────────────────────────────────────────────

    @TestConfiguration
    @EnableMethodSecurity(prePostEnabled = true)
    static class SliceTestConfig {

        @Bean
        SecurityExceptionHandlerAdvice securityExceptionHandlerAdvice() {
            return new SecurityExceptionHandlerAdvice();
        }
    }

    @ControllerAdvice
    static class SecurityExceptionHandlerAdvice {

        @ExceptionHandler(AccessDeniedException.class)
        @ResponseStatus(HttpStatus.FORBIDDEN)
        void handleAccessDenied() {
            // Intentionally empty: status mapping is asserted by MVC tests.
        }

        @ExceptionHandler(AuthenticationException.class)
        @ResponseStatus(HttpStatus.UNAUTHORIZED)
        void handleAuthenticationException() {
            // Intentionally empty: status mapping is asserted by MVC tests.
        }
    }

    // ---------------------------------------------------------------
    // X-Correlation-Id header (ADR-0017 §4, #1729) — proves every
    // @ExceptionHandler method on NltiExceptionHandler sets the correlation id
    // in both the response header and the ApiError body, and guards against a
    // future handler forgetting it.
    // ---------------------------------------------------------------

    @Nested
    @DisplayName("X-Correlation-Id header (ADR-0017 §4, #1729)")
    class XCorrelationIdHeader {

        @FunctionalInterface
        interface HandlerInvocation {
            ResponseEntity<ApiError> invoke(HttpServletRequest request);
        }

        /**
         * One entry per {@code @ExceptionHandler} method on {@link NltiExceptionHandler}. Uses a
         * standalone handler instance (not the outer test's MockMvc slice) so this factory method
         * can stay static, as required by {@code @MethodSource} outside a {@code PER_CLASS} test
         * instance lifecycle.
         */
        private static Stream<Named<HandlerInvocation>> handlerInvocations() {
            Clock fixedClock = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC);
            @SuppressWarnings("unchecked")
            ObjectProvider<Clock> clockProvider = mock(ObjectProvider.class);
            when(clockProvider.getIfAvailable(any())).thenReturn(fixedClock);
            NltiExceptionHandler handler = new NltiExceptionHandler(clockProvider);

            MethodArgumentNotValidException validationEx = mock(MethodArgumentNotValidException.class);
            BindingResult bindingResult = mock(BindingResult.class);
            when(bindingResult.getFieldErrors()).thenReturn(List.of());
            when(validationEx.getBindingResult()).thenReturn(bindingResult);

            return Stream.of(
                    Named.of("handleValidation", (HandlerInvocation)
                            request -> handler.handleValidation(validationEx, request)),
                    Named.of("handleConstraintViolation", (HandlerInvocation)
                            request -> handler.handleConstraintViolation(
                                    new ConstraintViolationException("bad param", Set.of()), request)),
                    Named.of("handleRateLimit", (HandlerInvocation) request ->
                            handler.handleRateLimit(new RateLimitExceededException("Rate limit exceeded"), request)),
                    Named.of("handleInvalidDocumentMetadata", (HandlerInvocation)
                            request -> handler.handleInvalidDocumentMetadata(
                                    new InvalidDocumentMetadataException("bad metadata", null), request)),
                    Named.of("handleSessionOwnershipViolation", (HandlerInvocation)
                            request -> handler.handleSessionOwnershipViolation(
                                    new SessionOwnershipViolationException("not owned"), request)),
                    Named.of("handleWritePlanNotFound", (HandlerInvocation) request ->
                            handler.handleWritePlanNotFound(new WritePlanNotFoundException("not found"), request)),
                    Named.of("handleWritePlanConflict", (HandlerInvocation) request ->
                            handler.handleWritePlanConflict(new WritePlanConflictException("conflict"), request)),
                    Named.of("handleWritePlanExpired", (HandlerInvocation) request ->
                            handler.handleWritePlanExpired(new WritePlanExpiredException("expired"), request)),
                    Named.of("handleWritePlanStale", (HandlerInvocation)
                            request -> handler.handleWritePlanStale(new WritePlanStaleException("stale"), request)),
                    Named.of(
                            "handleWritePlanExecution", (HandlerInvocation) request -> handler.handleWritePlanExecution(
                                    new WritePlanExecutionException("execution failed"), request)),
                    Named.of("handleUnsupported", (HandlerInvocation) request ->
                            handler.handleUnsupported(new UnsupportedOperationException("not implemented"), request)),
                    Named.of("handleAuthentication", (HandlerInvocation)
                            request -> handler.handleAuthentication(mock(AuthenticationException.class), request)),
                    Named.of("handleAccessDenied", (HandlerInvocation)
                            request -> handler.handleAccessDenied(new AccessDeniedException("denied"), request)));
        }

        @ParameterizedTest
        @MethodSource("handlerInvocations")
        @DisplayName("echoes the inbound X-Correlation-Id in both header and body")
        void echoesInboundCorrelationId(HandlerInvocation invocation) {
            MockHttpServletRequest request = new MockHttpServletRequest();
            UUID inbound = UUID.fromString("00000000-0000-7000-8000-000000000121");
            request.addHeader(NltiCorrelationIdSupport.CORRELATION_ID_HEADER, inbound.toString());

            ResponseEntity<ApiError> response = invocation.invoke(request);

            assertThat(response.getHeaders().getFirst(NltiCorrelationIdSupport.CORRELATION_ID_HEADER))
                    .isEqualTo(inbound.toString());
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getHeaders().getFirst(NltiCorrelationIdSupport.CORRELATION_ID_HEADER))
                    .isEqualTo(response.getBody().correlationId());
        }

        @ParameterizedTest
        @MethodSource("handlerInvocations")
        @DisplayName("generates a non-blank X-Correlation-Id, consistent between header and body, when absent")
        void generatesCorrelationIdWhenAbsent(HandlerInvocation invocation) {
            MockHttpServletRequest request = new MockHttpServletRequest();

            ResponseEntity<ApiError> response = invocation.invoke(request);

            String header = response.getHeaders().getFirst(NltiCorrelationIdSupport.CORRELATION_ID_HEADER);
            assertThat(header).isNotBlank();
            assertThat(response.getBody()).isNotNull();
            assertThat(header).isEqualTo(response.getBody().correlationId());
        }

        @Test
        @DisplayName("every @ExceptionHandler method on NltiExceptionHandler has a matching MethodSource entry")
        void everyHandlerMethodIsCovered() {
            long handlerMethodCount = Arrays.stream(NltiExceptionHandler.class.getDeclaredMethods())
                    .filter(method -> method.isAnnotationPresent(ExceptionHandler.class))
                    .count();
            long methodSourceEntryCount = handlerInvocations().count();

            assertThat(methodSourceEntryCount)
                    .as("A new @ExceptionHandler method was added to NltiExceptionHandler without a matching "
                            + "entry in XCorrelationIdHeader#handlerInvocations() in NltiExceptionHandlerTest — "
                            + "add one so the X-Correlation-Id header contract stays proven for every handler")
                    .isEqualTo(handlerMethodCount);
        }
    }
}

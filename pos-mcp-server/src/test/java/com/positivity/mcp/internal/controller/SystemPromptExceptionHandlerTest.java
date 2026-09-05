package com.positivity.mcp.internal.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.positivity.mcp.internal.exception.SystemPromptNameConflictException;
import com.positivity.shared.error.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;

/**
 * Unit tests for {@link SystemPromptExceptionHandler}. See {@link
 * SystemPromptExceptionHandlerErrorHandlingTest} for the MockMvc end-to-end coverage of the
 * per-exception status/body contracts (issue #1694).
 */
class SystemPromptExceptionHandlerTest {

    // ---------------------------------------------------------------
    // X-Correlation-Id header (ADR-0017 §4, #1729) — proves every
    // @ExceptionHandler method on SystemPromptExceptionHandler sets the
    // correlation id in both the response header and the ApiError body. Every
    // handler already builds its response inline with the header set, so this
    // advice needed no refactor -- this nested class only proves and guards
    // that existing compliance.
    // ---------------------------------------------------------------

    @Nested
    @DisplayName("X-Correlation-Id header (ADR-0017 §4, #1729)")
    class XCorrelationIdHeader {

        @FunctionalInterface
        interface HandlerInvocation {
            ResponseEntity<ApiError> invoke(HttpServletRequest request);
        }

        /** One entry per {@code @ExceptionHandler} method on {@link SystemPromptExceptionHandler}. */
        private static Stream<Named<HandlerInvocation>> handlerInvocations() {
            Clock fixedClock = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC);
            @SuppressWarnings("unchecked")
            ObjectProvider<Clock> clockProvider = mock(ObjectProvider.class);
            when(clockProvider.getIfAvailable(any())).thenReturn(fixedClock);
            SystemPromptExceptionHandler handler = new SystemPromptExceptionHandler(clockProvider);

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
                    Named.of("handleNameConflict", (HandlerInvocation) request -> handler.handleNameConflict(
                            new SystemPromptNameConflictException("Prompt name already exists: default"), request)),
                    Named.of("handleNotFound", (HandlerInvocation)
                            request -> handler.handleNotFound(new NoSuchElementException("Prompt not found"), request)),
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
            UUID inbound = UUID.fromString("00000000-0000-7000-8000-000000000131");
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

        @ParameterizedTest
        @MethodSource("handlerInvocations")
        @DisplayName("generates a fresh X-Correlation-Id when the inbound header is blank")
        void generatesCorrelationIdWhenInboundIsBlank(HandlerInvocation invocation) {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.addHeader(NltiCorrelationIdSupport.CORRELATION_ID_HEADER, "   ");

            ResponseEntity<ApiError> response = invocation.invoke(request);

            String header = response.getHeaders().getFirst(NltiCorrelationIdSupport.CORRELATION_ID_HEADER);
            assertThat(header).isNotBlank();
            assertThat(header).isNotEqualTo("   ");
            assertThat(response.getBody()).isNotNull();
            assertThat(header).isEqualTo(response.getBody().correlationId());
        }

        @Test
        @DisplayName("every @ExceptionHandler method on SystemPromptExceptionHandler has a matching MethodSource entry")
        void everyHandlerMethodIsCovered() {
            long handlerMethodCount = Arrays.stream(SystemPromptExceptionHandler.class.getDeclaredMethods())
                    .filter(method -> method.isAnnotationPresent(ExceptionHandler.class))
                    .count();
            long methodSourceEntryCount = handlerInvocations().count();

            assertThat(methodSourceEntryCount)
                    .as("A new @ExceptionHandler method was added to SystemPromptExceptionHandler without a "
                            + "matching entry in XCorrelationIdHeader#handlerInvocations() in "
                            + "SystemPromptExceptionHandlerTest — add one so the X-Correlation-Id header "
                            + "contract stays proven for every handler")
                    .isEqualTo(handlerMethodCount);
        }
    }
}

package com.positivity.vehicle.internal.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.positivity.shared.error.ApiError;
import com.positivity.vehicle.internal.exception.VehicleValidationException;
import com.positivity.vehicle.internal.exception.VehicleVinConflictException;
import jakarta.persistence.EntityNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;

/** Unit tests for {@link VehicleExceptionHandler}. */
class VehicleExceptionHandlerTest {

    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC);
    private static final String CORRELATION_ID = "test-correlation-id";

    private final VehicleExceptionHandler sut = new VehicleExceptionHandler(fixedClockProvider());

    private static ObjectProvider<Clock> fixedClockProvider() {
        @SuppressWarnings("unchecked")
        ObjectProvider<Clock> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable(org.mockito.ArgumentMatchers.any())).thenReturn(TEST_CLOCK);
        return provider;
    }

    private HttpServletRequest requestWithHeader() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("X-Correlation-Id")).thenReturn(CORRELATION_ID);
        when(request.getRequestURI()).thenReturn("/v1/vehicles");
        return request;
    }

    private HttpServletRequest requestWithoutHeader() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("X-Correlation-Id")).thenReturn(null);
        when(request.getRequestURI()).thenReturn("/v1/vehicles");
        return request;
    }

    private HttpServletRequest requestWithBlankHeader() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("X-Correlation-Id")).thenReturn("   ");
        when(request.getRequestURI()).thenReturn("/v1/vehicles");
        return request;
    }

    private static MethodArgumentNotValidException methodArgumentNotValidException() {
        MethodArgumentNotValidException ex = mock(MethodArgumentNotValidException.class);
        BindingResult bindingResult = mock(BindingResult.class);
        when(bindingResult.getFieldErrors()).thenReturn(List.of());
        when(ex.getBindingResult()).thenReturn(bindingResult);
        when(ex.getMessage()).thenReturn("Validation failed");
        return ex;
    }

    @Nested
    @DisplayName("handleConstraintViolation")
    class HandleConstraintViolation {

        @Test
        @DisplayName("returns 400 VALIDATION_FAILED and sets the correlation id header")
        void returns400AndSetsHeader() {
            ConstraintViolationException ex = new ConstraintViolationException("bad vin", Collections.emptySet());
            MockHttpServletResponse response = new MockHttpServletResponse();

            ResponseEntity<ApiError> result = sut.handleConstraintViolation(ex, requestWithHeader(), response);

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(result.getBody()).isNotNull();
            assertThat(result.getBody().code()).isEqualTo("VALIDATION_FAILED");
            assertThat(response.getHeader("X-Correlation-Id")).isEqualTo(CORRELATION_ID);
        }
    }

    @Nested
    @DisplayName("handleMethodArgumentNotValid")
    class HandleMethodArgumentNotValid {

        @Test
        @DisplayName("returns 400 VALIDATION_FAILED and sets the correlation id header")
        void returns400AndSetsHeader() {
            MockHttpServletResponse response = new MockHttpServletResponse();

            ResponseEntity<ApiError> result =
                    sut.handleMethodArgumentNotValid(methodArgumentNotValidException(), requestWithHeader(), response);

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(result.getBody()).isNotNull();
            assertThat(result.getBody().code()).isEqualTo("VALIDATION_FAILED");
            assertThat(response.getHeader("X-Correlation-Id")).isEqualTo(CORRELATION_ID);
        }
    }

    @Nested
    @DisplayName("handleEntityNotFound")
    class HandleEntityNotFound {

        @Test
        @DisplayName("returns 404 RESOURCE_NOT_FOUND and sets the correlation id header")
        void returns404AndSetsHeader() {
            EntityNotFoundException ex = new EntityNotFoundException("Vehicle not found");
            MockHttpServletResponse response = new MockHttpServletResponse();

            ResponseEntity<ApiError> result = sut.handleEntityNotFound(ex, requestWithHeader(), response);

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(result.getBody()).isNotNull();
            assertThat(result.getBody().code()).isEqualTo("RESOURCE_NOT_FOUND");
            assertThat(response.getHeader("X-Correlation-Id")).isEqualTo(CORRELATION_ID);
        }
    }

    @Nested
    @DisplayName("handleValidation")
    class HandleValidation {

        @Test
        @DisplayName("returns 400 VALIDATION_ERROR and sets the correlation id header")
        void returns400AndSetsHeader() {
            VehicleValidationException ex = new VehicleValidationException("Model year out of range");
            MockHttpServletResponse response = new MockHttpServletResponse();

            ResponseEntity<ApiError> result = sut.handleValidation(ex, requestWithHeader(), response);

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(result.getBody()).isNotNull();
            assertThat(result.getBody().code()).isEqualTo("VALIDATION_ERROR");
            assertThat(response.getHeader("X-Correlation-Id")).isEqualTo(CORRELATION_ID);
        }
    }

    @Nested
    @DisplayName("handleVinConflict")
    class HandleVinConflict {

        @Test
        @DisplayName("returns 409 VEHICLE_VIN_CONFLICT and sets the correlation id header")
        void returns409AndSetsHeader() {
            VehicleVinConflictException ex = new VehicleVinConflictException("VIN already registered");
            MockHttpServletResponse response = new MockHttpServletResponse();

            ResponseEntity<ApiError> result = sut.handleVinConflict(ex, requestWithHeader(), response);

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(result.getBody()).isNotNull();
            assertThat(result.getBody().code()).isEqualTo("VEHICLE_VIN_CONFLICT");
            assertThat(response.getHeader("X-Correlation-Id")).isEqualTo(CORRELATION_ID);
        }
    }

    // ---------------------------------------------------------------
    // X-Correlation-Id header (ADR-0017 §4, #1729) — VehicleExceptionHandler was already fully
    // compliant: every handler already resolves the correlation id and calls
    // response.setHeader(X-Correlation-Id, ...) as well as putting it in the ApiError body. This
    // nested class proves the contract holds for every handler and guards against a future
    // handler forgetting to set the header.
    // ---------------------------------------------------------------

    @Nested
    @DisplayName("X-Correlation-Id header (ADR-0017 §4, #1729)")
    class XCorrelationIdHeader {

        @FunctionalInterface
        interface HandlerInvocation {
            ResponseEntity<ApiError> invoke(HttpServletRequest request, MockHttpServletResponse response);
        }

        private static Stream<Named<HandlerInvocation>> handlerInvocations() {
            VehicleExceptionHandler handler = new VehicleExceptionHandler(fixedClockProvider());

            return Stream.of(
                    Named.of("handleConstraintViolation", (HandlerInvocation)
                            (request, response) -> handler.handleConstraintViolation(
                                    new ConstraintViolationException("bad vin", Collections.emptySet()),
                                    request,
                                    response)),
                    Named.of("handleMethodArgumentNotValid", (HandlerInvocation) (request, response) ->
                            handler.handleMethodArgumentNotValid(methodArgumentNotValidException(), request, response)),
                    Named.of("handleEntityNotFound", (HandlerInvocation)
                            (request, response) -> handler.handleEntityNotFound(
                                    new EntityNotFoundException("Vehicle not found"), request, response)),
                    Named.of("handleValidation", (HandlerInvocation) (request, response) -> handler.handleValidation(
                            new VehicleValidationException("Model year out of range"), request, response)),
                    Named.of("handleVinConflict", (HandlerInvocation) (request, response) -> handler.handleVinConflict(
                            new VehicleVinConflictException("VIN already registered"), request, response)));
        }

        @ParameterizedTest
        @MethodSource("handlerInvocations")
        @DisplayName("echoes the inbound X-Correlation-Id in both response header and body")
        void echoesInboundCorrelationId(HandlerInvocation invocation) {
            MockHttpServletResponse response = new MockHttpServletResponse();

            ResponseEntity<ApiError> result = invocation.invoke(requestWithHeader(), response);

            assertThat(response.getHeader("X-Correlation-Id")).isEqualTo(CORRELATION_ID);
            assertThat(result.getBody()).isNotNull();
            assertThat(response.getHeader("X-Correlation-Id"))
                    .isEqualTo(result.getBody().correlationId());
        }

        @ParameterizedTest
        @MethodSource("handlerInvocations")
        @DisplayName("generates a non-blank X-Correlation-Id, consistent between header and body, when absent")
        void generatesCorrelationIdWhenAbsent(HandlerInvocation invocation) {
            MockHttpServletResponse response = new MockHttpServletResponse();

            ResponseEntity<ApiError> result = invocation.invoke(requestWithoutHeader(), response);

            String header = response.getHeader("X-Correlation-Id");
            assertThat(header).isNotBlank();
            assertThat(result.getBody()).isNotNull();
            assertThat(header).isEqualTo(result.getBody().correlationId());
        }

        @ParameterizedTest
        @MethodSource("handlerInvocations")
        @DisplayName("generates a non-blank X-Correlation-Id when blank")
        void generatesCorrelationIdWhenBlank(HandlerInvocation invocation) {
            MockHttpServletResponse response = new MockHttpServletResponse();

            ResponseEntity<ApiError> result = invocation.invoke(requestWithBlankHeader(), response);

            String header = response.getHeader("X-Correlation-Id");
            assertThat(header).isNotBlank();
            assertThat(header).isNotEqualTo("   ");
        }

        @Test
        @DisplayName("every @ExceptionHandler method on VehicleExceptionHandler has a matching MethodSource entry")
        void everyHandlerMethodIsCovered() {
            long handlerMethodCount = Arrays.stream(VehicleExceptionHandler.class.getDeclaredMethods())
                    .filter(method -> method.isAnnotationPresent(ExceptionHandler.class))
                    .count();
            long methodSourceEntryCount = handlerInvocations().count();

            assertThat(methodSourceEntryCount)
                    .as("A new @ExceptionHandler method was added to VehicleExceptionHandler without a matching "
                            + "entry in XCorrelationIdHeader#handlerInvocations() in VehicleExceptionHandlerTest — "
                            + "add one so the X-Correlation-Id header contract stays proven for every handler")
                    .isEqualTo(handlerMethodCount);
        }
    }
}

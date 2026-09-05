package com.positivity.workorder.internal.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.positivity.shared.error.ApiError;
import com.positivity.workorder.internal.exception.ApprovalConfigurationNotFoundException;
import com.positivity.workorder.internal.exception.BreakSegmentNotFoundException;
import com.positivity.workorder.internal.exception.ChangeRequestNotFoundException;
import com.positivity.workorder.internal.exception.CustomerApprovalInvalidException;
import com.positivity.workorder.internal.exception.CustomerRequirementsNotMetException;
import com.positivity.workorder.internal.exception.DuplicateSubstituteLinkException;
import com.positivity.workorder.internal.exception.EstimateItemNotFoundException;
import com.positivity.workorder.internal.exception.EstimateNotFoundException;
import com.positivity.workorder.internal.exception.FractionalQuantityNotAllowedException;
import com.positivity.workorder.internal.exception.InsufficientPartAvailabilityException;
import com.positivity.workorder.internal.exception.LaborEntryNotFoundException;
import com.positivity.workorder.internal.exception.PartLineNotFoundException;
import com.positivity.workorder.internal.exception.PromotionIdempotencyInconsistencyException;
import com.positivity.workorder.internal.exception.PromotionValidationException;
import com.positivity.workorder.internal.exception.PromotionValidationException.PromotionErrorCode;
import com.positivity.workorder.internal.exception.PurchaseOrderRequiredException;
import com.positivity.workorder.internal.exception.ServiceLineNotFoundException;
import com.positivity.workorder.internal.exception.StaleSubstituteLinkVersionException;
import com.positivity.workorder.internal.exception.SubstituteLinkNotFoundException;
import com.positivity.workorder.internal.exception.TravelSegmentConflictException;
import com.positivity.workorder.internal.exception.TravelSegmentNotFoundException;
import com.positivity.workorder.internal.exception.UomConversionUndefinedException;
import com.positivity.workorder.internal.exception.WorkSessionNotFoundException;
import com.positivity.workorder.internal.exception.WorkSessionOverlapException;
import com.positivity.workorder.internal.exception.WorkorderNotFoundException;
import com.positivity.workorder.internal.exception.WorkorderRequestValidationException;
import com.positivity.workorder.internal.exception.WorkorderResourceConflictException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
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
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;

/**
 * Unit tests for {@link GlobalExceptionHandler}'s {@code X-Correlation-Id} contract (ADR-0017 §4,
 * issue #1729). Per-handler business-outcome coverage (status codes, error codes, envelope
 * fields) lives in {@link PromotionErrorEnvelopeTest} and the module's controller/contract tests;
 * this class exists solely to prove — and guard — that every {@code @ExceptionHandler} method on
 * this advice carries the correlation id in both the {@link ApiError} body and the {@code
 * X-Correlation-Id} response header.
 */
class GlobalExceptionHandlerTest {

    private static final String CORRELATION_ID = "018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f7a01";
    private static final UUID SOME_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f7a02");
    private static final UUID OTHER_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f7a03");

    // ---------------------------------------------------------------
    // X-Correlation-Id header (ADR-0017 §4, #1729) — proves that every @ExceptionHandler
    // method on GlobalExceptionHandler carries the correlation id in both the body and the
    // header, and guards against a future handler forgetting it.
    // ---------------------------------------------------------------

    @Nested
    @DisplayName("X-Correlation-Id header (ADR-0017 §4, #1729)")
    class XCorrelationIdHeader {

        @FunctionalInterface
        interface HandlerInvocation {
            ResponseEntity<ApiError> invoke(HttpServletRequest request);
        }

        private static HttpServletRequest requestWithHeader() {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.addHeader("X-Correlation-Id", CORRELATION_ID);
            return request;
        }

        private static HttpServletRequest requestWithoutHeader() {
            return new MockHttpServletRequest();
        }

        private static HttpServletRequest requestWithBlankHeader() {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.addHeader("X-Correlation-Id", "   ");
            return request;
        }

        private static ObjectProvider<Clock> fixedClockProvider() {
            Clock fixedClock = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC);
            return new ObjectProvider<>() {
                @Override
                public Clock getObject() {
                    return fixedClock;
                }

                @Override
                public Clock getObject(Object... args) {
                    return fixedClock;
                }

                @Override
                public Clock getIfAvailable() {
                    return fixedClock;
                }

                @Override
                public Clock getIfUnique() {
                    return fixedClock;
                }
            };
        }

        /**
         * One entry per {@code @ExceptionHandler} method on {@link GlobalExceptionHandler}. Uses
         * a standalone handler instance so this factory method can stay static, as required by
         * {@code @MethodSource} outside a {@code PER_CLASS} test instance lifecycle.
         */
        private static Stream<Named<HandlerInvocation>> handlerInvocations() {
            GlobalExceptionHandler handler = new GlobalExceptionHandler(fixedClockProvider());

            return Stream.of(
                    Named.of("handleWorkorderNotFound", (HandlerInvocation) request ->
                            handler.handleWorkorderNotFound(new WorkorderNotFoundException(SOME_ID), request)),
                    Named.of("handleInsufficientPartAvailability", (HandlerInvocation)
                            request -> handler.handleInsufficientPartAvailability(
                                    new InsufficientPartAvailabilityException(
                                            SOME_ID, "Widget", BigDecimal.TEN, BigDecimal.ONE),
                                    request)),
                    Named.of("handleFractionalQuantityNotAllowed", (HandlerInvocation)
                            request -> handler.handleFractionalQuantityNotAllowed(
                                    FractionalQuantityNotAllowedException.wholeUnitsOnly(
                                            "Widget", new BigDecimal("0.5"), BigDecimal.ONE),
                                    request)),
                    Named.of("handleUomConversionUndefined", (HandlerInvocation)
                            request -> handler.handleUomConversionUndefined(
                                    UomConversionUndefinedException.noConversionRow(SOME_ID, "EA"), request)),
                    Named.of("handleEstimateNotFound", (HandlerInvocation)
                            request -> handler.handleEstimateNotFound(new EstimateNotFoundException(SOME_ID), request)),
                    Named.of("handleCustomerRequirementsNotMet", (HandlerInvocation)
                            request -> handler.handleCustomerRequirementsNotMet(
                                    CustomerRequirementsNotMetException.requirementsNotMet(SOME_ID), request)),
                    Named.of("handleCustomerApprovalInvalid", (HandlerInvocation)
                            request -> handler.handleCustomerApprovalInvalid(
                                    new CustomerApprovalInvalidException("no approval", SOME_ID), request)),
                    Named.of("handlePromotionValidation", (HandlerInvocation)
                            request -> handler.handlePromotionValidation(
                                    new PromotionValidationException(
                                            PromotionErrorCode.APPROVAL_EXPIRED, "approval expired"),
                                    request)),
                    Named.of("handlePromotionIdempotencyInconsistency", (HandlerInvocation)
                            request -> handler.handlePromotionIdempotencyInconsistency(
                                    new PromotionIdempotencyInconsistencyException(SOME_ID), request)),
                    Named.of("handleIllegalState", (HandlerInvocation)
                            request -> handler.handleIllegalState(new IllegalStateException("bad state"), request)),
                    Named.of("handleWorkSessionNotFound", (HandlerInvocation) request ->
                            handler.handleWorkSessionNotFound(new WorkSessionNotFoundException(SOME_ID), request)),
                    Named.of("handleBreakSegmentNotFound", (HandlerInvocation) request ->
                            handler.handleBreakSegmentNotFound(new BreakSegmentNotFoundException(SOME_ID), request)),
                    Named.of("handleTravelSegmentNotFound", (HandlerInvocation) request ->
                            handler.handleTravelSegmentNotFound(new TravelSegmentNotFoundException(SOME_ID), request)),
                    Named.of("handleTravelSegmentConflict", (HandlerInvocation)
                            request -> handler.handleTravelSegmentConflict(
                                    new TravelSegmentConflictException("conflict"), request)),
                    Named.of("handleDuplicateSubstituteLink", (HandlerInvocation)
                            request -> handler.handleDuplicateSubstituteLink(
                                    new DuplicateSubstituteLinkException(SOME_ID, OTHER_ID), request)),
                    Named.of("handleSubstituteLinkNotFound", (HandlerInvocation)
                            request -> handler.handleSubstituteLinkNotFound(
                                    new SubstituteLinkNotFoundException(SOME_ID), request)),
                    Named.of("handleStaleSubstituteLinkVersion", (HandlerInvocation)
                            request -> handler.handleStaleSubstituteLinkVersion(
                                    new StaleSubstituteLinkVersionException(SOME_ID, 1, 2), request)),
                    Named.of("handleWorkorderRequestValidation", (HandlerInvocation)
                            request -> handler.handleWorkorderRequestValidation(
                                    new WorkorderRequestValidationException("bad request"), request)),
                    Named.of("handleWorkorderResourceConflict", (HandlerInvocation)
                            request -> handler.handleWorkorderResourceConflict(
                                    new WorkorderResourceConflictException("conflict"), request)),
                    Named.of("handlePurchaseOrderRequired", (HandlerInvocation)
                            request -> handler.handlePurchaseOrderRequired(
                                    new PurchaseOrderRequiredException("PO required"), request)),
                    Named.of("handleChangeRequestNotFound", (HandlerInvocation) request ->
                            handler.handleChangeRequestNotFound(new ChangeRequestNotFoundException(SOME_ID), request)),
                    Named.of("handleServiceLineNotFound", (HandlerInvocation) request ->
                            handler.handleServiceLineNotFound(ServiceLineNotFoundException.forId(SOME_ID), request)),
                    Named.of("handlePartLineNotFound", (HandlerInvocation) request ->
                            handler.handlePartLineNotFound(PartLineNotFoundException.forId(SOME_ID), request)),
                    Named.of("handleEstimateItemNotFound", (HandlerInvocation)
                            request -> handler.handleEstimateItemNotFound(
                                    new EstimateItemNotFoundException(SOME_ID, OTHER_ID), request)),
                    Named.of("handleApprovalConfigurationNotFound", (HandlerInvocation)
                            request -> handler.handleApprovalConfigurationNotFound(
                                    new ApprovalConfigurationNotFoundException(SOME_ID), request)),
                    Named.of("handleLaborEntryNotFound", (HandlerInvocation) request ->
                            handler.handleLaborEntryNotFound(new LaborEntryNotFoundException(SOME_ID), request)),
                    Named.of("handleConstraintViolation", (HandlerInvocation)
                            request -> handler.handleConstraintViolation(
                                    new ConstraintViolationException(Set.<ConstraintViolation<?>>of()), request)),
                    Named.of("handleAccessDenied", (HandlerInvocation)
                            request -> handler.handleAccessDenied(new AccessDeniedException("denied"), request)),
                    Named.of("handleWorkSessionConflict", (HandlerInvocation) request ->
                            handler.handleWorkSessionConflict(new WorkSessionOverlapException(SOME_ID), request)),
                    Named.of("handleValidationErrors", (HandlerInvocation) request -> {
                        MethodArgumentNotValidException ex = mock(MethodArgumentNotValidException.class);
                        BindingResult bindingResult = mock(BindingResult.class);
                        when(ex.getBindingResult()).thenReturn(bindingResult);
                        when(bindingResult.getFieldErrors()).thenReturn(List.of());
                        return handler.handleValidationErrors(ex, request);
                    }));
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
        @DisplayName("generates a fresh X-Correlation-Id when the inbound header is blank")
        void generatesCorrelationIdWhenInboundIsBlank(HandlerInvocation invocation) {
            ResponseEntity<ApiError> response = invocation.invoke(requestWithBlankHeader());

            String header = response.getHeaders().getFirst("X-Correlation-Id");
            assertThat(header).isNotBlank();
            assertThat(header).isNotEqualTo("   ");
            assertThat(response.getBody()).isNotNull();
            assertThat(header).isEqualTo(response.getBody().correlationId());
        }

        @Test
        @DisplayName("every @ExceptionHandler method on GlobalExceptionHandler has a matching MethodSource entry")
        void everyHandlerMethodIsCovered() {
            long handlerMethodCount = Arrays.stream(GlobalExceptionHandler.class.getDeclaredMethods())
                    .filter(method -> method.isAnnotationPresent(ExceptionHandler.class))
                    .count();
            long methodSourceEntryCount = handlerInvocations().count();

            assertThat(methodSourceEntryCount)
                    .as("A new @ExceptionHandler method was added to GlobalExceptionHandler without a matching "
                            + "entry in XCorrelationIdHeader#handlerInvocations() in GlobalExceptionHandlerTest — "
                            + "add one so the X-Correlation-Id header contract stays proven for every handler")
                    .isEqualTo(handlerMethodCount);
        }
    }
}

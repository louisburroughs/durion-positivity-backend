package com.positivity.price.internal.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.price.internal.exception.BasePriceUnavailableException;
import com.positivity.price.internal.exception.BasePriceWindowConflictException;
import com.positivity.price.internal.exception.DuplicatePromoCodeException;
import com.positivity.price.internal.exception.EligibilityRuleNotFoundException;
import com.positivity.price.internal.exception.PromotionCodeNotFoundException;
import com.positivity.price.internal.exception.PromotionMultipleNotAllowedException;
import com.positivity.price.internal.exception.PromotionNotApplicableException;
import com.positivity.price.internal.exception.PromotionOfferNotFoundException;
import com.positivity.price.internal.exception.PromotionOfferStateException;
import com.positivity.price.internal.exception.RestrictionRuleNotFoundException;
import com.positivity.price.internal.exception.RestrictionServiceUnavailableException;
import com.positivity.price.internal.exception.SnapshotNotFoundException;
import com.positivity.shared.error.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.core.MethodParameter;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;

/**
 * Verifies every {@code @ExceptionHandler} on {@link GlobalExceptionHandler} carries the
 * correlation id in both the {@link ApiError} body and the {@code X-Correlation-Id} response
 * header (ADR-0017 §4, issue #1729).
 */
class GlobalExceptionHandlerTest {

    private static final String CORRELATION_HEADER = "X-Correlation-Id";
    private static final String CORRELATION_ID = "test-correlation-id";

    @Nested
    @DisplayName("X-Correlation-Id header (ADR-0017 §4, #1729)")
    class XCorrelationIdHeader {

        @FunctionalInterface
        interface HandlerInvocation {
            ResponseEntity<ApiError> invoke(HttpServletRequest request);
        }

        /**
         * One entry per {@code @ExceptionHandler} method on {@link GlobalExceptionHandler}. Uses
         * a standalone handler instance so this factory method can stay static, as required by
         * {@code @MethodSource} outside a {@code PER_CLASS} test instance lifecycle.
         */
        private static Stream<Named<HandlerInvocation>> handlerInvocations() throws NoSuchMethodException {
            Clock fixedClock = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC);
            GlobalExceptionHandler handler = new GlobalExceptionHandler(fixedClock);

            BindingResult binding = new BeanPropertyBindingResult(new Object(), "request");
            binding.addError(new FieldError("request", "promoCode", "must not be blank"));
            MethodArgumentNotValidException validationException = new MethodArgumentNotValidException(
                    new MethodParameter(XCorrelationIdHeader.class.getDeclaredMethod("handlerInvocations"), -1),
                    binding);

            return Stream.of(
                    Named.of("handlePromotionOfferNotFound", (HandlerInvocation)
                            request -> handler.handlePromotionOfferNotFound(
                                    new PromotionOfferNotFoundException(UUID.randomUUID()), request)),
                    Named.of("handleDuplicatePromoCode", (HandlerInvocation) request ->
                            handler.handleDuplicatePromoCode(new DuplicatePromoCodeException("SAVE10"), request)),
                    Named.of("handlePromotionOfferState", (HandlerInvocation)
                            request -> handler.handlePromotionOfferState(
                                    new PromotionOfferStateException("Offer is not active"), request)),
                    Named.of("handleBasePriceUnavailable", (HandlerInvocation)
                            request -> handler.handleBasePriceUnavailable(
                                    new BasePriceUnavailableException(
                                            UUID.randomUUID(), "USD", Instant.parse("2024-01-01T00:00:00Z")),
                                    request)),
                    Named.of("handleBasePriceWindowConflict", (HandlerInvocation)
                            request -> handler.handleBasePriceWindowConflict(
                                    new BasePriceWindowConflictException(
                                            UUID.randomUUID(), "USD", Instant.parse("2024-01-01T00:00:00Z")),
                                    request)),
                    Named.of("handleSnapshotNotFound", (HandlerInvocation) request ->
                            handler.handleSnapshotNotFound(new SnapshotNotFoundException(UUID.randomUUID()), request)),
                    Named.of("handleEligibilityRuleNotFound", (HandlerInvocation)
                            request -> handler.handleEligibilityRuleNotFound(
                                    new EligibilityRuleNotFoundException(UUID.randomUUID()), request)),
                    Named.of("handlePromotionCodeNotFound", (HandlerInvocation) request ->
                            handler.handlePromotionCodeNotFound(new PromotionCodeNotFoundException("SAVE10"), request)),
                    Named.of("handlePromotionNotApplicable", (HandlerInvocation)
                            request -> handler.handlePromotionNotApplicable(
                                    new PromotionNotApplicableException("Promotion not applicable to cart"), request)),
                    Named.of("handlePromotionMultipleNotAllowed", (HandlerInvocation)
                            request -> handler.handlePromotionMultipleNotAllowed(
                                    new PromotionMultipleNotAllowedException(), request)),
                    Named.of("handleRestrictionServiceUnavailable", (HandlerInvocation)
                            request -> handler.handleRestrictionServiceUnavailable(
                                    new RestrictionServiceUnavailableException("restriction service down"), request)),
                    Named.of("handleRestrictionRuleNotFound", (HandlerInvocation)
                            request -> handler.handleRestrictionRuleNotFound(
                                    new RestrictionRuleNotFoundException("Restriction rule not found"), request)),
                    Named.of("handleValidationError", (HandlerInvocation)
                            request -> handler.handleValidationError(validationException, request)));
        }

        @ParameterizedTest
        @MethodSource("handlerInvocations")
        @DisplayName("echoes the inbound X-Correlation-Id in both header and body")
        void echoesInboundCorrelationId(HandlerInvocation invocation) {
            ResponseEntity<ApiError> response = invocation.invoke(requestWithHeader());

            assertThat(response.getHeaders().getFirst(CORRELATION_HEADER)).isEqualTo(CORRELATION_ID);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getHeaders().getFirst(CORRELATION_HEADER))
                    .isEqualTo(response.getBody().correlationId());
        }

        @ParameterizedTest
        @MethodSource("handlerInvocations")
        @DisplayName("generates a non-blank X-Correlation-Id, consistent between header and body, when absent")
        void generatesCorrelationIdWhenAbsent(HandlerInvocation invocation) {
            ResponseEntity<ApiError> response = invocation.invoke(requestWithoutHeader());

            String header = response.getHeaders().getFirst(CORRELATION_HEADER);
            assertThat(header).isNotBlank();
            assertThat(response.getBody()).isNotNull();
            assertThat(header).isEqualTo(response.getBody().correlationId());
        }

        @ParameterizedTest
        @MethodSource("handlerInvocations")
        @DisplayName("generates a fresh X-Correlation-Id when the inbound header is blank")
        void generatesCorrelationIdWhenInboundIsBlank(HandlerInvocation invocation) {
            ResponseEntity<ApiError> response = invocation.invoke(requestWithBlankHeader());

            String header = response.getHeaders().getFirst(CORRELATION_HEADER);
            assertThat(header).isNotBlank();
            assertThat(header).isNotEqualTo("   ");
            assertThat(response.getBody()).isNotNull();
            assertThat(header).isEqualTo(response.getBody().correlationId());
        }

        @Test
        @DisplayName("every @ExceptionHandler method on GlobalExceptionHandler has a matching MethodSource entry")
        void everyHandlerMethodIsCovered() throws NoSuchMethodException {
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

        private MockHttpServletRequest requestWithHeader() {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.addHeader(CORRELATION_HEADER, CORRELATION_ID);
            return request;
        }

        private MockHttpServletRequest requestWithoutHeader() {
            return new MockHttpServletRequest();
        }

        private MockHttpServletRequest requestWithBlankHeader() {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.addHeader(CORRELATION_HEADER, "   ");
            return request;
        }
    }
}

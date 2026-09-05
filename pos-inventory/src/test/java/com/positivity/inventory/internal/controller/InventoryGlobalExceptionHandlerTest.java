package com.positivity.inventory.internal.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.inventory.internal.enums.PurchaseSuggestionStatus;
import com.positivity.inventory.internal.exception.AdjustmentLedgerPostingException;
import com.positivity.inventory.internal.exception.AsOfInFutureException;
import com.positivity.inventory.internal.exception.CrossSiteTransferRequiresOrderException;
import com.positivity.inventory.internal.exception.CycleCountConflictException;
import com.positivity.inventory.internal.exception.CycleCountPlanNotFoundException;
import com.positivity.inventory.internal.exception.DuplicateAsnException;
import com.positivity.inventory.internal.exception.DuplicateEnabledAnyPutawayRuleException;
import com.positivity.inventory.internal.exception.FractionalQuantityNotAllowedException;
import com.positivity.inventory.internal.exception.InsufficientAtpException;
import com.positivity.inventory.internal.exception.InsufficientPermissionException;
import com.positivity.inventory.internal.exception.InsufficientStockException;
import com.positivity.inventory.internal.exception.InvalidCountQuantityException;
import com.positivity.inventory.internal.exception.InvalidParamCombinationException;
import com.positivity.inventory.internal.exception.InvalidPoReferenceException;
import com.positivity.inventory.internal.exception.LocationServiceUnavailableException;
import com.positivity.inventory.internal.exception.LotInsufficientStockException;
import com.positivity.inventory.internal.exception.LotNotAvailableException;
import com.positivity.inventory.internal.exception.LotNumberRequiredException;
import com.positivity.inventory.internal.exception.LotUnknownException;
import com.positivity.inventory.internal.exception.NegativeStockPolicyViolationException;
import com.positivity.inventory.internal.exception.OverReceiptNotPermittedException;
import com.positivity.inventory.internal.exception.PartMatchPermissionException;
import com.positivity.inventory.internal.exception.PickScanMismatchException;
import com.positivity.inventory.internal.exception.PurchaseSuggestionConversionException;
import com.positivity.inventory.internal.exception.PurchaseSuggestionStateException;
import com.positivity.inventory.internal.exception.PutawayValidationException;
import com.positivity.inventory.internal.exception.RecountLimitExceededException;
import com.positivity.inventory.internal.exception.ResourceNotFoundException;
import com.positivity.inventory.internal.exception.ReturnQuantityExceededException;
import com.positivity.inventory.internal.exception.RollupExpansionTooLargeException;
import com.positivity.inventory.internal.exception.ScrapInsufficientStockException;
import com.positivity.inventory.internal.exception.ScrapLedgerPostingException;
import com.positivity.inventory.internal.exception.ScrapNotFoundException;
import com.positivity.inventory.internal.exception.SerialAlreadyInStockException;
import com.positivity.inventory.internal.exception.SerialCountMismatchException;
import com.positivity.inventory.internal.exception.SerialNotAvailableException;
import com.positivity.inventory.internal.exception.ShortageResolutionException;
import com.positivity.inventory.internal.exception.SnoozeUntilNotInFutureException;
import com.positivity.inventory.internal.exception.SourceDocumentAlreadyReceivedException;
import com.positivity.inventory.internal.exception.SourceDocumentLinesUnavailableException;
import com.positivity.inventory.internal.exception.SourceDocumentNotFoundException;
import com.positivity.inventory.internal.exception.TaskNotFoundException;
import com.positivity.inventory.internal.exception.TransferLocationNotEligibleException;
import com.positivity.inventory.internal.exception.TransferOrderNotFoundException;
import com.positivity.inventory.internal.exception.TransferQuantityExceededException;
import com.positivity.inventory.internal.exception.UnsupportedSourceDocumentTypeException;
import com.positivity.inventory.internal.exception.UomConversionUndefinedException;
import com.positivity.inventory.internal.exception.ValuationAsOfSkuCapExceededException;
import com.positivity.inventory.internal.exception.WorkorderClosedException;
import com.positivity.inventory.internal.exception.WorkorderConsumptionException;
import com.positivity.shared.error.ApiError;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Unit tests for {@link InventoryGlobalExceptionHandler}.
 */
class InventoryGlobalExceptionHandlerTest {

    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC);
    private static final String X_CORRELATION_ID = "X-Correlation-Id";
    private static final String CORRELATION_ID = "test-correlation-id";

    private final InventoryGlobalExceptionHandler sut = new InventoryGlobalExceptionHandler(TEST_CLOCK);

    @AfterEach
    void resetRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    private static void setRequestCorrelationId(String value) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (value != null) {
            request.addHeader(X_CORRELATION_ID, value);
        }
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    // Dummy target for building a MethodParameter for MethodArgumentNotValidException.
    @SuppressWarnings("unused")
    private static void dummyMethod(String arg) {}

    private static MethodArgumentNotValidException validationException() throws NoSuchMethodException {
        BindingResult binding = new BeanPropertyBindingResult(new Object(), "request");
        return new MethodArgumentNotValidException(
                new MethodParameter(
                        InventoryGlobalExceptionHandlerTest.class.getDeclaredMethod("dummyMethod", String.class), 0),
                binding);
    }

    @Test
    @DisplayName("handleValidationError returns 400 VALIDATION_ERROR with default message when no field errors")
    void handleValidationError_defaultMessage() throws NoSuchMethodException {
        ResponseEntity<ApiError> response = sut.handleValidationError(validationException());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("VALIDATION_ERROR");
        assertThat(response.getBody().message()).isEqualTo("Validation failed");
    }

    // ---------------------------------------------------------------
    // X-Correlation-Id header (ADR-0017 §4, #1729) — proves the single `build`
    // helper puts the correlation id in both the body and the header for EVERY
    // @ExceptionHandler method, and guards against a future handler forgetting it.
    //
    // InventoryGlobalExceptionHandler was already fully compliant before this sweep:
    // every handler already routed through `build(...)`, which already set the
    // X-Correlation-Id header via UUIDv7Generator (no v4->v7 change needed). The one
    // handler that bypassed `build` (handleSourceDocumentLinesUnavailable) duplicated
    // the same header-setting logic inline; it has been refactored to route through
    // an overloaded `build(...)` so there remains exactly one response-building helper.
    // ---------------------------------------------------------------

    @Nested
    @DisplayName("X-Correlation-Id header (ADR-0017 §4, #1729)")
    class XCorrelationIdHeader {

        @FunctionalInterface
        interface HandlerInvocation {
            ResponseEntity<ApiError> invoke() throws Exception;
        }

        /**
         * One entry per {@code @ExceptionHandler} method on {@link InventoryGlobalExceptionHandler}.
         * Uses a standalone handler instance (not the outer test's {@code sut}) so this factory
         * method can stay static, as required by {@code @MethodSource} outside a {@code PER_CLASS}
         * test instance lifecycle. Correlation id resolution reads {@link RequestContextHolder},
         * which each parameterized test primes before invoking.
         */
        private static Stream<Named<HandlerInvocation>> handlerInvocations() {
            InventoryGlobalExceptionHandler handler = new InventoryGlobalExceptionHandler(TEST_CLOCK);

            return Stream.of(
                    Named.of("handleValidationError", (HandlerInvocation)
                            () -> handler.handleValidationError(validationException())),
                    Named.of("handleBadRequest", (HandlerInvocation)
                            () -> handler.handleBadRequest(new IllegalArgumentException("bad request"))),
                    Named.of("handleIllegalState", (HandlerInvocation)
                            () -> handler.handleIllegalState(new IllegalStateException("conflict"))),
                    Named.of("handleInvalidPoReference", (HandlerInvocation)
                            () -> handler.handleInvalidPoReference(new InvalidPoReferenceException("bad po ref"))),
                    Named.of("handleDuplicateAsn", (HandlerInvocation)
                            () -> handler.handleDuplicateAsn(new DuplicateAsnException("dup asn"))),
                    Named.of("handleDuplicateEnabledAnyPutawayRule", (HandlerInvocation)
                            () -> handler.handleDuplicateEnabledAnyPutawayRule(
                                    new DuplicateEnabledAnyPutawayRuleException(UUID.randomUUID()))),
                    Named.of("handleOverReceiptNotPermitted", (HandlerInvocation)
                            () -> handler.handleOverReceiptNotPermitted(
                                    new OverReceiptNotPermittedException("over receipt"))),
                    Named.of("handleTaskNotFound", (HandlerInvocation)
                            () -> handler.handleTaskNotFound(new TaskNotFoundException(UUID.randomUUID()))),
                    Named.of("handleCycleCountPlanNotFound", (HandlerInvocation)
                            () -> handler.handleCycleCountPlanNotFound(
                                    new CycleCountPlanNotFoundException(UUID.randomUUID()))),
                    Named.of("handleResourceNotFound", (HandlerInvocation)
                            () -> handler.handleResourceNotFound(new ResourceNotFoundException("Task", "task-1"))),
                    Named.of("handleLocationServiceUnavailable", (HandlerInvocation)
                            () -> handler.handleLocationServiceUnavailable(new LocationServiceUnavailableException(
                                    "unavailable", new RuntimeException("cause")))),
                    Named.of("handleRollupExpansionTooLarge", (HandlerInvocation)
                            () -> handler.handleRollupExpansionTooLarge(new RollupExpansionTooLargeException(100, 50))),
                    Named.of("handleInvalidCountQuantity", (HandlerInvocation)
                            () -> handler.handleInvalidCountQuantity(new InvalidCountQuantityException("bad qty"))),
                    Named.of("handleInsufficientStock", (HandlerInvocation) () -> handler.handleInsufficientStock(
                            new InsufficientStockException("SKU-1", UUID.randomUUID()))),
                    Named.of("handleNegativeStockPolicyViolation", (HandlerInvocation)
                            () -> handler.handleNegativeStockPolicyViolation(new NegativeStockPolicyViolationException(
                                    NegativeStockPolicyViolationException.OVERRIDE_REQUIRED, "msg"))),
                    Named.of("handleCycleCountConflict", (HandlerInvocation) () -> handler.handleCycleCountConflict(
                            new CycleCountConflictException(UUID.randomUUID(), BigDecimal.ONE))),
                    Named.of("handleRecountLimitExceeded", (HandlerInvocation) () -> handler.handleRecountLimitExceeded(
                            new RecountLimitExceededException(UUID.randomUUID(), 3, 2))),
                    Named.of("handleAsOfInFuture", (HandlerInvocation)
                            () -> handler.handleAsOfInFuture(new AsOfInFutureException(Instant.now()))),
                    Named.of("handleValuationAsOfSkuCapExceeded", (HandlerInvocation)
                            () -> handler.handleValuationAsOfSkuCapExceeded(
                                    new ValuationAsOfSkuCapExceededException(100, 50))),
                    Named.of("handleSnoozeUntilNotInFuture", (HandlerInvocation) () ->
                            handler.handleSnoozeUntilNotInFuture(new SnoozeUntilNotInFutureException(Instant.now()))),
                    Named.of("handleInsufficientPermission", (HandlerInvocation) () ->
                            handler.handleInsufficientPermission(new InsufficientPermissionException("no permission"))),
                    Named.of("handleInsufficientAtp", (HandlerInvocation) () -> handler.handleInsufficientAtp(
                            new InsufficientAtpException(UUID.randomUUID(), BigDecimal.TEN, BigDecimal.ONE))),
                    Named.of("handlePickScanMismatch", (HandlerInvocation) () -> handler.handlePickScanMismatch(
                            new PickScanMismatchException(UUID.randomUUID(), UUID.randomUUID()))),
                    Named.of("handleWorkorderConsumption", (HandlerInvocation) () -> handler.handleWorkorderConsumption(
                            new WorkorderConsumptionException("consumption failed"))),
                    Named.of("handleReturnQuantityExceeded", (HandlerInvocation)
                            () -> handler.handleReturnQuantityExceeded(new ReturnQuantityExceededException(
                                    UUID.randomUUID(), BigDecimal.TEN, BigDecimal.ONE))),
                    Named.of("handleAdjustmentLedgerPosting", (HandlerInvocation)
                            () -> handler.handleAdjustmentLedgerPosting(new AdjustmentLedgerPostingException(
                                    UUID.randomUUID(), "ledger failed", new RuntimeException()))),
                    Named.of("handleTransferQuantityExceeded", (HandlerInvocation)
                            () -> handler.handleTransferQuantityExceeded(
                                    TransferQuantityExceededException.dispatchExceedsRequested(
                                            UUID.randomUUID(), "SKU-1", 5, 3))),
                    Named.of("handleCrossSiteTransferRequiresOrder", (HandlerInvocation) () ->
                            handler.handleCrossSiteTransferRequiresOrder(new CrossSiteTransferRequiresOrderException(
                                    UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()))),
                    Named.of("handleTransferOrderNotFound", (HandlerInvocation) () ->
                            handler.handleTransferOrderNotFound(new TransferOrderNotFoundException(UUID.randomUUID()))),
                    Named.of("handleTransferLocationNotEligible", (HandlerInvocation)
                            () -> handler.handleTransferLocationNotEligible(
                                    new TransferLocationNotEligibleException(UUID.randomUUID(), "INACTIVE", "SOURCE"))),
                    Named.of("handleScrapNotFound", (HandlerInvocation)
                            () -> handler.handleScrapNotFound(new ScrapNotFoundException(UUID.randomUUID()))),
                    Named.of("handleScrapInsufficientStock", (HandlerInvocation)
                            () -> handler.handleScrapInsufficientStock(
                                    new ScrapInsufficientStockException("stock-1", UUID.randomUUID(), 5))),
                    Named.of("handleScrapLedgerPosting", (HandlerInvocation)
                            () -> handler.handleScrapLedgerPosting(new ScrapLedgerPostingException(
                                    UUID.randomUUID(), "scrap ledger failed", new RuntimeException()))),
                    Named.of("handlePutawayValidation", (HandlerInvocation) () -> handler.handlePutawayValidation(
                            new PutawayValidationException("PUTAWAY_ERR", "putaway invalid"))),
                    Named.of("handleReceivingNotFound", (HandlerInvocation) () ->
                            handler.handleReceivingNotFound(new SourceDocumentNotFoundException("doc not found"))),
                    Named.of("handleSourceDocumentLinesUnavailable", (HandlerInvocation)
                            () -> handler.handleSourceDocumentLinesUnavailable(
                                    new SourceDocumentLinesUnavailableException("lines unavailable"))),
                    Named.of("handleUnsupportedSourceDocumentType", (HandlerInvocation)
                            () -> handler.handleUnsupportedSourceDocumentType(
                                    new UnsupportedSourceDocumentTypeException("ASN"))),
                    Named.of("handleSourceDocumentAlreadyReceived", (HandlerInvocation)
                            () -> handler.handleSourceDocumentAlreadyReceived(
                                    new SourceDocumentAlreadyReceivedException("already received"))),
                    Named.of("handleAccessDenied", (HandlerInvocation)
                            () -> handler.handleAccessDenied(new AccessDeniedException("denied"))),
                    Named.of("handleWorkorderClosed", (HandlerInvocation)
                            () -> handler.handleWorkorderClosed(new WorkorderClosedException("closed"))),
                    Named.of("handlePartMatchPermission", (HandlerInvocation) () -> handler.handlePartMatchPermission(
                            new PartMatchPermissionException("no part match permission"))),
                    Named.of("handleFractionalQuantityNotAllowed", (HandlerInvocation)
                            () -> handler.handleFractionalQuantityNotAllowed(
                                    new FractionalQuantityNotAllowedException("frac not allowed", UUID.randomUUID()))),
                    Named.of("handleUomConversionUndefined", (HandlerInvocation)
                            () -> handler.handleUomConversionUndefined(
                                    UomConversionUndefinedException.unknownProduct(UUID.randomUUID(), "EA"))),
                    Named.of("handleLotNumberRequired", (HandlerInvocation)
                            () -> handler.handleLotNumberRequired(new LotNumberRequiredException("stock-1"))),
                    Named.of("handleLotUnknown", (HandlerInvocation)
                            () -> handler.handleLotUnknown(new LotUnknownException("stock-1", "LOT-1"))),
                    Named.of("handleLotNotAvailable", (HandlerInvocation) () -> handler.handleLotNotAvailable(
                            new LotNotAvailableException("stock-1", "LOT-1", "QUARANTINED"))),
                    Named.of("handleLotInsufficientStock", (HandlerInvocation)
                            () -> handler.handleLotInsufficientStock(new LotInsufficientStockException(
                                    "stock-1", UUID.randomUUID(), UUID.randomUUID(), BigDecimal.valueOf(-1)))),
                    Named.of("handleSerialCountMismatch", (HandlerInvocation)
                            () -> handler.handleSerialCountMismatch(new SerialCountMismatchException("stock-1", 3, 2))),
                    Named.of("handleSerialAlreadyInStock", (HandlerInvocation) () ->
                            handler.handleSerialAlreadyInStock(new SerialAlreadyInStockException("stock-1", "SN-1"))),
                    Named.of("handleSerialNotAvailable", (HandlerInvocation)
                            () -> handler.handleSerialNotAvailable(new SerialNotAvailableException("stock-1", "SN-1"))),
                    Named.of("handlePurchaseSuggestionConversion", (HandlerInvocation)
                            () -> handler.handlePurchaseSuggestionConversion(
                                    PurchaseSuggestionConversionException.vendorMismatch())),
                    Named.of("handlePurchaseSuggestionState", (HandlerInvocation)
                            () -> handler.handlePurchaseSuggestionState(new PurchaseSuggestionStateException(
                                    UUID.randomUUID(), PurchaseSuggestionStatus.CONVERTED, "accept"))),
                    Named.of("handleInvalidParamCombination", (HandlerInvocation) () ->
                            handler.handleInvalidParamCombination(new InvalidParamCombinationException("bad combo"))),
                    Named.of("handleShortageResolution", (HandlerInvocation) () -> handler.handleShortageResolution(
                            ShortageResolutionException.substituteUnavailable("SUB-1"))));
        }

        @ParameterizedTest
        @MethodSource("handlerInvocations")
        @DisplayName("echoes the inbound X-Correlation-Id in both header and body")
        void echoesInboundCorrelationId(HandlerInvocation invocation) throws Exception {
            setRequestCorrelationId(CORRELATION_ID);

            ResponseEntity<ApiError> response = invocation.invoke();

            assertThat(response.getHeaders().getFirst(X_CORRELATION_ID)).isEqualTo(CORRELATION_ID);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getHeaders().getFirst(X_CORRELATION_ID))
                    .isEqualTo(response.getBody().correlationId());
        }

        @ParameterizedTest
        @MethodSource("handlerInvocations")
        @DisplayName("generates a non-blank X-Correlation-Id, consistent between header and body, when absent")
        void generatesCorrelationIdWhenAbsent(HandlerInvocation invocation) throws Exception {
            RequestContextHolder.resetRequestAttributes();

            ResponseEntity<ApiError> response = invocation.invoke();

            String header = response.getHeaders().getFirst(X_CORRELATION_ID);
            assertThat(header).isNotBlank();
            assertThat(response.getBody()).isNotNull();
            assertThat(header).isEqualTo(response.getBody().correlationId());
        }

        @ParameterizedTest
        @MethodSource("handlerInvocations")
        @DisplayName("generates a fresh X-Correlation-Id when the inbound header is blank")
        void generatesCorrelationIdWhenInboundIsBlank(HandlerInvocation invocation) throws Exception {
            setRequestCorrelationId("   ");

            ResponseEntity<ApiError> response = invocation.invoke();

            String header = response.getHeaders().getFirst(X_CORRELATION_ID);
            assertThat(header).isNotBlank();
            assertThat(header).isNotEqualTo("   ");
            assertThat(response.getBody()).isNotNull();
            assertThat(header).isEqualTo(response.getBody().correlationId());
        }

        @Test
        @DisplayName("every @ExceptionHandler method on InventoryGlobalExceptionHandler has a matching "
                + "MethodSource entry")
        void everyHandlerMethodIsCovered() {
            long handlerMethodCount = Arrays.stream(InventoryGlobalExceptionHandler.class.getDeclaredMethods())
                    .filter(method -> method.isAnnotationPresent(ExceptionHandler.class))
                    .count();
            long methodSourceEntryCount = handlerInvocations().count();

            assertThat(methodSourceEntryCount)
                    .as("A new @ExceptionHandler method was added to InventoryGlobalExceptionHandler without a"
                            + " matching entry in XCorrelationIdHeader#handlerInvocations() in"
                            + " InventoryGlobalExceptionHandlerTest — add one so the X-Correlation-Id header"
                            + " contract stays proven for every handler")
                    .isEqualTo(handlerMethodCount);
        }
    }
}

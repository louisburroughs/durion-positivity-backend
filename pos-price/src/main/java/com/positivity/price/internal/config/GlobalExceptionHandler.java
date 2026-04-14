package com.positivity.price.internal.config;

import com.positivity.price.internal.exception.DuplicatePromoCodeException;
import com.positivity.price.internal.exception.EligibilityRuleNotFoundException;
import com.positivity.price.internal.exception.ProductNotFoundException;
import com.positivity.price.internal.exception.PromotionCodeNotFoundException;
import com.positivity.price.internal.exception.PromotionMultipleNotAllowedException;
import com.positivity.price.internal.exception.PromotionNotApplicableException;
import com.positivity.price.internal.exception.PromotionOfferNotFoundException;
import com.positivity.price.internal.exception.PromotionOfferStateException;
import com.positivity.price.internal.exception.RestrictionRuleNotFoundException;
import com.positivity.price.internal.exception.RestrictionServiceUnavailableException;
import com.positivity.price.internal.exception.SnapshotNotFoundException;
import com.positivity.shared.error.ApiError;
import com.positivity.shared.id.UUIDv7Generator;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Module-wide exception handler for pos-price REST API. Issue: #97 */
@Slf4j
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
public class GlobalExceptionHandler {

    private final Clock clock;
    private static final String PROMOTION_ERROR = "Promotion error [{}]: {}";
    private static final String CORRELATION_HEADER = "X-Correlation-Id";

    @ExceptionHandler(PromotionOfferNotFoundException.class)
    public ResponseEntity<ApiError> handlePromotionOfferNotFound(
            PromotionOfferNotFoundException exception, HttpServletRequest request) {
        return buildErrorResponse(
                HttpStatus.NOT_FOUND, "PROMOTION_OFFER_NOT_FOUND", exception.getMessage(), null, request);
    }

    @ExceptionHandler(DuplicatePromoCodeException.class)
    public ResponseEntity<ApiError> handleDuplicatePromoCode(
            DuplicatePromoCodeException exception, HttpServletRequest request) {
        return buildErrorResponse(HttpStatus.CONFLICT, "DUPLICATE_PROMO_CODE", exception.getMessage(), null, request);
    }

    @ExceptionHandler(PromotionOfferStateException.class)
    public ResponseEntity<ApiError> handlePromotionOfferState(
            PromotionOfferStateException exception, HttpServletRequest request) {
        return buildErrorResponse(
                HttpStatus.UNPROCESSABLE_CONTENT,
                "PROMOTION_OFFER_INVALID_STATE",
                exception.getMessage(),
                null,
                request);
    }

    @ExceptionHandler(ProductNotFoundException.class)
    public ResponseEntity<ApiError> handleProductNotFound(
            ProductNotFoundException exception, HttpServletRequest request) {
        return buildErrorResponse(HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND", exception.getMessage(), null, request);
    }

    @ExceptionHandler(SnapshotNotFoundException.class)
    public ResponseEntity<ApiError> handleSnapshotNotFound(
            SnapshotNotFoundException exception, HttpServletRequest request) {
        return buildErrorResponse(HttpStatus.NOT_FOUND, "SNAPSHOT_NOT_FOUND", exception.getMessage(), null, request);
    }

    @ExceptionHandler(EligibilityRuleNotFoundException.class)
    public ResponseEntity<ApiError> handleEligibilityRuleNotFound(
            EligibilityRuleNotFoundException ex, HttpServletRequest request) {
        log.warn(PROMOTION_ERROR, ex.getClass().getSimpleName(), ex.getMessage());
        return buildErrorResponse(HttpStatus.NOT_FOUND, "ELIGIBILITY_RULE_NOT_FOUND", ex.getMessage(), null, request);
    }

    @ExceptionHandler(PromotionCodeNotFoundException.class)
    public ResponseEntity<ApiError> handlePromotionCodeNotFound(
            PromotionCodeNotFoundException ex, HttpServletRequest request) {
        log.warn(PROMOTION_ERROR, ex.getClass().getSimpleName(), ex.getMessage());
        return buildErrorResponse(HttpStatus.BAD_REQUEST, "PROMO_NOT_FOUND", ex.getMessage(), null, request);
    }

    @ExceptionHandler(PromotionNotApplicableException.class)
    public ResponseEntity<ApiError> handlePromotionNotApplicable(
            PromotionNotApplicableException ex, HttpServletRequest request) {
        log.warn(PROMOTION_ERROR, ex.getClass().getSimpleName(), ex.getMessage());
        return buildErrorResponse(HttpStatus.BAD_REQUEST, "PROMO_NOT_APPLICABLE", ex.getMessage(), null, request);
    }

    @ExceptionHandler(PromotionMultipleNotAllowedException.class)
    public ResponseEntity<ApiError> handlePromotionMultipleNotAllowed(
            PromotionMultipleNotAllowedException ex, HttpServletRequest request) {
        log.warn(PROMOTION_ERROR, ex.getClass().getSimpleName(), ex.getMessage());
        return buildErrorResponse(HttpStatus.BAD_REQUEST, "PROMO_MULTIPLE_NOT_ALLOWED", ex.getMessage(), null, request);
    }

    @ExceptionHandler(RestrictionServiceUnavailableException.class)
    public ResponseEntity<ApiError> handleRestrictionServiceUnavailable(
            RestrictionServiceUnavailableException ex, HttpServletRequest request) {
        log.error("Restriction evaluation service unavailable: {}", ex.getMessage());
        return buildErrorResponse(
                HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE", ex.getMessage(), null, request);
    }

    @ExceptionHandler(RestrictionRuleNotFoundException.class)
    public ResponseEntity<ApiError> handleRestrictionRuleNotFound(
            RestrictionRuleNotFoundException exception, HttpServletRequest request) {
        return buildErrorResponse(
                HttpStatus.NOT_FOUND, "RESTRICTION_RULE_NOT_FOUND", exception.getMessage(), null, request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidationError(
            MethodArgumentNotValidException exception, HttpServletRequest request) {
        List<ApiError.FieldError> fieldErrors = exception.getBindingResult().getFieldErrors().stream()
                .map(this::toFieldError)
                .toList();

        String existingCorrelationId = (request != null) ? request.getHeader(CORRELATION_HEADER) : null;
        String correlationId = (existingCorrelationId != null && !existingCorrelationId.isBlank())
                ? existingCorrelationId
                : UUIDv7Generator.generate().toString();
        log.debug(
                "Returning {} for code {} correlationId={}",
                HttpStatus.BAD_REQUEST.value(),
                "VALIDATION_ERROR",
                correlationId);

        ApiError body = ApiError.withFieldErrors(
                "VALIDATION_ERROR",
                "Validation failed",
                HttpStatus.BAD_REQUEST.value(),
                Instant.now(clock).toString(),
                correlationId,
                fieldErrors);

        HttpHeaders headers = new HttpHeaders();
        headers.add(CORRELATION_HEADER, correlationId);
        return new ResponseEntity<>(body, headers, HttpStatus.BAD_REQUEST);
    }

    private ApiError.FieldError toFieldError(FieldError fieldError) {
        return new ApiError.FieldError(
                fieldError.getField(),
                fieldError.getDefaultMessage() == null ? "Invalid value" : fieldError.getDefaultMessage());
    }

    private ResponseEntity<ApiError> buildErrorResponse(
            HttpStatus status,
            String code,
            String message,
            List<ApiError.FieldError> fieldErrors,
            HttpServletRequest request) {
        String existingCorrelationId = (request != null) ? request.getHeader(CORRELATION_HEADER) : null;
        String correlationId = (existingCorrelationId != null && !existingCorrelationId.isBlank())
                ? existingCorrelationId
                : UUIDv7Generator.generate().toString();
        log.debug("Returning {} for code {} correlationId={}", status.value(), code, correlationId);

        ApiError body = fieldErrors != null
                ? ApiError.withFieldErrors(
                        code, message, status.value(), Instant.now(clock).toString(), correlationId, fieldErrors)
                : ApiError.of(code, message, status.value(), Instant.now(clock).toString(), correlationId);

        HttpHeaders headers = new HttpHeaders();
        headers.add(CORRELATION_HEADER, correlationId);
        return new ResponseEntity<>(body, headers, status);
    }
}

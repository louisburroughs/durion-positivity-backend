package com.positivity.price.internal.config;

import com.positivity.price.internal.exception.DuplicatePromoCodeException;
import com.positivity.price.internal.exception.EligibilityRuleNotFoundException;
import com.positivity.price.internal.exception.ProductNotFoundException;
import com.positivity.price.internal.exception.PromotionCodeNotFoundException;
import com.positivity.price.internal.exception.PromotionMultipleNotAllowedException;
import com.positivity.price.internal.exception.PromotionNotApplicableException;
import com.positivity.price.internal.exception.PromotionOfferNotFoundException;
import com.positivity.price.internal.exception.PromotionOfferStateException;
import com.positivity.price.internal.exception.SnapshotNotFoundException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Module-wide exception handler for pos-price REST API. Issue: #97 */
@Slf4j
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class GlobalExceptionHandler {

    private static final String CORRELATION_HEADER = "X-Correlation-Id";

    @ExceptionHandler(PromotionOfferNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handlePromotionOfferNotFound(
            PromotionOfferNotFoundException exception,
            HttpServletRequest request) {
        return buildErrorResponse(
                HttpStatus.NOT_FOUND,
                "PROMOTION_OFFER_NOT_FOUND",
                exception.getMessage(),
                null,
                request);
    }

    @ExceptionHandler(DuplicatePromoCodeException.class)
    public ResponseEntity<Map<String, Object>> handleDuplicatePromoCode(
            DuplicatePromoCodeException exception,
            HttpServletRequest request) {
        return buildErrorResponse(
                HttpStatus.CONFLICT,
                "DUPLICATE_PROMO_CODE",
                exception.getMessage(),
                null,
                request);
    }

    @ExceptionHandler(PromotionOfferStateException.class)
    public ResponseEntity<Map<String, Object>> handlePromotionOfferState(
            PromotionOfferStateException exception,
            HttpServletRequest request) {
        return buildErrorResponse(
                HttpStatus.UNPROCESSABLE_CONTENT,
                "PROMOTION_OFFER_INVALID_STATE",
                exception.getMessage(),
                null,
                request);
    }

    @ExceptionHandler(ProductNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleProductNotFound(
            ProductNotFoundException exception,
            HttpServletRequest request) {
        return buildErrorResponse(
                HttpStatus.NOT_FOUND,
                "PRODUCT_NOT_FOUND",
                exception.getMessage(),
                null,
                request);
    }

    @ExceptionHandler(SnapshotNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleSnapshotNotFound(
            SnapshotNotFoundException exception,
            HttpServletRequest request) {
        return buildErrorResponse(
                HttpStatus.NOT_FOUND,
                "SNAPSHOT_NOT_FOUND",
                exception.getMessage(),
                null,
                request);
    }

    @ExceptionHandler(EligibilityRuleNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleEligibilityRuleNotFound(
            EligibilityRuleNotFoundException ex,
            HttpServletRequest request) {
        return buildErrorResponse(
                HttpStatus.NOT_FOUND,
                "ELIGIBILITY_RULE_NOT_FOUND",
                ex.getMessage(),
                null,
                request);
    }

            @ExceptionHandler(PromotionCodeNotFoundException.class)
            public ResponseEntity<Map<String, Object>> handlePromotionCodeNotFound(
                PromotionCodeNotFoundException ex,
                HttpServletRequest request) {
            log.warn("Promotion error [{}]: {}", ex.getClass().getSimpleName(), ex.getMessage());
            return buildErrorResponse(
                HttpStatus.BAD_REQUEST,
                "PROMO_NOT_FOUND",
                ex.getMessage(),
                null,
                request);
            }

            @ExceptionHandler(PromotionNotApplicableException.class)
            public ResponseEntity<Map<String, Object>> handlePromotionNotApplicable(
                PromotionNotApplicableException ex,
                HttpServletRequest request) {
            log.warn("Promotion error [{}]: {}", ex.getClass().getSimpleName(), ex.getMessage());
            return buildErrorResponse(
                HttpStatus.BAD_REQUEST,
                "PROMO_NOT_APPLICABLE",
                ex.getMessage(),
                null,
                request);
            }

            @ExceptionHandler(PromotionMultipleNotAllowedException.class)
            public ResponseEntity<Map<String, Object>> handlePromotionMultipleNotAllowed(
                PromotionMultipleNotAllowedException ex,
                HttpServletRequest request) {
            log.warn("Promotion error [{}]: {}", ex.getClass().getSimpleName(), ex.getMessage());
            return buildErrorResponse(
                HttpStatus.BAD_REQUEST,
                "PROMO_MULTIPLE_NOT_ALLOWED",
                ex.getMessage(),
                null,
                request);
            }

    @ExceptionHandler(MethodArgumentNotValidException.class)
        public ResponseEntity<Map<String, Object>> handleValidationError(
            MethodArgumentNotValidException exception,
            HttpServletRequest request) {
        List<Map<String, String>> fieldErrors = exception.getBindingResult().getFieldErrors().stream()
                .map(this::toFieldError)
                .toList();

        return buildErrorResponse(
                HttpStatus.BAD_REQUEST,
                "VALIDATION_FAILED",
                "Validation failed",
            fieldErrors,
            request);
    }

    private Map<String, String> toFieldError(FieldError fieldError) {
        return Map.of(
                "field", fieldError.getField(),
                "message", fieldError.getDefaultMessage() == null ? "Invalid value" : fieldError.getDefaultMessage());
    }

    private ResponseEntity<Map<String, Object>> buildErrorResponse(
            HttpStatus status,
            String code,
            String message,
            List<Map<String, String>> fieldErrors,
            HttpServletRequest request) {
        String existingCorrelationId = (request != null) ? request.getHeader(CORRELATION_HEADER) : null;
        String correlationId = (existingCorrelationId != null && !existingCorrelationId.isBlank())
                ? existingCorrelationId
                : UUID.randomUUID().toString();
        log.debug("Returning {} for code {} correlationId={}", status.value(), code, correlationId);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("message", message);
        body.put("status", status.value());
        body.put("timestamp", Instant.now().toString());
        body.put("correlationId", correlationId);
        if (fieldErrors != null) {
            body.put("fieldErrors", fieldErrors);
        }

        HttpHeaders headers = new HttpHeaders();
        headers.add(CORRELATION_HEADER, correlationId);
        return new ResponseEntity<>(body, headers, status);
    }
}
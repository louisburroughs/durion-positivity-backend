package com.positivity.supplier.internal.exception;

import com.positivity.shared.error.ApiError;
import com.positivity.shared.id.UUIDv7Generator;
import jakarta.persistence.OptimisticLockException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Module-wide translation of exceptions into the standard {@code ApiError} envelope for every
 * pos-supplier controller (ADR-0017; mirrors pos-warranty {@code WarrantyExceptionHandler}).
 * The typed supplier exceptions map deterministically: not-found → 404, semantic validation →
 * 400, configuration-state collision (including YAML-managed mutation, ADR-0050 §6) → 409 —
 * each carrying its machine-readable domain code.
 */
@RestControllerAdvice(basePackages = "com.positivity.supplier.internal.controller")
public class SupplierExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(SupplierExceptionHandler.class);
    private static final String X_CORRELATION_ID = "X-Correlation-Id";

    /**
     * What a caller is told when the failure is a deployment defect rather than anything about
     * their request. The specific reference stays in the server log (ADR-0050 §3).
     */
    public static final String CONFIGURATION_DEFECT_MESSAGE =
            "Supplier connectivity is not correctly configured for this operation. Contact an administrator.";

    /**
     * Code → status table for {@link SupplierConfigurationException} (ADR-0017 §1/§2). Every code
     * declared on that exception must appear here; {@code SupplierConfigurationCodeMappingTest}
     * enforces that by reflection so a new code cannot silently inherit the 500 catch-all.
     */
    static final Map<String, HttpStatus> CONFIGURATION_STATUS = Map.of(
            // Caller-actionable configuration state.
            SupplierConfigurationException.UNKNOWN_SUPPLIER, HttpStatus.NOT_FOUND,
            SupplierConfigurationException.PROFILE_DISABLED, HttpStatus.CONFLICT,
            SupplierConfigurationException.CAPABILITY_NOT_CONFIGURED, HttpStatus.CONFLICT,
            SupplierConfigurationException.MISSING_BILLING_ACCOUNT, HttpStatus.CONFLICT,
            SupplierConfigurationException.MISSING_DELIVERY_MAPPING, HttpStatus.CONFLICT,
            SupplierConfigurationException.AUTH_CONFIG_MISSING, HttpStatus.CONFLICT,
            // Deployment defects: generic message, detail logged server-side only.
            SupplierConfigurationException.SECRET_REFERENCE_INVALID, HttpStatus.INTERNAL_SERVER_ERROR,
            SupplierConfigurationException.UNKNOWN_SECRET_SCHEME, HttpStatus.INTERNAL_SERVER_ERROR,
            SupplierConfigurationException.SECRET_NOT_FOUND, HttpStatus.INTERNAL_SERVER_ERROR,
            SupplierConfigurationException.YAML_BOOTSTRAP_INVALID, HttpStatus.INTERNAL_SERVER_ERROR);

    private final Clock clock;

    public SupplierExceptionHandler(Clock clock) {
        this.clock = clock;
    }

    @ExceptionHandler(SupplierNotFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(SupplierNotFoundException ex, HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, ex.getCode(), ex.getMessage(), request);
    }

    /** Semantically invalid vendor profile data — unknown canonical keys, bad refs (ADR-0050). */
    @ExceptionHandler(SupplierValidationException.class)
    public ResponseEntity<ApiError> handleValidation(SupplierValidationException ex, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, ex.getCode(), ex.getMessage(), request);
    }

    /** Configuration-state collisions, including YAML-managed profile mutation (ADR-0050 §6). */
    @ExceptionHandler(SupplierConflictException.class)
    public ResponseEntity<ApiError> handleConflict(SupplierConflictException ex, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, ex.getCode(), ex.getMessage(), request);
    }

    /**
     * Typed pre-flight configuration failures (ADR-0050 §3/§4/§5), mapped by code — never the 500
     * catch-all, which is the error leak ADR-0050 §3 forbids and which is exactly how
     * {@code SUPPLIER_CAPABILITY_NOT_CONFIGURED} would otherwise surface once slice 3 exposes the
     * resolver over HTTP.
     *
     * <p>Two families, split by who can act on the failure:
     *
     * <ul>
     *   <li><strong>Caller-actionable configuration state → 4xx carrying the domain message.</strong>
     *       An unknown supplier alias is 404; a disabled profile, an unbound capability and a
     *       missing account/auth mapping are 409, because the request cannot be applied in the
     *       current configuration state (ADR-0017 §2 prefers 409 to 422 for state collisions).
     *   <li><strong>Deployment defect → 500 with a generic message.</strong> A malformed,
     *       unsupported-scheme or unset secret reference means the deployment is misconfigured.
     *       The caller can do nothing about it, and the exception message names the reference (an
     *       environment variable NAME), so the detail is logged server-side and kept out of the
     *       response envelope.
     * </ul>
     */
    @ExceptionHandler(SupplierConfigurationException.class)
    public ResponseEntity<ApiError> handleConfiguration(SupplierConfigurationException ex, HttpServletRequest request) {
        HttpStatus status = CONFIGURATION_STATUS.get(ex.getCode());
        if (status == null) {
            // A new code was added without deciding its HTTP meaning. Fail closed and loudly;
            // SupplierConfigurationCodeMappingTest exists so this cannot reach production.
            log.error(
                    "Unmapped SupplierConfigurationException code '{}'; defaulting to 500. Add it to"
                            + " SupplierExceptionHandler.CONFIGURATION_STATUS.",
                    ex.getCode(),
                    ex);
            return build(HttpStatus.INTERNAL_SERVER_ERROR, ex.getCode(), CONFIGURATION_DEFECT_MESSAGE, request);
        }
        if (status.is5xxServerError()) {
            log.error("Supplier deployment misconfiguration [{}]: {}", ex.getCode(), ex.getMessage());
            return build(status, ex.getCode(), CONFIGURATION_DEFECT_MESSAGE, request);
        }
        return build(status, ex.getCode(), ex.getMessage(), request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleBodyValidation(
            MethodArgumentNotValidException ex, HttpServletRequest request) {
        List<ApiError.FieldError> fieldErrors = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> new ApiError.FieldError(
                        fe.getField(), fe.getDefaultMessage() == null ? "invalid value" : fe.getDefaultMessage()))
                .toList();
        String correlationId = resolveCorrelationId(request);
        HttpHeaders headers = new HttpHeaders();
        headers.add(X_CORRELATION_ID, correlationId);
        return new ResponseEntity<>(
                ApiError.withFieldErrors(
                        "VALIDATION_ERROR",
                        "Request validation failed",
                        HttpStatus.BAD_REQUEST.value(),
                        Instant.now(clock).toString(),
                        correlationId,
                        fieldErrors),
                headers,
                HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiError> handleConstraintViolation(
            ConstraintViolationException ex, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", ex.getMessage(), request);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiError> handleMissingParameter(
            MissingServletRequestParameterException ex, HttpServletRequest request) {
        return build(
                HttpStatus.BAD_REQUEST,
                "MISSING_PARAMETER",
                "Required request parameter '" + ex.getParameterName() + "' is missing",
                request);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> handleTypeMismatch(
            MethodArgumentTypeMismatchException ex, HttpServletRequest request) {
        return build(
                HttpStatus.BAD_REQUEST,
                "INVALID_PARAMETER",
                "Parameter '" + ex.getName() + "' has an invalid value",
                request);
    }

    /**
     * Unparseable bodies are 400. The contract records in {@code service.model} validate in
     * their canonical constructors, so a well-formed JSON body carrying illegal values (blank
     * {@code supplierRef}, DELIVERY account without a location, …) surfaces here wrapped by
     * Jackson — unwrap it into a proper {@code VALIDATION_ERROR} with the constructor's
     * message instead of a generic parse failure.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadableBody(
            HttpMessageNotReadableException ex, HttpServletRequest request) {
        for (Throwable cause = ex.getCause(); cause != null; cause = cause.getCause()) {
            if (cause instanceof IllegalArgumentException || cause instanceof NullPointerException) {
                String message = cause.getMessage() == null ? "Request validation failed" : cause.getMessage();
                return build(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message, request);
            }
        }
        return build(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", "Request body could not be parsed", request);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> handleIllegalArgument(IllegalArgumentException ex, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", ex.getMessage(), request);
    }

    /** {@code @PreAuthorize} denials must not fall into the 500 catch-all below. */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> handleAccessDenied(AccessDeniedException ex, HttpServletRequest request) {
        return build(HttpStatus.FORBIDDEN, "FORBIDDEN", "You do not have permission to perform this action", request);
    }

    /**
     * Concurrent writes to the same {@code @Version}-ed configuration row are expected,
     * retryable conflicts — 409, never the 500 catch-all (mirrors pos-warranty).
     */
    @ExceptionHandler({ObjectOptimisticLockingFailureException.class, OptimisticLockException.class})
    public ResponseEntity<ApiError> handleOptimisticLockConflict(Exception ex, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, "CONFLICT", "Resource was updated concurrently. Please retry.", request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception in supplier controller", ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "An unexpected error occurred", request);
    }

    private ResponseEntity<ApiError> build(HttpStatus status, String code, String message, HttpServletRequest request) {
        String correlationId = resolveCorrelationId(request);
        HttpHeaders headers = new HttpHeaders();
        headers.add(X_CORRELATION_ID, correlationId);
        return new ResponseEntity<>(
                ApiError.of(code, message, status.value(), Instant.now(clock).toString(), correlationId),
                headers,
                status);
    }

    private String resolveCorrelationId(HttpServletRequest request) {
        if (request == null) {
            return UUIDv7Generator.generate().toString();
        }
        String header = request.getHeader(X_CORRELATION_ID);
        return (header != null && !header.isBlank())
                ? header
                : UUIDv7Generator.generate().toString();
    }
}

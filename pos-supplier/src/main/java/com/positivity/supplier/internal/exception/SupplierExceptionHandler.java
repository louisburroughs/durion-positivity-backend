package com.positivity.supplier.internal.exception;

import static java.util.Map.entry;

import com.positivity.shared.error.ApiError;
import com.positivity.shared.id.UUIDv7Generator;
import com.positivity.supplier.internal.audit.SupplierCorrelationContext;
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
     * What a caller is told when stored audit content cannot be decrypted.
     *
     * <p>Says nothing about <em>why</em>. The exception's own message names the envelope's key id and, for
     * an unconfigured key, the property that would fix it — useful in a server log, and both of them
     * details of the encryption scheme that no API response should hand out. The typed {@code code} still
     * distinguishes the three cases for anyone entitled to read the log.
     */
    public static final String PAYLOAD_UNREADABLE_MESSAGE =
            "The stored content of this exchange could not be read. Contact an administrator.";

    /**
     * Code → status table for {@link SupplierConfigurationException} (ADR-0017 §1/§2). Every code
     * declared on that exception must appear here; {@code SupplierConfigurationCodeMappingTest}
     * enforces that by reflection so a new code cannot silently inherit the 500 catch-all.
     */
    static final Map<String, HttpStatus> CONFIGURATION_STATUS = Map.ofEntries(
            // Caller-actionable configuration state.
            entry(SupplierConfigurationException.UNKNOWN_SUPPLIER, HttpStatus.NOT_FOUND),
            entry(SupplierConfigurationException.PROFILE_DISABLED, HttpStatus.CONFLICT),
            entry(SupplierConfigurationException.CAPABILITY_NOT_CONFIGURED, HttpStatus.CONFLICT),
            entry(SupplierConfigurationException.MISSING_BILLING_ACCOUNT, HttpStatus.CONFLICT),
            entry(SupplierConfigurationException.MISSING_DELIVERY_MAPPING, HttpStatus.CONFLICT),
            entry(SupplierConfigurationException.AUTH_CONFIG_MISSING, HttpStatus.CONFLICT),
            entry(SupplierConfigurationException.AUTH_CREDENTIALS_REJECTED, HttpStatus.CONFLICT),
            // Deployment defects: generic message, detail logged server-side only.
            entry(SupplierConfigurationException.SECRET_REFERENCE_INVALID, HttpStatus.INTERNAL_SERVER_ERROR),
            entry(SupplierConfigurationException.UNKNOWN_SECRET_SCHEME, HttpStatus.INTERNAL_SERVER_ERROR),
            entry(SupplierConfigurationException.SECRET_NOT_FOUND, HttpStatus.INTERNAL_SERVER_ERROR),
            entry(SupplierConfigurationException.YAML_BOOTSTRAP_INVALID, HttpStatus.INTERNAL_SERVER_ERROR),
            entry(SupplierConfigurationException.AUTH_TOKEN_RESPONSE_INVALID, HttpStatus.INTERNAL_SERVER_ERROR));

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

    /**
     * A vendor invoice window that could not be fetched or read (CAP-321 #1343).
     *
     * <p>422 rather than 500: the request was understood and the fault is at the vendor — it could
     * not be reached, or it refused the window. A caller can act on that, by narrowing the window
     * or waiting; a 500 tells them only that something broke here, which is the wrong place to
     * look.
     */
    /**
     * A fleet lookup that could not be answered.
     *
     * <p>422 rather than 502: the request was well-formed and the configuration is fine — the vendor
     * simply could not be reached or said something unreadable. An operator seeing this should try
     * again or check the vendor's status, not go and edit a profile.
     */
    /**
     * A marketing-catalogue import that could not be read.
     *
     * <p>422 rather than 502: the request was well-formed and the configuration is fine — the
     * catalogue could not be reached or answered unreadably. Distinguished from an empty catalogue
     * on purpose, because an importer that diffs would read "unreadable" as "everything withdrawn".
     */
    @ExceptionHandler(MktCatImportException.class)
    public ResponseEntity<ApiError> handleMktCatImportFailure(MktCatImportException ex, HttpServletRequest request) {
        return build(HttpStatus.UNPROCESSABLE_ENTITY, "SUPPLIER_MKTCAT_IMPORT_FAILED", ex.getMessage(), request);
    }

    @ExceptionHandler(FleetLookupException.class)
    public ResponseEntity<ApiError> handleFleetLookupFailure(FleetLookupException ex, HttpServletRequest request) {
        return build(HttpStatus.UNPROCESSABLE_ENTITY, "SUPPLIER_FLEET_LOOKUP_FAILED", ex.getMessage(), request);
    }

    @ExceptionHandler(InvoiceFetchException.class)
    public ResponseEntity<ApiError> handleInvoiceFetch(InvoiceFetchException ex, HttpServletRequest request) {
        return build(HttpStatus.UNPROCESSABLE_ENTITY, "SUPPLIER_INVOICE_FETCH_FAILED", ex.getMessage(), request);
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

    /**
     * Stored exchange-audit content that exists but cannot be decrypted (ADR-0050 §7).
     *
     * <p>Handled explicitly rather than left to the 500 catch-all, for three reasons:
     *
     * <ul>
     *   <li>The catch-all would return {@code INTERNAL_ERROR}, losing the distinction between an
     *       unconfigured key (an operator provisioning mistake, fixable) and a failed authentication tag
     *       (possible tampering, an incident). Those need different responses from a human.
     *   <li>The catch-all logs "Unhandled exception", which would frame a security-relevant integrity
     *       failure as a bug. {@code AUTHENTICATION_FAILED} gets its own log line saying what it may mean.
     *   <li>Once key rotation exists, this path is <em>reachable in normal operation</em> — a key retired
     *       without being carried into {@code previous-keys} makes every pre-rotation row unreadable. It is
     *       not an exotic branch.
     * </ul>
     *
     * <p>500 rather than a 4xx: the request was valid and the caller can do nothing about the outcome.
     * The caller-facing message is generic on purpose; see {@link #PAYLOAD_UNREADABLE_MESSAGE}.
     *
     * <p>This response is <strong>not</strong> evidence that the read went unrecorded. The read service
     * declares {@code noRollbackFor} on this exception precisely so the access row survives it.
     */
    @ExceptionHandler(PayloadUnreadableException.class)
    public ResponseEntity<ApiError> handlePayloadUnreadable(PayloadUnreadableException ex, HttpServletRequest request) {
        if (PayloadUnreadableException.AUTHENTICATION_FAILED.equals(ex.getCode())) {
            log.error(
                    "Exchange-audit payload FAILED AUTHENTICATION [{}]. The stored bytes or their bound header"
                            + " do not match the GCM tag: either the row was modified at rest or the key id maps"
                            + " to the wrong key. Treat as a potential integrity incident, not a routine error."
                            + " Detail: {}",
                    ex.getCode(),
                    ex.getMessage());
        } else {
            log.error("Exchange-audit payload is unreadable [{}]: {}", ex.getCode(), ex.getMessage());
        }
        return build(HttpStatus.INTERNAL_SERVER_ERROR, ex.getCode(), PAYLOAD_UNREADABLE_MESSAGE, request);
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

    /**
     * The ambient scope first, then the inbound header, then a fresh id. {@code SupplierCorrelationFilter}
     * opens a scope for every request, so in production the first branch always answers and the error
     * envelope's id matches the one the filter echoed on the response header. The header and generate
     * fallbacks remain for handler-only test slices where the filter is not in the chain.
     */
    private String resolveCorrelationId(HttpServletRequest request) {
        return SupplierCorrelationContext.current().orElseGet(() -> {
            String header = request == null ? null : request.getHeader(X_CORRELATION_ID);
            return (header != null && !header.isBlank())
                    ? header
                    : UUIDv7Generator.generate().toString();
        });
    }
}

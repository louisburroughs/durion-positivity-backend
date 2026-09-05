package com.positivity.securityservice.internal.config;

import com.positivity.securityservice.internal.dto.AuditLogEventRequest;
import com.positivity.securityservice.internal.exception.DuplicateRoleNameException;
import com.positivity.securityservice.internal.exception.DuplicateUsernameException;
import com.positivity.securityservice.internal.exception.InvalidRefreshTokenException;
import com.positivity.securityservice.internal.exception.NoRolesAssignedException;
import com.positivity.securityservice.internal.exception.PermissionNotFoundException;
import com.positivity.securityservice.internal.exception.RoleAssignmentNotFoundException;
import com.positivity.securityservice.internal.exception.RoleNotFoundException;
import com.positivity.securityservice.internal.exception.SecurityValidationException;
import com.positivity.securityservice.internal.exception.SelfRegistrationConflictException;
import com.positivity.securityservice.internal.exception.SelfRegistrationReviewCaseNotFoundException;
import com.positivity.securityservice.internal.exception.UserNotFoundException;
import com.positivity.shared.error.ApiError;
import com.positivity.shared.id.UUIDv7Generator;
import jakarta.persistence.EntityNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.authentication.AccountExpiredException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.CredentialsExpiredException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Global exception handler for security service.
 *
 * **Purpose:**
 * - Standardize error responses across all endpoints
 * - Include correlation ID in all error responses for request tracking
 * - Log exceptions for observability
 * - Map exceptions to appropriate HTTP status codes
 *
 * **Error Response Format (from BACKEND_CONTRACT_GUIDE.md):**
 * ```json
 * {
 * "code": "error_code",
 * "message": "Human-readable description",
 * "status": 400,
 * "timestamp": "2024-01-15T10:30:00Z",
 * "correlationId": "550e8400-e29b-41d4-a716-446655440000"
 * }
 * ```
 *
 * **Mapped Exceptions:**
 * - SecurityValidationException → 400 Bad Request (VALIDATION_ERROR) — this module's own
 * request/field-shape validation failures (ADR-0017 §1). Aligned onto the fleet-wide
 * VALIDATION_ERROR spelling in #1730; the type was introduced by #1694 and had no prior wire
 * contract to preserve, unlike pos-order's and pos-invoice's module-prefixed codes.
 * - Request-binding failures (MethodArgumentNotValidException and friends) keep the
 * pre-existing INVALID_REQUEST code — that one does have consumers, so #1730 left it alone and
 * recorded both spellings in docs/ERROR_ENVELOPE.md
 * - NoRolesAssignedException → 403 Forbidden (USER_HAS_NO_ROLES) — valid credentials or refresh
 * token, but the account currently holds no roles and so no effective permissions (ADR-0017 §2
 * question 1: a refusal about the caller's authorization, answered the same on login and refresh)
 * - InvalidRefreshTokenException → 401 Unauthorized
 * - LockedException → 401 Unauthorized
 * - DisabledException → 401 Unauthorized
 * - AccountExpiredException → 401 Unauthorized
 * - CredentialsExpiredException → 401 Unauthorized
 * - BadCredentialsException → 401 Unauthorized
 * - AuthorizationDeniedException → 403 Forbidden
 * - DuplicateRoleNameException → 409 Conflict
 * - IllegalStateException → 409 Conflict (overlapping assignment) or 400 Bad
 * Request
 * - Request-binding exceptions (MethodArgumentNotValidException, etc.) → 400
 * Bad Request
 * - RoleNotFoundException, UserNotFoundException,
 * RoleAssignmentNotFoundException, PermissionNotFoundException, EntityNotFoundException →
 * 404 Not Found
 * - ObjectOptimisticLockingFailureException → 409 Conflict (retry needed)
 *
 * <p>This class deliberately does NOT map bare {@code IllegalArgumentException} or a
 * catch-all {@code Exception} handler (issue #1694). {@code IllegalArgumentException} is not
 * exclusive to this module's own validation — Hibernate/JPA throw it for an invalid query and
 * {@code UUID.fromString} throws it on malformed stored data — so a blanket handler previously
 * reported such server-side defects back to the client as a {@code 400 INVALID_REQUEST}
 * carrying internal class names and query text, especially sensitive in an auth service. And a
 * blanket {@code @ExceptionHandler(Exception.class)} here pre-empted
 * {@code pos-web-common}'s platform-wide {@code GlobalApiExceptionHandler}
 * (Spring's {@code ExceptionHandlerExceptionResolver} picks the first advice with a matching
 * handler method), so the ADR-0056 §2 {@code DataIntegrityViolationException} mapping (409
 * unique/FK, 422 client-supplied not-null/check) never ran in this module. An unmapped
 * exception now falls through to that platform advice, which answers a generic, correlated
 * {@code 500 INTERNAL_ERROR} and logs the stack trace at ERROR.
 *
 * <p>Every response built by this advice carries the correlation id in both the {@link ApiError}
 * body and the {@code X-Correlation-Id} response header (ADR-0017 §4, issue #1729). The private
 * {@code respond} helper is the sole path that builds an {@link ApiError}, so a handler added
 * later cannot forget the header.
 *
 * @since 1.0
 */
@Slf4j
@ControllerAdvice
@RequiredArgsConstructor
public class GlobalExceptionHandler {
    private static final String CORRELATION_ID_HEADER = "X-Correlation-Id";

    private final Clock clock;
    private final ObjectProvider<AuditEventService> auditEventServiceProvider;

    /**
     * Handles RoleNotFoundException (role not found by ID or name).
     *
     * **HTTP Status:** 404 Not Found
     *
     * @param ex      the exception
     * @param request the web request
     * @return error response with 404 status and correlation ID
     */
    @ExceptionHandler(RoleNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ResponseEntity<ApiError> handleRoleNotFoundException(RoleNotFoundException ex, WebRequest request) {

        String correlationId = extractCorrelationId(request);
        log.warn("Role not found (correlationId={}): {}", correlationId, ex.getMessage());

        return respond(HttpStatus.NOT_FOUND, "ROLE_NOT_FOUND", ex.getMessage(), correlationId);
    }

    /**
     * Handles UserNotFoundException (user not found by ID or username).
     *
     * **HTTP Status:** 404 Not Found
     *
     * @param ex      the exception
     * @param request the web request
     * @return error response with 404 status and correlation ID
     */
    @ExceptionHandler(UserNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ResponseEntity<ApiError> handleUserNotFoundException(UserNotFoundException ex, WebRequest request) {

        String correlationId = extractCorrelationId(request);
        log.warn("User not found (correlationId={}): {}", correlationId, ex.getMessage());

        return respond(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", ex.getMessage(), correlationId);
    }

    /**
     * Handles RoleAssignmentNotFoundException (role assignment not found by ID).
     *
     * **HTTP Status:** 404 Not Found
     *
     * @param ex      the exception
     * @param request the web request
     * @return error response with 404 status and correlation ID
     */
    @ExceptionHandler(RoleAssignmentNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ResponseEntity<ApiError> handleRoleAssignmentNotFoundException(
            RoleAssignmentNotFoundException ex, WebRequest request) {

        String correlationId = extractCorrelationId(request);
        log.warn("Role assignment not found (correlationId={}): {}", correlationId, ex.getMessage());

        return respond(HttpStatus.NOT_FOUND, "ROLE_ASSIGNMENT_NOT_FOUND", ex.getMessage(), correlationId);
    }

    /**
     * Handles PermissionNotFoundException (permission not found by name).
     *
     * **HTTP Status:** 404 Not Found
     *
     * @param ex      the exception
     * @param request the web request
     * @return error response with 404 status and correlation ID
     */
    @ExceptionHandler(PermissionNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ResponseEntity<ApiError> handlePermissionNotFoundException(
            PermissionNotFoundException ex, WebRequest request) {

        String correlationId = extractCorrelationId(request);
        log.warn("Permission not found (correlationId={}): {}", correlationId, ex.getMessage());

        return respond(HttpStatus.NOT_FOUND, "PERMISSION_NOT_FOUND", ex.getMessage(), correlationId);
    }

    @ExceptionHandler(EntityNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ResponseEntity<ApiError> handleEntityNotFoundException(EntityNotFoundException ex, WebRequest request) {
        String correlationId = extractCorrelationId(request);
        log.warn("Entity not found (correlationId={}): {}", correlationId, ex.getMessage());
        return respond(HttpStatus.NOT_FOUND, "NOT_FOUND", ex.getMessage(), correlationId);
    }

    @ExceptionHandler(SelfRegistrationConflictException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ResponseEntity<ApiError> handleSelfRegistrationConflictException(
            SelfRegistrationConflictException ex, WebRequest request) {

        String correlationId = extractCorrelationId(request);
        log.warn("Self-registration conflict (correlationId={}): {}", correlationId, ex.getMessage());
        SelfRegistrationGuidance guidance = selfRegistrationGuidance(ex.getErrorCode());

        return respond(
                HttpStatus.CONFLICT,
                ex.getErrorCode(),
                ex.getMessage(),
                correlationId,
                ex.getReferenceId() == null ? null : ex.getReferenceId().toString(),
                guidance.nextAction(),
                guidance.supportAction());
    }

    @ExceptionHandler(SelfRegistrationReviewCaseNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ResponseEntity<ApiError> handleSelfRegistrationReviewCaseNotFoundException(
            SelfRegistrationReviewCaseNotFoundException ex, WebRequest request) {

        String correlationId = extractCorrelationId(request);
        log.warn("Self-registration review case not found (correlationId={}): {}", correlationId, ex.getMessage());

        return respond(HttpStatus.NOT_FOUND, "SELF_REGISTRATION_REVIEW_CASE_NOT_FOUND", ex.getMessage(), correlationId);
    }

    /**
     * Handles SecurityValidationException — this module's own genuine client input-validation
     * failures (blank/malformed field, unresolved role/user reference, malformed permission
     * key or bitset, invalid scope/location combination). See {@link SecurityValidationException}
     * for why bare {@code IllegalArgumentException} is not used for this (issue #1694).
     *
     * **HTTP Status:** 400 Bad Request (ADR-0017 §1)
     *
     * @param ex      the exception
     * @param request the web request
     * @return error response with 400 status and correlation ID
     */
    @ExceptionHandler(SecurityValidationException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ResponseEntity<ApiError> handleSecurityValidationException(
            SecurityValidationException ex, WebRequest request) {

        String correlationId = extractCorrelationId(request);
        // A SecurityValidationException carrying a logDetail has a deliberately generic message
        // because the real reason is information the caller must not receive (issue #1715): the
        // detail goes to the log keyed by the correlation id, never into the response body.
        // Logged at WARN, not ERROR, because this is a 4xx client outcome and every other
        // validation failure in this advice logs at WARN; ADR-0046 retains WARN at the same full
        // fidelity as ERROR, so the diagnostic value is unchanged.
        String logDetail = ex.getLogDetail();
        if (logDetail != null) {
            log.warn("Validation error (correlationId={}): {} — {}", correlationId, ex.getMessage(), logDetail);
        } else {
            log.warn("Validation error (correlationId={}): {}", correlationId, ex.getMessage());
        }

        return respond(
                HttpStatus.BAD_REQUEST,
                "VALIDATION_ERROR",
                ex.getMessage() != null ? ex.getMessage() : "Invalid request parameters",
                correlationId);
    }

    /**
     * Handles NoRolesAssignedException — valid credentials or a valid refresh token, but the
     * account currently has no roles assigned and so no effective permissions, so no non-empty
     * roles/authorities claim can be issued. Thrown from both credential login and refresh and
     * answered the same way on each. See {@link NoRolesAssignedException}.
     *
     * **HTTP Status:** 403 Forbidden (ADR-0017 §2 question 1 — a refusal about the caller's
     * authorization, not a malformed request; decided in #1725). The body carries a
     * {@code nextAction} hint telling the caller how to get the account back into service.
     *
     * @param ex      the exception
     * @param request the web request
     * @return error response with 403 status, a {@code nextAction} hint, and correlation ID
     */
    @ExceptionHandler(NoRolesAssignedException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public ResponseEntity<ApiError> handleNoRolesAssignedException(NoRolesAssignedException ex, WebRequest request) {

        String correlationId = extractCorrelationId(request);
        log.warn("No roles assigned (correlationId={}): {}", correlationId, ex.getMessage());

        return respond(
                HttpStatus.FORBIDDEN,
                "USER_HAS_NO_ROLES",
                ex.getMessage(),
                correlationId,
                null,
                "Ask an administrator to assign at least one role to this account, then sign in again",
                null);
    }

    @ExceptionHandler({
        MethodArgumentTypeMismatchException.class,
        MethodArgumentNotValidException.class,
        HttpMessageNotReadableException.class,
        MissingServletRequestParameterException.class
    })
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ResponseEntity<ApiError> handleBadRequestExceptions(Exception ex, WebRequest request) {
        String correlationId = extractCorrelationId(request);
        log.warn("Request binding/validation error (correlationId={}): {}", correlationId, ex.getMessage());
        if (ex instanceof MethodArgumentNotValidException mave) {
            mave.getBindingResult()
                    .getFieldErrors()
                    .forEach(fe -> log.warn(
                            "  Field validation error: field={} rejected={} message={}",
                            fe.getField(),
                            fe.getRejectedValue(),
                            fe.getDefaultMessage()));
        }

        return respond(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Invalid request parameters", correlationId);
    }

    @ExceptionHandler(AuthorizationDeniedException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public ResponseEntity<ApiError> handleAuthorizationDeniedException(
            AuthorizationDeniedException ex, WebRequest request, HttpServletRequest httpServletRequest) {
        String correlationId = extractCorrelationId(request);
        log.warn("Authorization denied (correlationId={}): {}", correlationId, ex.getMessage());

        String actorId = getCurrentUsername();
        String resourceUri = httpServletRequest != null ? httpServletRequest.getRequestURI() : "unknown";
        String deniedPermissions = ex.getMessage() != null ? ex.getMessage() : "unknown";
        try {
            AuditEventService auditEventService =
                    auditEventServiceProvider != null ? auditEventServiceProvider.getIfAvailable() : null;
            if (auditEventService != null) {
                auditEventService.createEvent(new AuditLogEventRequest(
                        "PermissionDenied",
                        actorId,
                        resourceUri,
                        "Authorization",
                        "",
                        "",
                        Map.of(
                                "message",
                                deniedPermissions,
                                "requestUri",
                                resourceUri,
                                "deniedPermissions",
                                deniedPermissions)));
            }
        } catch (Exception auditException) {
            log.warn(
                    "Failed to persist PermissionDenied audit event (correlationId={}): {}",
                    correlationId,
                    auditException.getMessage());
        }

        return respond(HttpStatus.FORBIDDEN, "FORBIDDEN", "Access denied", correlationId);
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiError> handleIllegalStateException(IllegalStateException ex, WebRequest request) {
        String correlationId = extractCorrelationId(request);
        String message = ex.getMessage() != null ? ex.getMessage() : "Invalid state";

        if (message.contains("Overlapping role assignment")) {
            log.warn("Role assignment overlap conflict (correlationId={}): {}", correlationId, message);
            return respond(HttpStatus.CONFLICT, "ROLE_ASSIGNMENT_CONFLICT", message, correlationId);
        }

        log.warn("Illegal state (correlationId={}): {}", correlationId, message);
        return respond(HttpStatus.BAD_REQUEST, "INVALID_STATE", message, correlationId);
    }

    /**
     * Handles ObjectOptimisticLockingFailureException (concurrency conflicts).
     *
     * **Typical Cause:**
     * - Multiple threads attempting to revoke the same token simultaneously
     * - Entity version mismatch during concurrent updates
     *
     * **Client Action:**
     * - Retry request with exponential backoff (recommended: 3 retries)
     * - Max retry delay: 400ms (100ms → 200ms → 400ms with 2x multiplier)
     *
     * **HTTP Status:** 409 Conflict
     *
     * @param ex      the exception
     * @param request the web request
     * @return error response with 409 status and correlation ID
     */
    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ResponseEntity<ApiError> handleOptimisticLockingFailure(
            ObjectOptimisticLockingFailureException ex, WebRequest request) {

        String correlationId = extractCorrelationId(request);
        log.warn(
                "Concurrency conflict (correlationId={}): Entity was modified concurrently. Retry with backoff.",
                correlationId);

        return respond(
                HttpStatus.CONFLICT,
                "CONCURRENCY_CONFLICT",
                "Token was modified concurrently. Please retry with exponential backoff.",
                correlationId);
    }

    /**
     * Handles DuplicateRoleNameException (case-insensitive role name uniqueness
     * violation).
     *
     * <p>
     * Story #62: role names must be unique regardless of case.
     *
     * **HTTP Status:** 409 Conflict
     */
    @ExceptionHandler(DuplicateRoleNameException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ResponseEntity<ApiError> handleDuplicateRoleNameException(
            DuplicateRoleNameException ex, WebRequest request) {

        String correlationId = extractCorrelationId(request);
        log.warn("Duplicate role name (correlationId={}): {}", correlationId, ex.getMessage());

        return respond(HttpStatus.CONFLICT, "DUPLICATE_ROLE_NAME", ex.getMessage(), correlationId);
    }

    /**
     * Handles DuplicateUsernameException (operator user provisioning against an
     * existing username) → 409 Conflict, matching self-registration's conflict
     * semantics for an already-taken account.
     */
    @ExceptionHandler(DuplicateUsernameException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ResponseEntity<ApiError> handleDuplicateUsernameException(
            DuplicateUsernameException ex, WebRequest request) {

        String correlationId = extractCorrelationId(request);
        log.warn("Duplicate username (correlationId={}): {}", correlationId, ex.getMessage());

        return respond(HttpStatus.CONFLICT, "USER_ALREADY_EXISTS", ex.getMessage(), correlationId);
    }

    /**
     * Handles InvalidRefreshTokenException (valid JWT but user no longer exists
     * or token not refreshable).
     *
     * <p>
     * <b>HTTP Status:</b> 401 Unauthorized
     *
     * @param ex      the exception
     * @param request the web request
     * @return error response with 401 status and correlation ID
     */
    @ExceptionHandler(InvalidRefreshTokenException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public ResponseEntity<ApiError> handleInvalidRefreshTokenException(
            InvalidRefreshTokenException ex, WebRequest request) {
        String correlationId = extractCorrelationId(request);
        log.warn("Invalid refresh token (correlationId={}): {}", correlationId, ex.getMessage());
        return respond(HttpStatus.UNAUTHORIZED, "INVALID_REFRESH_TOKEN", ex.getMessage(), correlationId);
    }

    @ExceptionHandler(LockedException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public ResponseEntity<ApiError> handleLockedException(LockedException ex, WebRequest request) {
        String correlationId = extractCorrelationId(request);
        log.warn("Authentication denied (correlationId={}): account is locked", correlationId);
        return respond(
                HttpStatus.UNAUTHORIZED,
                "ACCOUNT_LOCKED",
                "Account is temporarily locked due to repeated failed login attempts",
                correlationId);
    }

    @ExceptionHandler(DisabledException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public ResponseEntity<ApiError> handleDisabledException(DisabledException ex, WebRequest request) {
        String correlationId = extractCorrelationId(request);
        log.warn("Authentication denied (correlationId={}): account is disabled", correlationId);
        return respond(HttpStatus.UNAUTHORIZED, "ACCOUNT_DISABLED", "Account is disabled", correlationId);
    }

    @ExceptionHandler(AccountExpiredException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public ResponseEntity<ApiError> handleAccountExpiredException(AccountExpiredException ex, WebRequest request) {
        String correlationId = extractCorrelationId(request);
        log.warn("Authentication denied (correlationId={}): account has expired", correlationId);
        return respond(HttpStatus.UNAUTHORIZED, "ACCOUNT_EXPIRED", "Account has expired", correlationId);
    }

    @ExceptionHandler(CredentialsExpiredException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public ResponseEntity<ApiError> handleCredentialsExpiredException(
            CredentialsExpiredException ex, WebRequest request) {
        String correlationId = extractCorrelationId(request);
        log.warn("Authentication denied (correlationId={}): credentials have expired", correlationId);
        return respond(HttpStatus.UNAUTHORIZED, "CREDENTIALS_EXPIRED", "Credentials have expired", correlationId);
    }

    @ExceptionHandler(BadCredentialsException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public ResponseEntity<ApiError> handleBadCredentialsException(BadCredentialsException ex, WebRequest request) {
        String correlationId = extractCorrelationId(request);
        log.warn("Authentication failed (correlationId={}): invalid credentials", correlationId);
        return respond(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Invalid username or password", correlationId);
    }

    /**
     * Builds the standardized error response, carrying the correlation id in both the
     * {@link ApiError} body and the {@code X-Correlation-Id} response header (ADR-0017 §4). This
     * is the only path in this advice that builds an {@link ApiError}, so a handler added later
     * cannot forget the header.
     *
     * @param status        HTTP status for the response
     * @param code          error code for client processing
     * @param message       human-readable error message
     * @param correlationId request correlation ID for tracking
     * @return the response, with the correlation id header set and the body populated
     */
    private ResponseEntity<ApiError> respond(HttpStatus status, String code, String message, String correlationId) {
        return respond(status, code, message, correlationId, null, null, null);
    }

    private ResponseEntity<ApiError> respond(
            HttpStatus status,
            String code,
            String message,
            String correlationId,
            String referenceId,
            String nextAction,
            String supportAction) {
        return ResponseEntity.status(status)
                .header(CORRELATION_ID_HEADER, correlationId)
                .body(new ApiError(
                        code,
                        message,
                        status.value(),
                        Instant.now(clock).toString(),
                        correlationId,
                        null,
                        referenceId,
                        nextAction,
                        supportAction));
    }

    private SelfRegistrationGuidance selfRegistrationGuidance(String errorCode) {
        return switch (errorCode) {
            case "USER_ALREADY_EXISTS" ->
                new SelfRegistrationGuidance(
                        "Sign in with the existing account or use password recovery instead of registering again.",
                        "Confirm that the submitted username or derived email username already maps to an active user in pos-security-service. Preserve the existing account.");
            case "ACCOUNT_RECOVERY_REQUIRED" ->
                new SelfRegistrationGuidance(
                        "Use account recovery or reactivation instead of self-registration.",
                        "Locate the existing inactive or already-linked account, verify the person linkage, and recover or reactivate it rather than creating a second user.");
            case "PERSON_ALREADY_HAS_ACTIVE_USER" ->
                new SelfRegistrationGuidance(
                        "Use the already-linked account instead of creating a new one.",
                        "Review the resolved person's linked users in pos-people and pos-security-service. Maintain the 1:1 person-to-active-user rule.");
            case "USER_PERSON_LINK_CONFLICT" ->
                new SelfRegistrationGuidance(
                        "Retry later or contact support with the correlation ID.",
                        "Investigate user-person linkage consistency between pos-security-service and pos-people before retrying registration.");
            case "CRM_PERSON_CONFLICT" ->
                new SelfRegistrationGuidance(
                        "Do not retry self-registration. Contact support to review the existing customer or contact identity.",
                        "Review CRM person matches, people resolution output, and linked users before creating or linking any account.");
            case "IDEMPOTENCY_KEY_REUSED" ->
                new SelfRegistrationGuidance(
                        "Retry with the original request payload or generate a new idempotency key.",
                        "Confirm whether the original request already completed, then either reuse that payload or instruct the caller to submit a new key.");
            default ->
                new SelfRegistrationGuidance(
                        "Contact support with the correlation ID if the problem continues.",
                        "Review the self-registration correlation ID and downstream identity resolution logs.");
        };
    }

    private record SelfRegistrationGuidance(String nextAction, String supportAction) {}

    /**
     * Extracts correlation ID from request header or generates new one.
     *
     * **Priority:**
     * 1. X-Correlation-Id header (if present)
     * 2. Generate new UUID v7 (see {@link UUIDv7Generator})
     *
     * @param request the web request
     * @return correlation ID (never null)
     */
    private String extractCorrelationId(WebRequest request) {
        String correlationId = request.getHeader("X-Correlation-Id");
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = UUIDv7Generator.generate().toString();
        }
        return correlationId;
    }

    private String getCurrentUsername() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()) {
            return authentication.getName();
        }
        return "system";
    }
}

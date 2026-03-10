package com.positivity.securityservice.internal.config;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.context.request.WebRequest;

import com.positivity.securityservice.internal.dto.ErrorResponse;
import com.positivity.securityservice.internal.dto.AuditLogEventRequest;
import com.positivity.securityservice.internal.exception.DuplicateRoleNameException;
import com.positivity.securityservice.internal.exception.PermissionNotFoundException;
import com.positivity.securityservice.internal.exception.RoleAssignmentNotFoundException;
import com.positivity.securityservice.internal.exception.RoleNotFoundException;
import com.positivity.securityservice.internal.exception.UserNotFoundException;
import com.positivity.securityservice.service.AuditEventService;
import com.positivity.shared.id.UUIDv7Generator;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

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
 * - IllegalArgumentException → 400 Bad Request
 * - AuthorizationDeniedException → 403 Forbidden
 * - DuplicateRoleNameException → 409 Conflict
 * - IllegalStateException → 409 Conflict (overlapping assignment) or 400 Bad Request
 * - Request-binding exceptions (MethodArgumentNotValidException, etc.) → 400 Bad Request
 * - RoleNotFoundException, UserNotFoundException,
 * RoleAssignmentNotFoundException, PermissionNotFoundException → 404 Not Found
 * - ObjectOptimisticLockingFailureException → 409 Conflict (retry needed)
 * - Exception (catch-all) → 500 Internal Server Error
 * 
 * @since 1.0
 */
@Slf4j
@ControllerAdvice
@RequiredArgsConstructor
public class GlobalExceptionHandler {
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
        public ResponseEntity<ErrorResponse> handleRoleNotFoundException(
                        RoleNotFoundException ex,
                        WebRequest request) {

                String correlationId = extractCorrelationId(request);
                log.warn("Role not found (correlationId={}): {}", correlationId, ex.getMessage());

                return ResponseEntity
                                .status(HttpStatus.NOT_FOUND)
                                .body(errorResponse(
                                                "ROLE_NOT_FOUND",
                                                ex.getMessage(),
                                                HttpStatus.NOT_FOUND,
                                                correlationId));
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
        public ResponseEntity<ErrorResponse> handleUserNotFoundException(
                        UserNotFoundException ex,
                        WebRequest request) {

                String correlationId = extractCorrelationId(request);
                log.warn("User not found (correlationId={}): {}", correlationId, ex.getMessage());

                return ResponseEntity
                                .status(HttpStatus.NOT_FOUND)
                                .body(errorResponse(
                                                "USER_NOT_FOUND",
                                                ex.getMessage(),
                                                HttpStatus.NOT_FOUND,
                                                correlationId));
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
        public ResponseEntity<ErrorResponse> handleRoleAssignmentNotFoundException(
                        RoleAssignmentNotFoundException ex,
                        WebRequest request) {

                String correlationId = extractCorrelationId(request);
                log.warn("Role assignment not found (correlationId={}): {}", correlationId, ex.getMessage());

                return ResponseEntity
                                .status(HttpStatus.NOT_FOUND)
                                .body(errorResponse(
                                                "ROLE_ASSIGNMENT_NOT_FOUND",
                                                ex.getMessage(),
                                                HttpStatus.NOT_FOUND,
                                                correlationId));
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
        public ResponseEntity<ErrorResponse> handlePermissionNotFoundException(
                        PermissionNotFoundException ex,
                        WebRequest request) {

                String correlationId = extractCorrelationId(request);
                log.warn("Permission not found (correlationId={}): {}", correlationId, ex.getMessage());

                return ResponseEntity
                                .status(HttpStatus.NOT_FOUND)
                                .body(errorResponse(
                                                "PERMISSION_NOT_FOUND",
                                                ex.getMessage(),
                                                HttpStatus.NOT_FOUND,
                                                correlationId));
        }

        /**
         * Handles IllegalArgumentException (validation failures).
         * 
         * **Typical Causes:**
         * - Invalid username or roles format
         * - Blank or null required fields
         * - Invalid refresh token
         * 
         * **HTTP Status:** 400 Bad Request
         * 
         * @param ex      the exception
         * @param request the web request
         * @return error response with 400 status and correlation ID
         */
        @ExceptionHandler(IllegalArgumentException.class)
        @ResponseStatus(HttpStatus.BAD_REQUEST)
        public ResponseEntity<ErrorResponse> handleIllegalArgumentException(
                        IllegalArgumentException ex,
                        WebRequest request) {

                String correlationId = extractCorrelationId(request);
                log.warn("Validation error (correlationId={}): {}", correlationId, ex.getMessage());

                return ResponseEntity
                                .status(HttpStatus.BAD_REQUEST)
                                .body(errorResponse(
                                                "INVALID_REQUEST",
                                                ex.getMessage() != null ? ex.getMessage()
                                                                : "Invalid request parameters",
                                                HttpStatus.BAD_REQUEST,
                                                correlationId));
        }

        @ExceptionHandler({
                        MethodArgumentTypeMismatchException.class,
                        MethodArgumentNotValidException.class,
                        HttpMessageNotReadableException.class,
                        MissingServletRequestParameterException.class
        })
        @ResponseStatus(HttpStatus.BAD_REQUEST)
        public ResponseEntity<ErrorResponse> handleBadRequestExceptions(
                        Exception ex,
                        WebRequest request) {
                String correlationId = extractCorrelationId(request);
                log.warn("Request binding/validation error (correlationId={}): {}", correlationId, ex.getMessage());

                return ResponseEntity
                                .status(HttpStatus.BAD_REQUEST)
                                .body(errorResponse(
                                                "INVALID_REQUEST",
                                                "Invalid request parameters",
                                                HttpStatus.BAD_REQUEST,
                                                correlationId));
        }

        @ExceptionHandler(AuthorizationDeniedException.class)
        @ResponseStatus(HttpStatus.FORBIDDEN)
        public ResponseEntity<ErrorResponse> handleAuthorizationDeniedException(
                        AuthorizationDeniedException ex,
                        WebRequest request,
                        HttpServletRequest httpServletRequest) {
                String correlationId = extractCorrelationId(request);
                log.warn("Authorization denied (correlationId={}): {}", correlationId, ex.getMessage());

                String actorId = getCurrentUsername();
                String resourceUri = httpServletRequest != null ? httpServletRequest.getRequestURI() : "unknown";
                String deniedPermissions = ex.getMessage() != null ? ex.getMessage() : "unknown";
                try {
                        AuditEventService auditEventService = auditEventServiceProvider != null
                                        ? auditEventServiceProvider.getIfAvailable()
                                        : null;
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
                        log.warn("Failed to persist PermissionDenied audit event (correlationId={}): {}",
                                        correlationId,
                                        auditException.getMessage());
                }

                return ResponseEntity
                                .status(HttpStatus.FORBIDDEN)
                                .body(errorResponse(
                                                "FORBIDDEN",
                                                "Access denied",
                                                HttpStatus.FORBIDDEN,
                                                correlationId));
        }

        @ExceptionHandler(IllegalStateException.class)
        public ResponseEntity<ErrorResponse> handleIllegalStateException(
                        IllegalStateException ex,
                        WebRequest request) {
                String correlationId = extractCorrelationId(request);
                String message = ex.getMessage() != null ? ex.getMessage() : "Invalid state";

                if (message.contains("Overlapping role assignment")) {
                        log.warn("Role assignment overlap conflict (correlationId={}): {}", correlationId, message);
                        return ResponseEntity
                                        .status(HttpStatus.CONFLICT)
                                        .body(errorResponse(
                                                        "ROLE_ASSIGNMENT_CONFLICT",
                                                        message,
                                                        HttpStatus.CONFLICT,
                                                        correlationId));
                }

                log.warn("Illegal state (correlationId={}): {}", correlationId, message);
                return ResponseEntity
                                .status(HttpStatus.BAD_REQUEST)
                                .body(errorResponse(
                                                "INVALID_STATE",
                                                message,
                                                HttpStatus.BAD_REQUEST,
                                                correlationId));
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
        public ResponseEntity<ErrorResponse> handleOptimisticLockingFailure(
                        ObjectOptimisticLockingFailureException ex,
                        WebRequest request) {

                String correlationId = extractCorrelationId(request);
                log.warn("Concurrency conflict (correlationId={}): Entity was modified concurrently. Retry with backoff.",
                                correlationId);

                return ResponseEntity
                                .status(HttpStatus.CONFLICT)
                                .body(errorResponse(
                                                "CONCURRENCY_CONFLICT",
                                                "Token was modified concurrently. Please retry with exponential backoff.",
                                                HttpStatus.CONFLICT,
                                                correlationId));
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
        public ResponseEntity<ErrorResponse> handleDuplicateRoleNameException(
                        DuplicateRoleNameException ex,
                        WebRequest request) {

                String correlationId = extractCorrelationId(request);
                log.warn("Duplicate role name (correlationId={}): {}", correlationId, ex.getMessage());

                return ResponseEntity
                                .status(HttpStatus.CONFLICT)
                                .body(errorResponse(
                                                "DUPLICATE_ROLE_NAME",
                                                ex.getMessage(),
                                                HttpStatus.CONFLICT,
                                                correlationId));
        }

        /**
         * Handles generic exceptions (catch-all).
         * 
         * **HTTP Status:** 500 Internal Server Error
         * 
         * @param ex      the exception
         * @param request the web request
         * @return error response with 500 status and correlation ID
         */
        @ExceptionHandler(Exception.class)
        @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
        public ResponseEntity<ErrorResponse> handleGenericException(
                        Exception ex,
                        WebRequest request) {

                String correlationId = extractCorrelationId(request);
                log.error("Unhandled exception (correlationId={})", correlationId, ex);

                return ResponseEntity
                                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                                .body(errorResponse(
                                                "INTERNAL_SERVER_ERROR",
                                                "An unexpected error occurred. Please contact support with correlation ID: "
                                                                + correlationId,
                                                HttpStatus.INTERNAL_SERVER_ERROR,
                                                correlationId));
        }

        /**
         * Builds standardized error response object.
         * 
         * @param code          error code for client processing
         * @param message       human-readable error message
         * @param status        HTTP status for the response
         * @param correlationId request correlation ID for tracking
         * @return error response record
         */
        private ErrorResponse errorResponse(String code, String message, HttpStatus status, String correlationId) {
                return new ErrorResponse(code, message, status.value(), Instant.now(clock).toString(), correlationId);
        }

        /**
         * Extracts correlation ID from request header or generates new one.
         * 
         * **Priority:**
         * 1. X-Correlation-Id header (if present)
         * 2. Generate new UUID v4
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

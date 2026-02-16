package com.positivity.securityservice.internal.controller;

import com.positivity.securityservice.internal.dto.ErrorResponse;
import com.positivity.securityservice.internal.exception.PermissionNotFoundException;
import com.positivity.securityservice.internal.exception.RoleAssignmentNotFoundException;
import com.positivity.securityservice.internal.exception.RoleNotFoundException;
import com.positivity.securityservice.internal.exception.UserNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;

/**
 * Global exception handler for Security Service operations.
 * 
 * Maps domain exceptions to appropriate HTTP status codes using standard
 * ErrorResponse format:
 * - RoleNotFoundException → 404 Not Found
 * - UserNotFoundException → 404 Not Found
 * - RoleAssignmentNotFoundException → 404 Not Found
 * - PermissionNotFoundException → 404 Not Found
 * - IllegalArgumentException → 400 Bad Request (validation errors)
 * - IllegalStateException → 409 Conflict (business rule violations)
 */
@Slf4j
@RestControllerAdvice(basePackages = "com.positivity.securityservice.internal.controller")
public class SecurityServiceExceptionHandler {

    @ExceptionHandler(RoleNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleRoleNotFound(RoleNotFoundException ex) {
        log.warn("Role not found: {}", ex.getMessage());
        ErrorResponse body = new ErrorResponse(
                "ROLE_NOT_FOUND",
                ex.getMessage(),
                Instant.now().toString(),
                null);
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
    }

    @ExceptionHandler(UserNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleUserNotFound(UserNotFoundException ex) {
        log.warn("User not found: {}", ex.getMessage());
        ErrorResponse body = new ErrorResponse(
                "USER_NOT_FOUND",
                ex.getMessage(),
                Instant.now().toString(),
                null);
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
    }

    @ExceptionHandler(RoleAssignmentNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleRoleAssignmentNotFound(RoleAssignmentNotFoundException ex) {
        log.warn("Role assignment not found: {}", ex.getMessage());
        ErrorResponse body = new ErrorResponse(
                "ROLE_ASSIGNMENT_NOT_FOUND",
                ex.getMessage(),
                Instant.now().toString(),
                null);
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
    }

    @ExceptionHandler(PermissionNotFoundException.class)
    public ResponseEntity<ErrorResponse> handlePermissionNotFound(PermissionNotFoundException ex) {
        log.warn("Permission not found: {}", ex.getMessage());
        ErrorResponse body = new ErrorResponse(
                "PERMISSION_NOT_FOUND",
                ex.getMessage(),
                Instant.now().toString(),
                null);
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException ex) {
        log.warn("Validation error: {}", ex.getMessage());
        ErrorResponse body = new ErrorResponse(
                "VALIDATION_ERROR",
                ex.getMessage(),
                Instant.now().toString(),
                null);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ErrorResponse> handleIllegalState(IllegalStateException ex) {
        log.warn("Business rule violation: {}", ex.getMessage());
        ErrorResponse body = new ErrorResponse(
                "CONFLICT",
                ex.getMessage(),
                Instant.now().toString(),
                null);
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }
}

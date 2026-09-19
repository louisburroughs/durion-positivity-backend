package com.positivity.mcp.internal.controller;

import com.positivity.mcp.internal.exception.ConversationNotFoundException;
import com.positivity.mcp.internal.exception.MessageNotFoundException;
import com.positivity.shared.error.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * ADR-0017 status mapping for {@link McpConversationController} (#2073).
 *
 * <p>{@link ConversationNotFoundException} and {@link MessageNotFoundException} always answer
 * 404, never 403: an id belonging to another subject and one that never existed must be
 * indistinguishable, so a caller cannot use the status code to enumerate other subjects'
 * conversations/messages.
 */
@RestControllerAdvice(assignableTypes = McpConversationController.class)
class McpConversationExceptionHandler {

    private final Clock clock;

    McpConversationExceptionHandler(ObjectProvider<Clock> clockProvider) {
        this.clock = clockProvider.getIfAvailable(Clock::systemUTC);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        UUID correlationId = NltiCorrelationIdSupport.resolveFromRequest(request);
        List<ApiError.FieldError> fieldErrors = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> new ApiError.FieldError(
                        fe.getField(), fe.getDefaultMessage() != null ? fe.getDefaultMessage() : "Invalid value"))
                .toList();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .header(NltiCorrelationIdSupport.CORRELATION_ID_HEADER, correlationId.toString())
                .body(ApiError.withFieldErrors(
                        "VALIDATION_ERROR",
                        "Request validation failed",
                        HttpStatus.BAD_REQUEST.value(),
                        Instant.now(clock).toString(),
                        correlationId.toString(),
                        fieldErrors));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<ApiError> handleConstraintViolation(ConstraintViolationException ex, HttpServletRequest request) {
        UUID correlationId = NltiCorrelationIdSupport.resolveFromRequest(request);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .header(NltiCorrelationIdSupport.CORRELATION_ID_HEADER, correlationId.toString())
                .body(ApiError.of(
                        "VALIDATION_ERROR",
                        ex.getMessage(),
                        HttpStatus.BAD_REQUEST.value(),
                        Instant.now(clock).toString(),
                        correlationId.toString()));
    }

    @ExceptionHandler(ConversationNotFoundException.class)
    ResponseEntity<ApiError> handleConversationNotFound(ConversationNotFoundException ex, HttpServletRequest request) {
        UUID correlationId = NltiCorrelationIdSupport.resolveFromRequest(request);
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .header(NltiCorrelationIdSupport.CORRELATION_ID_HEADER, correlationId.toString())
                .body(ApiError.of(
                        "CONVERSATION_NOT_FOUND",
                        ex.getMessage(),
                        HttpStatus.NOT_FOUND.value(),
                        Instant.now(clock).toString(),
                        correlationId.toString()));
    }

    @ExceptionHandler(MessageNotFoundException.class)
    ResponseEntity<ApiError> handleMessageNotFound(MessageNotFoundException ex, HttpServletRequest request) {
        UUID correlationId = NltiCorrelationIdSupport.resolveFromRequest(request);
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .header(NltiCorrelationIdSupport.CORRELATION_ID_HEADER, correlationId.toString())
                .body(ApiError.of(
                        "MESSAGE_NOT_FOUND",
                        ex.getMessage(),
                        HttpStatus.NOT_FOUND.value(),
                        Instant.now(clock).toString(),
                        correlationId.toString()));
    }

    @ExceptionHandler(AuthenticationException.class)
    ResponseEntity<ApiError> handleAuthentication(AuthenticationException ex, HttpServletRequest request) {
        UUID correlationId = NltiCorrelationIdSupport.resolveFromRequest(request);
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .header(NltiCorrelationIdSupport.CORRELATION_ID_HEADER, correlationId.toString())
                .body(ApiError.of(
                        "UNAUTHORIZED",
                        "Authentication is required",
                        HttpStatus.UNAUTHORIZED.value(),
                        Instant.now(clock).toString(),
                        correlationId.toString()));
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ApiError> handleAccessDenied(AccessDeniedException ex, HttpServletRequest request) {
        UUID correlationId = NltiCorrelationIdSupport.resolveFromRequest(request);
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .header(NltiCorrelationIdSupport.CORRELATION_ID_HEADER, correlationId.toString())
                .body(ApiError.of(
                        "FORBIDDEN",
                        "Insufficient permissions",
                        HttpStatus.FORBIDDEN.value(),
                        Instant.now(clock).toString(),
                        correlationId.toString()));
    }
}

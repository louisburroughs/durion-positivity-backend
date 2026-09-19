package com.positivity.mcp.internal.controller;

import com.positivity.mcp.internal.exception.AudioTooLargeException;
import com.positivity.mcp.internal.exception.TranscriptionUnavailableException;
import com.positivity.mcp.internal.exception.UnintelligibleAudioException;
import com.positivity.mcp.internal.exception.UnsupportedAudioException;
import com.positivity.shared.error.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

/**
 * ADR-0017 status mapping for {@link McpTranscriptionController} (#2074), covering every
 * exception thrown from inside the controller's method — request-argument resolution and the
 * service call.
 *
 * <p>Deliberately does <strong>not</strong> handle {@link
 * org.springframework.web.multipart.MaxUploadSizeExceededException} or {@link
 * org.springframework.web.HttpMediaTypeNotSupportedException}: both are thrown by {@code
 * DispatcherServlet} before the handler method is resolved (multipart parsing and {@code
 * consumes} matching happen ahead of handler lookup), so a controller-scoped advice such as this
 * one — {@code assignableTypes = McpTranscriptionController.class} — never sees them; Spring only
 * consults advice beans with no type/package selector when there is no resolved handler to match
 * against. Those two are mapped by {@link McpTranscriptionPreDispatchExceptionHandler} instead.
 */
@RestControllerAdvice(assignableTypes = McpTranscriptionController.class)
class McpTranscriptionExceptionHandler {

    private final Clock clock;

    McpTranscriptionExceptionHandler(ObjectProvider<Clock> clockProvider) {
        this.clock = clockProvider.getIfAvailable(Clock::systemUTC);
    }

    @ExceptionHandler(AudioTooLargeException.class)
    ResponseEntity<ApiError> handleAudioTooLarge(AudioTooLargeException ex, HttpServletRequest request) {
        return respond(HttpStatus.PAYLOAD_TOO_LARGE, "AUDIO_TOO_LARGE", ex.getMessage(), request);
    }

    @ExceptionHandler(UnsupportedAudioException.class)
    ResponseEntity<ApiError> handleUnsupportedAudio(UnsupportedAudioException ex, HttpServletRequest request) {
        return respond(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_AUDIO", ex.getMessage(), request);
    }

    @ExceptionHandler(MissingServletRequestPartException.class)
    ResponseEntity<ApiError> handleMissingPart(MissingServletRequestPartException ex, HttpServletRequest request) {
        return respond(
                HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                "UNSUPPORTED_AUDIO",
                "Required part '" + ex.getRequestPartName() + "' is missing",
                request);
    }

    @ExceptionHandler(UnintelligibleAudioException.class)
    ResponseEntity<ApiError> handleUnintelligibleAudio(UnintelligibleAudioException ex, HttpServletRequest request) {
        return respond(HttpStatus.UNPROCESSABLE_CONTENT, "UNINTELLIGIBLE_AUDIO", ex.getMessage(), request);
    }

    /**
     * {@code Retry-After: 30} except when the provider is simply not configured, since retrying a
     * misconfigured server on any schedule cannot succeed. Discriminated by {@code getCause()}: the
     * client ({@code SpeechToTextClient}/{@code OpenAiSpeechToTextClient}) throws this exception
     * with no cause in exactly one place — "not configured" — and always with a cause (timeout,
     * IO failure, or the provider's own 5xx/429/401/403) for every transient case, per the Code
     * Review Agent's confirmation with the Client Coder.
     */
    @ExceptionHandler(TranscriptionUnavailableException.class)
    ResponseEntity<ApiError> handleUnavailable(TranscriptionUnavailableException ex, HttpServletRequest request) {
        UUID correlationId = NltiCorrelationIdSupport.resolveFromRequest(request);
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(NltiCorrelationIdSupport.CORRELATION_ID_HEADER, correlationId.toString());
        if (ex.getCause() != null) {
            builder = builder.header(HttpHeaders.RETRY_AFTER, "30");
        }
        return builder.body(ApiError.of(
                "TRANSCRIPTION_UNAVAILABLE",
                ex.getMessage(),
                HttpStatus.SERVICE_UNAVAILABLE.value(),
                Instant.now(clock).toString(),
                correlationId.toString()));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<ApiError> handleConstraintViolation(ConstraintViolationException ex, HttpServletRequest request) {
        return respond(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", ex.getMessage(), request);
    }

    @ExceptionHandler(AuthenticationException.class)
    ResponseEntity<ApiError> handleAuthentication(AuthenticationException ex, HttpServletRequest request) {
        return respond(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Authentication is required", request);
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ApiError> handleAccessDenied(AccessDeniedException ex, HttpServletRequest request) {
        return respond(HttpStatus.FORBIDDEN, "FORBIDDEN", "Insufficient permissions", request);
    }

    private ResponseEntity<ApiError> respond(
            HttpStatus status, String code, String message, HttpServletRequest request) {
        UUID correlationId = NltiCorrelationIdSupport.resolveFromRequest(request);
        return ResponseEntity.status(status)
                .header(NltiCorrelationIdSupport.CORRELATION_ID_HEADER, correlationId.toString())
                .body(ApiError.of(
                        code, message, status.value(), Instant.now(clock).toString(), correlationId.toString()));
    }
}

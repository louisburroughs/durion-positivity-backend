package com.positivity.mcp.internal.controller;

import com.positivity.mcp.internal.exception.LlmApiIdAlreadyExistsException;
import com.positivity.shared.error.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Instant;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Scoped error mapping for {@link LlmApiConfigController} (issue #1713).
 *
 * <p>This controller had no advice at all, so two outcomes its own {@code @Operation} prose
 * described — a duplicate {@code apiId} and an unknown configuration id — inherited
 * pos-web-common's platform fallback and answered {@code 500 INTERNAL_ERROR}. ADR-0056
 * guarantees an enveloped, correlated 500 as the <em>worst case</em>; it was never meant to be
 * the answer for outcomes the endpoint documents.
 *
 * <p>Mappings follow ADR-0017 §2: a collision against existing unique data is a 409, a missing
 * row is a 404. Deliberately narrow, mirroring {@link SystemPromptExceptionHandler} — anything
 * unmapped still reaches the platform catch-all as a generic, correlated 500 that echoes
 * nothing (ADR-0056 §1).
 */
@RestControllerAdvice(assignableTypes = LlmApiConfigController.class)
class LlmApiConfigExceptionHandler {

    private final Clock clock;

    LlmApiConfigExceptionHandler(ObjectProvider<Clock> clockProvider) {
        this.clock = clockProvider.getIfAvailable(Clock::systemUTC);
    }

    @ExceptionHandler(LlmApiIdAlreadyExistsException.class)
    ResponseEntity<ApiError> handleApiIdConflict(LlmApiIdAlreadyExistsException ex, HttpServletRequest request) {
        return respond(HttpStatus.CONFLICT, "LLM_API_ID_CONFLICT", ex.getMessage(), request);
    }

    @ExceptionHandler(NoSuchElementException.class)
    ResponseEntity<ApiError> handleNotFound(NoSuchElementException ex, HttpServletRequest request) {
        return respond(HttpStatus.NOT_FOUND, "LLM_API_NOT_FOUND", ex.getMessage(), request);
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

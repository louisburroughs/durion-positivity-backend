package com.positivity.image.internal.exception;

import com.positivity.shared.error.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Error responses for this module (CAP-324 #1257, docs/ERROR_ENVELOPE.md).
 *
 * <h2>Why this module suddenly needs one</h2>
 *
 * pos-image had no error handling because it had no writes: a read either found an image or answered
 * 404, and neither needed an envelope. The store endpoint can be sent a body that is not valid
 * base64, or one that decodes to nothing — both the caller's problem, and both of which would
 * otherwise have surfaced as a 500 while the contract advertised 400.
 *
 * <p>A 500 for a malformed request is worse than untidy. It tells the caller to retry an identical
 * request that will fail identically, and it puts a client error into whatever alerts on server
 * errors.
 *
 * <h2>Why this maps {@link ImageValidationException}, not bare {@code IllegalArgumentException}
 * (issue #1694)</h2>
 *
 * {@code IllegalArgumentException} is not exclusive to this module's own validation — Hibernate/
 * JPA throw it for an invalid query, {@code UUID.fromString} throws it on malformed stored data,
 * and a JPA attribute converter throws it on corrupt stored JSON. A handler that caught it
 * directly would report any of those server-side defects back to the client as a 400, leaking
 * internal class names and query text. {@link ImageValidationException} is thrown only where this
 * module validates its own input (see {@link ImageValidationException}'s own javadoc); an
 * unexpected bare {@code IllegalArgumentException} now falls through to {@code pos-web-common}'s
 * platform-wide {@code GlobalApiExceptionHandler}, which answers a generic, correlated 500
 * instead of echoing the exception's own text.
 */
@Slf4j
@RestControllerAdvice
@RequiredArgsConstructor
public class ImageExceptionHandler {

    private static final String X_CORRELATION_ID = "X-Correlation-Id";

    private final Clock clock;

    /**
     * A request this service cannot make sense of.
     *
     * <p>Logged at debug, not warn: a client sending a bad body is an ordinary event on an endpoint
     * that accepts uploads, and logging it at warn would turn routine misuse into noise that hides
     * real problems.
     */
    @ExceptionHandler(ImageValidationException.class)
    public ResponseEntity<ApiError> handleBadRequest(ImageValidationException ex, HttpServletRequest request) {
        log.debug("Rejecting image request: {}", ex.getMessage());
        return build(HttpStatus.BAD_REQUEST, "IMAGE_REQUEST_INVALID", ex.getMessage(), request);
    }

    /**
     * A record that points at content this service does not hold.
     *
     * <p>A broken row rather than a bad request, so it is a 500 and says so. Handled explicitly to
     * stop it being reported as a client error, which would send whoever hit it looking at their own
     * request instead of at the data.
     */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiError> handleBrokenState(IllegalStateException ex, HttpServletRequest request) {
        log.error("Image service is in an inconsistent state", ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "IMAGE_STATE_INVALID", ex.getMessage(), request);
    }

    private ResponseEntity<ApiError> build(HttpStatus status, String code, String message, HttpServletRequest request) {
        String correlationId = resolveCorrelationId(request);
        HttpHeaders headers = new HttpHeaders();
        headers.add(X_CORRELATION_ID, correlationId);
        return new ResponseEntity<>(
                ApiError.of(
                        code,
                        message == null ? status.getReasonPhrase() : message,
                        status.value(),
                        Instant.now(clock).toString(),
                        correlationId),
                headers,
                status);
    }

    private static String resolveCorrelationId(HttpServletRequest request) {
        String supplied = request.getHeader(X_CORRELATION_ID);
        return supplied == null || supplied.isBlank() ? UUID.randomUUID().toString() : supplied;
    }
}

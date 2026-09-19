package com.positivity.mcp.internal.controller;

import com.positivity.shared.error.ApiError;
import com.positivity.web.common.GlobalApiExceptionHandler;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;

/**
 * Maps the exceptions {@code DispatcherServlet} can throw for {@link
 * McpTranscriptionController#PATH} <em>before</em> a handler method is resolved (#2074):
 * multipart parsing (size limits, malformed boundaries, client aborts, part-count limits) and
 * {@code consumes} negotiation both run ahead of handler lookup, so the handler is {@code null} at
 * the point {@link MaxUploadSizeExceededException}, {@link HttpMediaTypeNotSupportedException}, or
 * a plain {@link MultipartException} is thrown. Spring's {@code ExceptionHandlerExceptionResolver}
 * only matches a null handler against {@code @ControllerAdvice} beans that declare no type
 * selector ({@code assignableTypes}/{@code basePackages}/{@code annotations}) — a {@link
 * McpTranscriptionExceptionHandler}-style scoped advice never sees these, which is why this class
 * is deliberately global instead.
 *
 * <p>To keep that globality from changing any other route's behavior (there is no other multipart
 * route in this module today, but a future one could add one), every handler here checks the
 * request path first: on any path other than {@link McpTranscriptionController#PATH} it delegates
 * to {@code com.positivity.web.common.GlobalApiExceptionHandler#handleUnhandled}, the platform's
 * own catch-all, obtained via {@link ObjectProvider} rather than a hard constructor dependency —
 * that bean is registered by an {@code @AutoConfiguration} that narrower Spring context slices (for
 * example {@code @WebMvcTest} on an unrelated controller) do not import, and a bare, unscoped
 * {@code @ControllerAdvice} such as this one is still instantiated in every such slice, so a hard
 * dependency on the bean would fail context startup for every other controller's slice test in this
 * module. When the bean is unavailable, a hardcoded fallback reproduces the same status/code/message
 * {@code GlobalApiExceptionHandler} would have produced — used only in that narrow test-slice case,
 * since production always has the bean.
 *
 * <p>{@code @Order} places this advice ahead of {@code GlobalApiExceptionHandler} (registered at
 * {@code Ordered.LOWEST_PRECEDENCE}) so it is consulted first for these exception types.
 */
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE - 100)
class McpTranscriptionPreDispatchExceptionHandler {

    private final Clock clock;
    private final ObjectProvider<GlobalApiExceptionHandler> fallbackProvider;

    McpTranscriptionPreDispatchExceptionHandler(
            ObjectProvider<Clock> clockProvider, ObjectProvider<GlobalApiExceptionHandler> fallbackProvider) {
        this.clock = clockProvider.getIfAvailable(Clock::systemUTC);
        this.fallbackProvider = fallbackProvider;
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<ApiError> handleMaxUploadSizeExceeded(
            MaxUploadSizeExceededException ex, HttpServletRequest request, HttpServletResponse response)
            throws Exception {
        if (!isTranscriptionRoute(request)) {
            return delegateOrFallback(
                    ex, request, response, HttpStatus.PAYLOAD_TOO_LARGE, "PAYLOAD_TOO_LARGE", "Request rejected");
        }
        return respond(
                HttpStatus.PAYLOAD_TOO_LARGE, "AUDIO_TOO_LARGE", "Clip exceeds the maximum upload size", request);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ApiError> handleUnsupportedMediaType(
            HttpMediaTypeNotSupportedException ex, HttpServletRequest request, HttpServletResponse response)
            throws Exception {
        if (!isTranscriptionRoute(request)) {
            return delegateOrFallback(
                    ex,
                    request,
                    response,
                    HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    "UNSUPPORTED_MEDIA_TYPE",
                    "Unsupported media type");
        }
        return respond(
                HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_AUDIO", "Request must be multipart/form-data", request);
    }

    /**
     * Everything else multipart resolution can throw before a handler is picked: a malformed
     * boundary, the client aborting mid-upload, or the container's own part-count limit. Handled
     * separately from {@link MaxUploadSizeExceededException} — its own {@code @ExceptionHandler}
     * on this same advice wins for that specific subtype by Spring's usual most-specific-match
     * rule, so there is no overlap.
     */
    @ExceptionHandler(MultipartException.class)
    ResponseEntity<ApiError> handleMalformedMultipart(
            MultipartException ex, HttpServletRequest request, HttpServletResponse response) throws Exception {
        if (!isTranscriptionRoute(request)) {
            // Matches GlobalApiExceptionHandler's catch-all for this type exactly: plain
            // MultipartException is neither an ErrorResponse nor @ResponseStatus-annotated, so it
            // falls through to the generic 500 INTERNAL_ERROR branch.
            return delegateOrFallback(
                    ex,
                    request,
                    response,
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "INTERNAL_ERROR",
                    "Unexpected error occurred");
        }
        return respond(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Malformed multipart request", request);
    }

    private ResponseEntity<ApiError> delegateOrFallback(
            Exception ex,
            HttpServletRequest request,
            HttpServletResponse response,
            HttpStatus fallbackStatus,
            String fallbackCode,
            String fallbackMessage)
            throws Exception {
        GlobalApiExceptionHandler fallback = fallbackProvider.getIfAvailable();
        if (fallback != null) {
            return fallback.handleUnhandled(ex, request, response);
        }
        return respond(fallbackStatus, fallbackCode, fallbackMessage, request);
    }

    private static boolean isTranscriptionRoute(HttpServletRequest request) {
        return McpTranscriptionController.PATH.equals(request.getRequestURI());
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

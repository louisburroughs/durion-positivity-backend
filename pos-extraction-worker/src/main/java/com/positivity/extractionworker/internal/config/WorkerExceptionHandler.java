package com.positivity.extractionworker.internal.config;

import com.positivity.extractionworker.internal.exception.FileTooLargeException;
import com.positivity.extractionworker.internal.exception.InvalidRequestException;
import com.positivity.extractionworker.internal.exception.PageNotRenderableException;
import com.positivity.extractionworker.internal.exception.WorkerBusyException;
import com.positivity.shared.error.ApiError;
import com.positivity.shared.id.UUIDv7Generator;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

/**
 * Maps the worker's outcomes onto the ADR-0017 envelope. Messages are fixed text: they never carry a file
 * name, file content, a library exception message or the secret (ADR-0072). Anything unexpected falls
 * through to {@code pos-web-common}'s {@code GlobalApiExceptionHandler}.
 */
@ControllerAdvice
@Slf4j
@RequiredArgsConstructor
public class WorkerExceptionHandler {

    private static final String X_CORRELATION_ID = "X-Correlation-Id";
    private static final String CODE_FILE_TOO_LARGE = "FILE_TOO_LARGE";
    private static final String CODE_WORKER_BUSY = "WORKER_BUSY";
    private static final String CODE_NOT_RENDERABLE = "PAGE_NOT_RENDERABLE";
    private static final String CODE_VALIDATION = "VALIDATION_FAILED";

    private final Clock clock;

    @ExceptionHandler(WorkerBusyException.class)
    public ResponseEntity<ApiError> handleBusy(
            WorkerBusyException ex, HttpServletRequest request, HttpServletResponse response) {
        response.setHeader(HttpHeaders.RETRY_AFTER, "5");
        return error(request, response, HttpStatus.SERVICE_UNAVAILABLE, CODE_WORKER_BUSY, ex.getMessage());
    }

    @ExceptionHandler({FileTooLargeException.class, MaxUploadSizeExceededException.class})
    public ResponseEntity<ApiError> handleTooLarge(
            Exception ex, HttpServletRequest request, HttpServletResponse response) {
        String message = ex instanceof FileTooLargeException ? ex.getMessage() : "The file is larger than the limit";
        return error(request, response, HttpStatus.PAYLOAD_TOO_LARGE, CODE_FILE_TOO_LARGE, message);
    }

    @ExceptionHandler(PageNotRenderableException.class)
    public ResponseEntity<ApiError> handleNotRenderable(
            PageNotRenderableException ex, HttpServletRequest request, HttpServletResponse response) {
        return error(request, response, HttpStatus.UNPROCESSABLE_ENTITY, CODE_NOT_RENDERABLE, ex.getMessage());
    }

    @ExceptionHandler(InvalidRequestException.class)
    public ResponseEntity<ApiError> handleInvalid(
            InvalidRequestException ex, HttpServletRequest request, HttpServletResponse response) {
        return validation(request, response, ex.getField(), ex.getMessage());
    }

    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<ApiError> handleMissingPart(
            MissingServletRequestPartException ex, HttpServletRequest request, HttpServletResponse response) {
        return validation(request, response, ex.getRequestPartName(), "This part is required");
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiError> handleMissingParameter(
            MissingServletRequestParameterException ex, HttpServletRequest request, HttpServletResponse response) {
        return validation(request, response, ex.getParameterName(), "This parameter is required");
    }

    private ResponseEntity<ApiError> validation(
            HttpServletRequest request, HttpServletResponse response, String field, String message) {
        String correlationId = correlationId(request);
        response.setHeader(X_CORRELATION_ID, correlationId);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiError.withFieldErrors(
                        CODE_VALIDATION,
                        "Request validation failed",
                        HttpStatus.BAD_REQUEST.value(),
                        Instant.now(clock).toString(),
                        correlationId,
                        List.of(new ApiError.FieldError(field, message))));
    }

    private ResponseEntity<ApiError> error(
            HttpServletRequest request, HttpServletResponse response, HttpStatus status, String code, String message) {
        String correlationId = correlationId(request);
        response.setHeader(X_CORRELATION_ID, correlationId);
        return ResponseEntity.status(status)
                .body(ApiError.of(code, message, status.value(), Instant.now(clock).toString(), correlationId));
    }

    private static String correlationId(HttpServletRequest request) {
        String inbound = request == null ? null : request.getHeader(X_CORRELATION_ID);
        return inbound == null || inbound.isBlank()
                ? UUIDv7Generator.generate().toString()
                : inbound.trim();
    }
}

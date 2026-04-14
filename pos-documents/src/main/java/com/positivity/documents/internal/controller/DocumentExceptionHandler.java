package com.positivity.documents.internal.controller;

import com.positivity.documents.internal.exception.RenderingException;
import com.positivity.documents.internal.exception.TemplateNotFoundException;
import com.positivity.documents.internal.exception.UnsupportedFormatException;
import com.positivity.shared.error.ApiError;
import com.positivity.shared.id.UUIDv7Generator;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(basePackages = "com.positivity.documents.internal.controller")
public class DocumentExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(DocumentExceptionHandler.class);
    private static final String X_CORRELATION_ID = "X-Correlation-Id";

    @ExceptionHandler(UnsupportedFormatException.class)
    public ResponseEntity<ApiError> handleUnsupportedFormat(UnsupportedFormatException ex, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, "UNSUPPORTED_FORMAT", ex.getMessage(), request);
    }

    @ExceptionHandler(TemplateNotFoundException.class)
    public ResponseEntity<ApiError> handleTemplateNotFound(TemplateNotFoundException ex, HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, "TEMPLATE_NOT_FOUND", ex.getMessage(), request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Request validation failed", request);
    }

    @ExceptionHandler(RenderingException.class)
    public ResponseEntity<ApiError> handleRendering(RenderingException ex, HttpServletRequest request) {
        if (ex.isMalformedInput()) {
            return build(HttpStatus.UNPROCESSABLE_CONTENT, "MALFORMED_INPUT", ex.getMessage(), request);
        }
        log.error("Document rendering failure", ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "RENDERING_ERROR", "Document rendering failed", request);
    }

    private ResponseEntity<ApiError> build(HttpStatus status, String code, String message, HttpServletRequest request) {
        String correlationId = resolveCorrelationId(request);
        HttpHeaders headers = new HttpHeaders();
        headers.add(X_CORRELATION_ID, correlationId);
        return new ResponseEntity<>(
                ApiError.of(code, message, status.value(), Instant.now().toString(), correlationId), headers, status);
    }

    private String resolveCorrelationId(HttpServletRequest request) {
        if (request == null) {
            return UUIDv7Generator.generate().toString();
        }
        String header = request.getHeader(X_CORRELATION_ID);
        return (header != null && !header.isBlank())
                ? header
                : UUIDv7Generator.generate().toString();
    }
}

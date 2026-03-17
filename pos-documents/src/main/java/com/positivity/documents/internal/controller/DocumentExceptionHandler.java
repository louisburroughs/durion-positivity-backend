package com.positivity.documents.internal.controller;

import com.positivity.shared.error.ApiError;
import com.positivity.documents.internal.exception.RenderingException;
import com.positivity.documents.internal.exception.TemplateNotFoundException;
import com.positivity.documents.internal.exception.UnsupportedFormatException;
import java.time.Instant;
import java.util.UUID;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(basePackages = "com.positivity.documents.internal.controller")
public class DocumentExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(DocumentExceptionHandler.class);

    private String resolveCorrelationId(HttpServletRequest request) {
        final String headerName = "X-Correlation-Id";
        String correlationId = request.getHeader(headerName);
        if (correlationId == null || correlationId.trim().isEmpty()) {
            correlationId = UUID.randomUUID().toString();
        }
        return correlationId;
    }

    @ExceptionHandler(UnsupportedFormatException.class)
    public ResponseEntity<ApiError> handleUnsupportedFormat(UnsupportedFormatException ex,
                                                            HttpServletRequest request) {
        String correlationId = resolveCorrelationId(request);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .header("X-Correlation-Id", correlationId)
                .body(ApiError.of("UNSUPPORTED_FORMAT", ex.getMessage(),
                        HttpStatus.BAD_REQUEST.value(), Instant.now().toString(), correlationId));
    }

    @ExceptionHandler(TemplateNotFoundException.class)
    public ResponseEntity<ApiError> handleTemplateNotFound(TemplateNotFoundException ex,
                                                           HttpServletRequest request) {
        String correlationId = resolveCorrelationId(request);
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .header("X-Correlation-Id", correlationId)
                .body(ApiError.of("TEMPLATE_NOT_FOUND", ex.getMessage(),
                        HttpStatus.NOT_FOUND.value(), Instant.now().toString(), correlationId));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex,
                                                     HttpServletRequest request) {
        String correlationId = resolveCorrelationId(request);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .header("X-Correlation-Id", correlationId)
                .body(ApiError.of("VALIDATION_ERROR", "Request validation failed",
                        HttpStatus.BAD_REQUEST.value(), Instant.now().toString(), correlationId));
    }

    @ExceptionHandler(RenderingException.class)
    public ResponseEntity<ApiError> handleRendering(RenderingException ex,
                                                    HttpServletRequest request) {
        String correlationId = resolveCorrelationId(request);
        if (ex.isMalformedInput()) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT)
                    .header("X-Correlation-Id", correlationId)
                    .body(ApiError.of("MALFORMED_INPUT", ex.getMessage(),
                            HttpStatus.UNPROCESSABLE_CONTENT.value(), Instant.now().toString(), correlationId));
        }
        log.error("Document rendering failure", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .header("X-Correlation-Id", correlationId)
                .body(ApiError.of("RENDERING_ERROR", "Document rendering failed",
                        HttpStatus.INTERNAL_SERVER_ERROR.value(), Instant.now().toString(), correlationId));
    }
}


package com.positivity.accounting.internal.controller;

import com.positivity.accounting.internal.exception.GLPostingException;
import com.positivity.shared.error.ApiError;
import java.time.Clock;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Exception handler for GL Posting failures.
 */
@RestControllerAdvice(basePackages = "com.positivity.accounting.internal.controller")
@RequiredArgsConstructor
public class GLPostingExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GLPostingExceptionHandler.class);
    private final Clock clock;

    @ExceptionHandler(GLPostingException.class)
    public ResponseEntity<ApiError> handleGLPostingException(GLPostingException ex) {
        log.error("GL Posting Exception: {}", ex.getMessage(), ex);
        return new ResponseEntity<>(
                ApiError.of(
                        "GL_POSTING_FAILED",
                        ex.getMessage(),
                        HttpStatus.CONFLICT.value(),
                        Instant.now(clock).toString(),
                        null),
                HttpStatus.CONFLICT);
    }
}

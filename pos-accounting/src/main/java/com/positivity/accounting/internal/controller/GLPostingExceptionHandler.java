package com.positivity.accounting.internal.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import java.time.Instant;

import com.positivity.accounting.internal.dto.ErrorResponse;
import com.positivity.accounting.internal.exception.GLPostingException;

/**
 * Exception handler for GL Posting failures.
 */
@RestControllerAdvice(basePackages = "com.positivity.accounting.internal.controller")
public class GLPostingExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GLPostingExceptionHandler.class);

    @ExceptionHandler(GLPostingException.class)
    public ResponseEntity<ErrorResponse> handleGLPostingException(GLPostingException ex) {
        log.error("GL Posting Exception: {}", ex.getMessage(), ex);

        ErrorResponse errorResponse = ErrorResponse.builder()
                .errorCode("GL_POSTING_FAILED")
                .message(ex.getMessage())
                .timestamp(Instant.now().toEpochMilli())
                .build();

        return new ResponseEntity<>(errorResponse, HttpStatus.CONFLICT);
    }
}

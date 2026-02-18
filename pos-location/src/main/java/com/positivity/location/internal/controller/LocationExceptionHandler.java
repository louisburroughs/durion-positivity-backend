package com.positivity.location.internal.controller;

import com.positivity.location.internal.exception.DuplicateLocationCodeException;
import com.positivity.location.internal.exception.GeographicalLocationNotFoundException;
import com.positivity.location.internal.exception.LocationNotFoundException;
import com.positivity.location.internal.exception.LocationTypeNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;

@RestControllerAdvice
public class LocationExceptionHandler {

    private static final String TIMESTAMP_PROPERTY = "timestamp";

    @ExceptionHandler(LocationNotFoundException.class)
    ProblemDetail handleLocationNotFound(LocationNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(LocationTypeNotFoundException.class)
    ProblemDetail handleLocationTypeNotFound(LocationTypeNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(GeographicalLocationNotFoundException.class)
    ProblemDetail handleGeographicalLocationNotFound(GeographicalLocationNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(DuplicateLocationCodeException.class)
    ProblemDetail handleDuplicateCode(DuplicateLocationCodeException ex) {
        return problem(HttpStatus.CONFLICT, ex.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail handleIllegalArgument(IllegalArgumentException ex) {
        return problem(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail handleValidation(MethodArgumentNotValidException ex) {
        return problem(HttpStatus.BAD_REQUEST, "Validation failed");
    }

    private ProblemDetail problem(HttpStatus status, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setProperty(TIMESTAMP_PROPERTY, Instant.now());
        return problem;
    }
}

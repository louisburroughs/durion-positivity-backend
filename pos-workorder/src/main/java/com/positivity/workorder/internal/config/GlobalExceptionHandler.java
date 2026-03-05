package com.positivity.workorder.internal.config;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.positivity.shared.id.UUIDv7Generator;
import com.positivity.workorder.internal.exception.BreakSegmentNotFoundException;
import com.positivity.workorder.internal.exception.TimeEntryNotFoundException;
import com.positivity.workorder.internal.exception.TimeEntryStateException;
import com.positivity.workorder.internal.exception.TravelSegmentConflictException;
import com.positivity.workorder.internal.exception.TravelSegmentNotFoundException;
import com.positivity.workorder.internal.exception.WorkSessionLockedException;
import com.positivity.workorder.internal.exception.WorkSessionNotFoundException;
import com.positivity.workorder.internal.exception.WorkSessionOverlapException;
import com.positivity.workorder.internal.exception.WorkSessionStateException;
import com.positivity.workorder.internal.exception.WorkorderNotFoundException;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;

@RestControllerAdvice
@RequiredArgsConstructor
public class GlobalExceptionHandler {

    private final Clock clock;
    private static final String X_CORRELATION_ID = "X-Correlation-Id";

    @ExceptionHandler(WorkorderNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleWorkorderNotFound(
            WorkorderNotFoundException ex,
            HttpServletRequest request) {
        return buildErrorResponse(HttpStatus.NOT_FOUND, "NOT_FOUND", ex.getMessage(), request);
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalState(
            IllegalStateException ex,
            HttpServletRequest request) {
        return buildErrorResponse(HttpStatus.CONFLICT, "CONFLICT", ex.getMessage(), request);
    }

    @ExceptionHandler(WorkSessionNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleWorkSessionNotFound(
            WorkSessionNotFoundException ex,
            HttpServletRequest request) {
        return buildErrorResponse(HttpStatus.NOT_FOUND, "WORK_SESSION_NOT_FOUND", ex.getMessage(), request);
    }

    @ExceptionHandler(BreakSegmentNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleBreakSegmentNotFound(
            BreakSegmentNotFoundException ex, HttpServletRequest request) {
        return buildErrorResponse(HttpStatus.NOT_FOUND, "BREAK_SEGMENT_NOT_FOUND", ex.getMessage(), request);
    }

    @ExceptionHandler(TravelSegmentNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleTravelSegmentNotFound(
            TravelSegmentNotFoundException ex, HttpServletRequest request) {
        return buildErrorResponse(HttpStatus.NOT_FOUND, "TRAVEL_SEGMENT_NOT_FOUND", ex.getMessage(), request);
    }

    @ExceptionHandler(TravelSegmentConflictException.class)
    public ResponseEntity<Map<String, Object>> handleTravelSegmentConflict(
            TravelSegmentConflictException ex, HttpServletRequest request) {
        return buildErrorResponse(HttpStatus.CONFLICT, "TRAVEL_SEGMENT_CONFLICT", ex.getMessage(), request);
    }

    @ExceptionHandler(TimeEntryNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleTimeEntryNotFound(
            TimeEntryNotFoundException ex, HttpServletRequest request) {
        return buildErrorResponse(HttpStatus.NOT_FOUND, "TIME_ENTRY_NOT_FOUND", ex.getMessage(), request);
    }

    @ExceptionHandler(TimeEntryStateException.class)
    public ResponseEntity<Map<String, Object>> handleTimeEntryState(
            TimeEntryStateException ex, HttpServletRequest request) {
        return buildErrorResponse(HttpStatus.CONFLICT, "TIME_ENTRY_INVALID_STATE", ex.getMessage(), request);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(
            IllegalArgumentException ex, HttpServletRequest request) {
        return buildErrorResponse(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", ex.getMessage(), request);
    }

    @ExceptionHandler({ WorkSessionOverlapException.class, WorkSessionStateException.class,
            WorkSessionLockedException.class })
    public ResponseEntity<Map<String, Object>> handleWorkSessionConflict(
            RuntimeException ex,
            HttpServletRequest request) {
        return buildErrorResponse(HttpStatus.CONFLICT, "CONFLICT", ex.getMessage(), request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidationErrors(MethodArgumentNotValidException ex,
            HttpServletRequest request) {
        List<Map<String, String>> fieldErrors = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> Map.of("field", fe.getField(),
                        "message", fe.getDefaultMessage() != null ? fe.getDefaultMessage() : "Invalid value"))
                .toList();
        ResponseEntity<Map<String, Object>> baseResponse = buildErrorResponse(
                HttpStatus.BAD_REQUEST,
                "VALIDATION_FAILED",
                "Request validation failed",
                request);
        Map<String, Object> body = new LinkedHashMap<>(baseResponse.getBody());
        body.put("fieldErrors", fieldErrors);
        return new ResponseEntity<>(body, baseResponse.getHeaders(), baseResponse.getStatusCode());
    }

    private ResponseEntity<Map<String, Object>> buildErrorResponse(
            HttpStatus status,
            String code,
            String message,
            HttpServletRequest request) {
        String correlationId = resolveCorrelationId(request);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("message", message);
        body.put("status", status.value());
        body.put("timestamp", Instant.now(clock).toString());
        body.put("correlationId", correlationId);

        HttpHeaders headers = new HttpHeaders();
        headers.add(X_CORRELATION_ID, correlationId);
        return new ResponseEntity<>(body, headers, status);
    }

    private String resolveCorrelationId(HttpServletRequest request) {
        String header = request.getHeader(X_CORRELATION_ID);
        return (header != null && !header.isBlank()) ? header : UUIDv7Generator.generate().toString();
    }
}

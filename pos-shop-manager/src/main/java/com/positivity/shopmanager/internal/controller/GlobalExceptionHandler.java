package com.positivity.shopmanager.internal.controller;

import com.positivity.shopmanager.internal.dto.ErrorResponse;
import com.positivity.shopmanager.internal.exception.AppointmentNotFoundException;
import com.positivity.shopmanager.internal.exception.AppointmentStateException;
import com.positivity.shopmanager.internal.exception.AppointmentValidationException;
import com.positivity.shopmanager.internal.exception.CrmCustomerNotFoundException;
import com.positivity.shopmanager.internal.exception.CrmUnavailableException;
import com.positivity.shopmanager.internal.exception.HrUnavailableException;
import com.positivity.shopmanager.internal.exception.CrmVehicleNotFoundException;
import com.positivity.shopmanager.internal.exception.LocationNotFoundException;
import com.positivity.shopmanager.internal.exception.ResourceNotFoundException;
import com.positivity.shopmanager.internal.exception.VehicleCustomerMismatchException;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

@ControllerAdvice
@RequiredArgsConstructor
public class GlobalExceptionHandler {

        private static final String CODE_CRM_UNAVAILABLE = "CRM_UNAVAILABLE";
        private static final String CODE_HR_UNAVAILABLE = "HR_UNAVAILABLE";
        private final Clock clock;

        @ExceptionHandler(CrmCustomerNotFoundException.class)
        public ResponseEntity<ErrorResponse> handleCustomerNotFound(
                        CrmCustomerNotFoundException exception,
                        HttpServletRequest request) {
                UUID correlationId = resolveCorrelationId(request);
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                                .body(error("CUSTOMER_NOT_FOUND", exception.getMessage(), correlationId));
        }

        @ExceptionHandler(CrmVehicleNotFoundException.class)
        public ResponseEntity<ErrorResponse> handleVehicleNotFound(
                        CrmVehicleNotFoundException exception,
                        HttpServletRequest request) {
                UUID correlationId = resolveCorrelationId(request);
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                                .body(error("VEHICLE_NOT_FOUND", exception.getMessage(), correlationId));
        }

        @ExceptionHandler(VehicleCustomerMismatchException.class)
        public ResponseEntity<ErrorResponse> handleVehicleCustomerMismatch(
                        VehicleCustomerMismatchException exception,
                        HttpServletRequest request) {
                UUID correlationId = resolveCorrelationId(request);
                return ResponseEntity.status(HttpStatus.CONFLICT)
                                .body(error("VEHICLE_CUSTOMER_MISMATCH", exception.getMessage(), correlationId));
        }

        @ExceptionHandler(AppointmentValidationException.class)
        public ResponseEntity<ErrorResponse> handleAppointmentValidation(
                        AppointmentValidationException exception,
                        HttpServletRequest request) {
                UUID correlationId = resolveCorrelationId(request);
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                                .body(error("VALIDATION_ERROR", exception.getMessage(), correlationId));
        }

        @ExceptionHandler(AppointmentStateException.class)
        public ResponseEntity<ErrorResponse> handleAppointmentState(
                        AppointmentStateException exception,
                        HttpServletRequest request) {
                UUID correlationId = resolveCorrelationId(request);
                return ResponseEntity.status(HttpStatus.CONFLICT)
                                .body(error("INVALID_APPOINTMENT_STATE", exception.getMessage(), correlationId));
        }

        @ExceptionHandler(AppointmentNotFoundException.class)
        public ResponseEntity<ErrorResponse> handleAppointmentNotFound(
                        AppointmentNotFoundException exception,
                        HttpServletRequest request) {
                UUID correlationId = resolveCorrelationId(request);
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                                .body(error("APPOINTMENT_NOT_FOUND", exception.getMessage(), correlationId));
        }

        @ExceptionHandler(LocationNotFoundException.class)
        public ResponseEntity<ErrorResponse> handleLocationNotFound(
                        LocationNotFoundException exception,
                        HttpServletRequest request) {
                UUID correlationId = resolveCorrelationId(request);
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                                .body(error("LOCATION_NOT_FOUND", exception.getMessage(), correlationId));
        }

        @ExceptionHandler(ResourceNotFoundException.class)
        public ResponseEntity<ErrorResponse> handleResourceNotFound(
                        ResourceNotFoundException exception,
                        HttpServletRequest request) {
                UUID correlationId = resolveCorrelationId(request);
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                                .body(error("RESOURCE_NOT_FOUND", exception.getMessage(), correlationId));
        }

        @ExceptionHandler(MethodArgumentNotValidException.class)
        public ResponseEntity<ErrorResponse> handleMethodArgumentNotValid(
                        MethodArgumentNotValidException exception,
                        HttpServletRequest request) {
                UUID correlationId = resolveCorrelationId(request);
                ErrorResponse response = error("VALIDATION_ERROR", "Request validation failed", correlationId);
                response.setFieldErrors(exception.getBindingResult().getFieldErrors().stream()
                                .collect(java.util.stream.Collectors.toMap(
                                                FieldError::getField,
                                                fieldError -> fieldError.getDefaultMessage() == null
                                                                ? "invalid value"
                                                                : fieldError.getDefaultMessage(),
                                                (existing, replacement) -> existing)));
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
        }

        @ExceptionHandler({ ResourceAccessException.class, RestClientException.class })
        public ResponseEntity<ErrorResponse> handleCrmUnavailable(Exception exception, HttpServletRequest request) {
                UUID correlationId = resolveCorrelationId(request);
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                                .body(error(CODE_CRM_UNAVAILABLE, "CRM service is unavailable", correlationId));
        }

        @ExceptionHandler(CrmUnavailableException.class)
        public ResponseEntity<ErrorResponse> handleCrmUnavailable(
                        CrmUnavailableException exception,
                        HttpServletRequest request) {
                UUID correlationId = resolveCorrelationId(request);
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                                .body(error(CODE_CRM_UNAVAILABLE, "CRM service is unavailable", correlationId));
        }

        @ExceptionHandler(HrUnavailableException.class)
        public ResponseEntity<ErrorResponse> handleHrUnavailable(
                        HrUnavailableException exception,
                        HttpServletRequest request) {
                UUID correlationId = resolveCorrelationId(request);
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                                .body(error(CODE_HR_UNAVAILABLE, "HR service is unavailable", correlationId));
        }

        @ExceptionHandler(UnsupportedOperationException.class)
        public ResponseEntity<ErrorResponse> handleNotImplemented(
                        UnsupportedOperationException exception,
                        HttpServletRequest request) {
                UUID correlationId = resolveCorrelationId(request);
                return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED)
                                .body(error("NOT_IMPLEMENTED", exception.getMessage(), correlationId));
        }

        private ErrorResponse error(String code, String message, UUID correlationId) {
                ErrorResponse response = new ErrorResponse();
                response.setCode(code);
                response.setMessage(message);
                response.setStatus(resolveStatus(code));
                response.setCorrelationId(correlationId.toString());
                response.setTimestamp(Instant.now(clock));
                response.setFieldErrors(Map.of());
                return response;
        }

        private UUID resolveCorrelationId(HttpServletRequest request) {
                String rawCorrelationId = Optional.ofNullable(request.getHeader("X-Correlation-Id"))
                                .map(String::trim)
                                .filter(header -> !header.isBlank())
                                .orElse(null);
                if (rawCorrelationId == null) {
                        return UUID.randomUUID();
                }
                try {
                        return UUID.fromString(rawCorrelationId);
                } catch (IllegalArgumentException ignored) {
                        return UUID.randomUUID();
                }
        }

        private int resolveStatus(String code) {
                return switch (code) {
                        case "CUSTOMER_NOT_FOUND", "VEHICLE_NOT_FOUND", "APPOINTMENT_NOT_FOUND", "LOCATION_NOT_FOUND",
                                        "RESOURCE_NOT_FOUND" ->
                                HttpStatus.NOT_FOUND.value();
                        case "VEHICLE_CUSTOMER_MISMATCH", "INVALID_APPOINTMENT_STATE" -> HttpStatus.CONFLICT.value();
                        case CODE_CRM_UNAVAILABLE, CODE_HR_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE.value();
                        case "NOT_IMPLEMENTED" -> HttpStatus.NOT_IMPLEMENTED.value();
                        default -> HttpStatus.BAD_REQUEST.value();
                };
        }
}

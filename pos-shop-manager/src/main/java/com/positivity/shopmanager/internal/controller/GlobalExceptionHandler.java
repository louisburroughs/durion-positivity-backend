package com.positivity.shopmanager.internal.controller;

import com.positivity.shared.error.ApiError;
import com.positivity.shared.id.UUIDv7Generator;
import com.positivity.shopmanager.internal.exception.AppointmentNotFoundException;
import com.positivity.shopmanager.internal.exception.AppointmentStateException;
import com.positivity.shopmanager.internal.exception.AppointmentValidationException;
import com.positivity.shopmanager.internal.exception.CrmCustomerNotFoundException;
import com.positivity.shopmanager.internal.exception.CrmUnavailableException;
import com.positivity.shopmanager.internal.exception.CrmVehicleNotFoundException;
import com.positivity.shopmanager.internal.exception.LocationNotFoundException;
import com.positivity.shopmanager.internal.exception.ResourceNotFoundException;
import com.positivity.shopmanager.internal.exception.ShopManagerValidationException;
import com.positivity.shopmanager.internal.exception.SourceNotEligibleException;
import com.positivity.shopmanager.internal.exception.VehicleCustomerMismatchException;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@ControllerAdvice
@RequiredArgsConstructor
public class GlobalExceptionHandler {

    private static final String CODE_CRM_UNAVAILABLE = "CRM_UNAVAILABLE";
    private static final String CODE_HR_UNAVAILABLE = "HR_UNAVAILABLE";
    private static final String CORRELATION_ID_HEADER = "X-Correlation-Id";
    private final Clock clock;

    @ExceptionHandler(CrmCustomerNotFoundException.class)
    public ResponseEntity<ApiError> handleCustomerNotFound(
            CrmCustomerNotFoundException exception, HttpServletRequest request) {
        UUID correlationId = resolveCorrelationId(request);
        return respond(HttpStatus.NOT_FOUND, "CUSTOMER_NOT_FOUND", exception.getMessage(), correlationId);
    }

    @ExceptionHandler(CrmVehicleNotFoundException.class)
    public ResponseEntity<ApiError> handleVehicleNotFound(
            CrmVehicleNotFoundException exception, HttpServletRequest request) {
        UUID correlationId = resolveCorrelationId(request);
        return respond(HttpStatus.NOT_FOUND, "VEHICLE_NOT_FOUND", exception.getMessage(), correlationId);
    }

    @ExceptionHandler(VehicleCustomerMismatchException.class)
    public ResponseEntity<ApiError> handleVehicleCustomerMismatch(
            VehicleCustomerMismatchException exception, HttpServletRequest request) {
        UUID correlationId = resolveCorrelationId(request);
        return respond(HttpStatus.CONFLICT, "VEHICLE_CUSTOMER_MISMATCH", exception.getMessage(), correlationId);
    }

    @ExceptionHandler(AppointmentValidationException.class)
    public ResponseEntity<ApiError> handleAppointmentValidation(
            AppointmentValidationException exception, HttpServletRequest request) {
        UUID correlationId = resolveCorrelationId(request);
        return respond(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", exception.getMessage(), correlationId);
    }

    @ExceptionHandler(SourceNotEligibleException.class)
    public ResponseEntity<ApiError> handleSourceNotEligible(
            SourceNotEligibleException exception, HttpServletRequest request) {
        UUID correlationId = resolveCorrelationId(request);
        return respond(
                HttpStatus.UNPROCESSABLE_CONTENT,
                exception.getErrorCode() != null ? exception.getErrorCode() : "SOURCE_NOT_ELIGIBLE",
                exception.getMessage(),
                correlationId);
    }

    @ExceptionHandler(AppointmentStateException.class)
    public ResponseEntity<ApiError> handleAppointmentState(
            AppointmentStateException exception, HttpServletRequest request) {
        UUID correlationId = resolveCorrelationId(request);
        return respond(HttpStatus.CONFLICT, "INVALID_APPOINTMENT_STATE", exception.getMessage(), correlationId);
    }

    @ExceptionHandler(AppointmentNotFoundException.class)
    public ResponseEntity<ApiError> handleAppointmentNotFound(
            AppointmentNotFoundException exception, HttpServletRequest request) {
        UUID correlationId = resolveCorrelationId(request);
        return respond(HttpStatus.NOT_FOUND, "APPOINTMENT_NOT_FOUND", exception.getMessage(), correlationId);
    }

    @ExceptionHandler(LocationNotFoundException.class)
    public ResponseEntity<ApiError> handleLocationNotFound(
            LocationNotFoundException exception, HttpServletRequest request) {
        UUID correlationId = resolveCorrelationId(request);
        return respond(HttpStatus.NOT_FOUND, "LOCATION_NOT_FOUND", exception.getMessage(), correlationId);
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiError> handleResourceNotFound(
            ResourceNotFoundException exception, HttpServletRequest request) {
        UUID correlationId = resolveCorrelationId(request);
        return respond(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", exception.getMessage(), correlationId);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleMethodArgumentNotValid(
            MethodArgumentNotValidException exception, HttpServletRequest request) {
        UUID correlationId = resolveCorrelationId(request);
        List<ApiError.FieldError> fieldErrors = exception.getBindingResult().getFieldErrors().stream()
                .map(this::toFieldError)
                .toList();
        ApiError body = ApiError.withFieldErrors(
                "VALIDATION_ERROR",
                "Request validation failed",
                HttpStatus.BAD_REQUEST.value(),
                Instant.now(clock).toString(),
                correlationId.toString(),
                fieldErrors);
        return respond(HttpStatus.BAD_REQUEST, body, correlationId);
    }

    @ExceptionHandler({ResourceAccessException.class, RestClientException.class})
    public ResponseEntity<ApiError> handleCrmUnavailable(Exception exception, HttpServletRequest request) {
        UUID correlationId = resolveCorrelationId(request);
        return respond(
                HttpStatus.SERVICE_UNAVAILABLE, CODE_CRM_UNAVAILABLE, "CRM service is unavailable", correlationId);
    }

    @ExceptionHandler(CrmUnavailableException.class)
    public ResponseEntity<ApiError> handleCrmUnavailable(
            CrmUnavailableException exception, HttpServletRequest request) {
        UUID correlationId = resolveCorrelationId(request);
        return respond(
                HttpStatus.SERVICE_UNAVAILABLE, CODE_CRM_UNAVAILABLE, "CRM service is unavailable", correlationId);
    }

    @ExceptionHandler(UnsupportedOperationException.class)
    public ResponseEntity<ApiError> handleNotImplemented(
            UnsupportedOperationException exception, HttpServletRequest request) {
        UUID correlationId = resolveCorrelationId(request);
        return respond(HttpStatus.NOT_IMPLEMENTED, "NOT_IMPLEMENTED", exception.getMessage(), correlationId);
    }

    /**
     * A path or query parameter that will not convert — a malformed UUID, a date that is not
     * {@code yyyy-MM-dd} (ADR-0017, ADR-0038). Spring's own handling answers 400 with an empty
     * body; this maps it onto the same {@link ApiError} envelope every other error in this module
     * uses, so a caller never has to parse two shapes.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> handleTypeMismatch(
            MethodArgumentTypeMismatchException exception, HttpServletRequest request) {
        UUID correlationId = resolveCorrelationId(request);
        return respond(
                HttpStatus.BAD_REQUEST,
                "INVALID_REQUEST",
                "Parameter '" + exception.getName() + "' is not a valid value",
                correlationId);
    }

    /**
     * Genuine client input-validation failures raised by this module's own services/controllers
     * (see {@link ShopManagerValidationException}). This class deliberately does NOT map bare
     * {@code IllegalArgumentException} (issue #1686): that type is not exclusive to this
     * module's validation — Hibernate/JPA throw it for an invalid query and {@code
     * UUID.fromString} throws it on malformed data, and catching it here previously turned a
     * server-side persistence defect (issue #1679) into a client-facing 400 that also leaked
     * internal class names and JPQL. An unexpected {@code IllegalArgumentException} now falls
     * through to {@code pos-web-common}'s platform-wide {@code GlobalApiExceptionHandler}
     * fallback, which answers a generic, correlated 500 instead of echoing the exception text.
     */
    @ExceptionHandler(ShopManagerValidationException.class)
    public ResponseEntity<ApiError> handleShopManagerValidation(
            ShopManagerValidationException exception, HttpServletRequest request) {
        UUID correlationId = resolveCorrelationId(request);
        return respond(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", exception.getMessage(), correlationId);
    }

    /**
     * Builds the standardized error response, carrying the correlation id in both the {@link
     * ApiError} body and the {@code X-Correlation-Id} response header (ADR-0017 §4, issue #1729).
     * This overload and {@link #respond(HttpStatus, ApiError, UUID)} are the only paths in this
     * advice that build a {@link ResponseEntity}, so a handler added later cannot forget the
     * header.
     */
    private ResponseEntity<ApiError> respond(HttpStatus status, String code, String message, UUID correlationId) {
        return respond(status, error(code, message, correlationId), correlationId);
    }

    private ResponseEntity<ApiError> respond(HttpStatus status, ApiError body, UUID correlationId) {
        return ResponseEntity.status(status)
                .header(CORRELATION_ID_HEADER, correlationId.toString())
                .body(body);
    }

    private ApiError error(String code, String message, UUID correlationId) {
        return ApiError.of(
                code, message, resolveStatus(code), Instant.now(clock).toString(), correlationId.toString());
    }

    private ApiError.FieldError toFieldError(FieldError fieldError) {
        return new ApiError.FieldError(
                fieldError.getField(), Objects.requireNonNullElse(fieldError.getDefaultMessage(), "invalid value"));
    }

    private UUID resolveCorrelationId(HttpServletRequest request) {
        String rawCorrelationId = Optional.ofNullable(request.getHeader("X-Correlation-Id"))
                .map(String::trim)
                .filter(header -> !header.isBlank())
                .orElse(null);
        if (rawCorrelationId == null) {
            return UUIDv7Generator.generate();
        }
        try {
            return UUID.fromString(rawCorrelationId);
        } catch (IllegalArgumentException ignored) {
            return UUIDv7Generator.generate();
        }
    }

    private int resolveStatus(String code) {
        return switch (code) {
            case "CUSTOMER_NOT_FOUND",
                    "VEHICLE_NOT_FOUND",
                    "APPOINTMENT_NOT_FOUND",
                    "LOCATION_NOT_FOUND",
                    "RESOURCE_NOT_FOUND" -> HttpStatus.NOT_FOUND.value();
            case "VEHICLE_CUSTOMER_MISMATCH", "INVALID_APPOINTMENT_STATE" -> HttpStatus.CONFLICT.value();
            case CODE_CRM_UNAVAILABLE, CODE_HR_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE.value();
            case "NOT_IMPLEMENTED" -> HttpStatus.NOT_IMPLEMENTED.value();
            case "SOURCE_NOT_ELIGIBLE", "ESTIMATE_NOT_ELIGIBLE", "WORKORDER_NOT_ELIGIBLE" ->
                HttpStatus.UNPROCESSABLE_CONTENT.value();
            default -> HttpStatus.BAD_REQUEST.value();
        };
    }
}

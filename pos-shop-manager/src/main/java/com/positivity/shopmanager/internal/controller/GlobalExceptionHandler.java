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
import com.positivity.shopmanager.internal.exception.MechanicReplicationPendingException;
import com.positivity.shopmanager.internal.exception.ResourceNotFoundException;
import com.positivity.shopmanager.internal.exception.ShopManagerValidationException;
import com.positivity.shopmanager.internal.exception.SourceNotEligibleException;
import com.positivity.shopmanager.internal.exception.VehicleCustomerMismatchException;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Value;
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
public class GlobalExceptionHandler {

    private static final String CODE_CRM_UNAVAILABLE = "CRM_UNAVAILABLE";
    private static final String CODE_HR_UNAVAILABLE = "HR_UNAVAILABLE";
    private static final String CODE_MECHANIC_REPLICATION_PENDING = "MECHANIC_REPLICATION_PENDING";
    private static final String CORRELATION_ID_HEADER = "X-Correlation-Id";
    private static final String RETRY_AFTER_HEADER = "Retry-After";

    private final Clock clock;

    /**
     * Seconds a caller is asked to wait before retrying a replication-pending write: the same
     * window the service itself waits out, rounded up, never less than one second.
     *
     * <p>Derived rather than fixed, because each retry costs the server another full wait on a
     * servlet thread. A client honouring a `Retry-After` shorter than that window would keep a
     * thread near-permanently occupied on an id that never resolves.
     */
    private final String retryAfterSeconds;

    public GlobalExceptionHandler(
            @NonNull Clock clock,
            @Value("${pos.shop-manager.mechanic-replication-wait:PT5S}") @NonNull Duration replicationWait) {
        this.clock = clock;
        this.retryAfterSeconds = Long.toString(Math.max(1L, (long) Math.ceil(replicationWait.toMillis() / 1000d)));
    }

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

    /**
     * A write naming a person whose mechanic row this service has not received yet (#1987).
     *
     * <p>{@code 503} with a {@code Retry-After}, never {@code 404}: the person may well exist and
     * the row may well arrive, so the caller is told to ask again rather than told the mechanic
     * does not exist. A person this service does hold assignment history for, none of it making
     * them a technician, still answers {@code 404} — that one is an answer, not a wait.
     */
    @ExceptionHandler(MechanicReplicationPendingException.class)
    public ResponseEntity<ApiError> handleMechanicReplicationPending(
            MechanicReplicationPendingException exception, HttpServletRequest request) {
        UUID correlationId = resolveCorrelationId(request);
        ApiError body = ApiError.guided(
                CODE_MECHANIC_REPLICATION_PENDING,
                exception.getMessage(),
                HttpStatus.SERVICE_UNAVAILABLE.value(),
                Instant.now(clock).toString(),
                correlationId.toString(),
                exception.getPersonId(),
                "Retry the request; the mechanic record is created from a staffing assignment this service "
                        + "receives asynchronously.",
                "If it never resolves, check that the person holds an active TECHNICIAN staffing assignment "
                        + "and that this service is consuming people.events.v1.");
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(CORRELATION_ID_HEADER, correlationId.toString())
                .header(RETRY_AFTER_HEADER, retryAfterSeconds)
                .body(body);
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
            case CODE_CRM_UNAVAILABLE, CODE_HR_UNAVAILABLE, CODE_MECHANIC_REPLICATION_PENDING ->
                HttpStatus.SERVICE_UNAVAILABLE.value();
            case "NOT_IMPLEMENTED" -> HttpStatus.NOT_IMPLEMENTED.value();
            case "SOURCE_NOT_ELIGIBLE", "ESTIMATE_NOT_ELIGIBLE", "WORKORDER_NOT_ELIGIBLE" ->
                HttpStatus.UNPROCESSABLE_CONTENT.value();
            default -> HttpStatus.BAD_REQUEST.value();
        };
    }
}

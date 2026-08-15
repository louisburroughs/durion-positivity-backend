package com.positivity.shopmanager.internal.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.shared.error.ApiError;
import com.positivity.shopmanager.internal.exception.AppointmentNotFoundException;
import com.positivity.shopmanager.internal.exception.AppointmentStateException;
import com.positivity.shopmanager.internal.exception.AppointmentValidationException;
import com.positivity.shopmanager.internal.exception.CrmCustomerNotFoundException;
import com.positivity.shopmanager.internal.exception.CrmUnavailableException;
import com.positivity.shopmanager.internal.exception.CrmVehicleNotFoundException;
import com.positivity.shopmanager.internal.exception.LocationNotFoundException;
import com.positivity.shopmanager.internal.exception.ResourceNotFoundException;
import com.positivity.shopmanager.internal.exception.SourceNotEligibleException;
import com.positivity.shopmanager.internal.exception.VehicleCustomerMismatchException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;

/**
 * Shop-manager error envelope (docs/ERROR_ENVELOPE.md): each domain failure gets its own code and
 * status, and the correlation id comes from {@code X-Correlation-Id} when the caller sends a
 * usable one.
 */
@DisplayName("GlobalExceptionHandler")
class GlobalExceptionHandlerTest {

    private static final Instant NOW = Instant.parse("2026-03-01T12:00:00Z");
    private static final UUID CUSTOMER_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3fcc01");
    private static final UUID VEHICLE_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3fcc02");
    private static final UUID APPOINTMENT_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3fcc03");
    private static final UUID LOCATION_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3fcc04");
    private static final UUID CORRELATION_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3fcc05");

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler(Clock.fixed(NOW, ZoneOffset.UTC));

    private static MockHttpServletRequest request() {
        return new MockHttpServletRequest("GET", "/v1/shop-manager/appointments");
    }

    private static MockHttpServletRequest requestWithCorrelationId(String value) {
        MockHttpServletRequest request = request();
        request.addHeader("X-Correlation-Id", value);
        return request;
    }

    private static void assertEnvelope(ResponseEntity<ApiError> response, HttpStatus status, String code) {
        assertThat(response.getStatusCode()).isEqualTo(status);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo(code);
        assertThat(response.getBody().status()).isEqualTo(status.value());
        assertThat(response.getBody().timestamp()).isEqualTo(NOW.toString());
        assertThat(response.getBody().correlationId()).isNotBlank();
    }

    @Test
    void mapsAMissingCrmCustomerToNotFound() {
        assertEnvelope(
                handler.handleCustomerNotFound(new CrmCustomerNotFoundException(CUSTOMER_ID), request()),
                HttpStatus.NOT_FOUND,
                "CUSTOMER_NOT_FOUND");
    }

    @Test
    void mapsAMissingCrmVehicleToNotFound() {
        assertEnvelope(
                handler.handleVehicleNotFound(new CrmVehicleNotFoundException(VEHICLE_ID), request()),
                HttpStatus.NOT_FOUND,
                "VEHICLE_NOT_FOUND");
    }

    @Test
    void mapsAVehicleThatBelongsToSomebodyElseToConflict() {
        assertEnvelope(
                handler.handleVehicleCustomerMismatch(
                        new VehicleCustomerMismatchException(VEHICLE_ID, CUSTOMER_ID), request()),
                HttpStatus.CONFLICT,
                "VEHICLE_CUSTOMER_MISMATCH");
    }

    @Test
    void mapsAnAppointmentValidationFailureToBadRequest() {
        ResponseEntity<ApiError> response = handler.handleAppointmentValidation(
                new AppointmentValidationException("slot is in the past"), request());

        assertEnvelope(response, HttpStatus.BAD_REQUEST, "VALIDATION_ERROR");
        assertThat(response.getBody().message()).isEqualTo("slot is in the past");
    }

    @Test
    void mapsAnIneligibleSourceToUnprocessableContentKeepingItsOwnCode() {
        ResponseEntity<ApiError> response = handler.handleSourceNotEligible(
                new SourceNotEligibleException(
                        "ESTIMATE_NOT_ELIGIBLE", "Estimate is not approved", "ESTIMATE", "EST-1", "DRAFT"),
                request());

        assertEnvelope(response, HttpStatus.UNPROCESSABLE_CONTENT, "ESTIMATE_NOT_ELIGIBLE");
    }

    @Test
    void fallsBackToAGenericCodeWhenTheIneligibleSourceCarriesNone() {
        ResponseEntity<ApiError> response = handler.handleSourceNotEligible(
                new SourceNotEligibleException(null, "Not eligible", "ESTIMATE", "EST-1", "DRAFT"), request());

        assertEnvelope(response, HttpStatus.UNPROCESSABLE_CONTENT, "SOURCE_NOT_ELIGIBLE");
    }

    @Test
    void mapsAnIllegalAppointmentTransitionToConflict() {
        assertEnvelope(
                handler.handleAppointmentState(new AppointmentStateException("already checked in"), request()),
                HttpStatus.CONFLICT,
                "INVALID_APPOINTMENT_STATE");
    }

    @Test
    void mapsAMissingAppointmentToNotFound() {
        assertEnvelope(
                handler.handleAppointmentNotFound(new AppointmentNotFoundException(APPOINTMENT_ID), request()),
                HttpStatus.NOT_FOUND,
                "APPOINTMENT_NOT_FOUND");
    }

    @Test
    void mapsAMissingLocationToNotFound() {
        assertEnvelope(
                handler.handleLocationNotFound(new LocationNotFoundException(LOCATION_ID), request()),
                HttpStatus.NOT_FOUND,
                "LOCATION_NOT_FOUND");
    }

    @Test
    void mapsAMissingResourceToNotFound() {
        assertEnvelope(
                handler.handleResourceNotFound(new ResourceNotFoundException("BAY-3"), request()),
                HttpStatus.NOT_FOUND,
                "RESOURCE_NOT_FOUND");
    }

    @Test
    void reportsEveryRejectedFieldOnAValidationFailure() throws Exception {
        BindingResult bindingResult = new BeanPropertyBindingResult(new Object(), "request");
        bindingResult.rejectValue(null, "NotNull", "startAt is required");
        bindingResult.addError(new org.springframework.validation.FieldError(
                "request", "locationId", null, false, null, null, "locationId is required"));
        bindingResult.addError(
                new org.springframework.validation.FieldError("request", "customerId", null, false, null, null, null));

        ResponseEntity<ApiError> response = handler.handleMethodArgumentNotValid(
                new MethodArgumentNotValidException(methodParameter(), bindingResult), request());

        assertEnvelope(response, HttpStatus.BAD_REQUEST, "VALIDATION_ERROR");
        assertThat(response.getBody().message()).isEqualTo("Request validation failed");
        assertThat(response.getBody().fieldErrors())
                .extracting(ApiError.FieldError::field)
                .contains("locationId", "customerId");
        // A rejection with no message still names the field rather than dropping it.
        assertThat(response.getBody().fieldErrors())
                .extracting(ApiError.FieldError::message)
                .contains("invalid value");
    }

    @Test
    void mapsAnUnreachableCrmToServiceUnavailable() {
        assertEnvelope(
                handler.handleCrmUnavailable(new ResourceAccessException("connect timed out"), request()),
                HttpStatus.SERVICE_UNAVAILABLE,
                "CRM_UNAVAILABLE");
        assertEnvelope(
                handler.handleCrmUnavailable(new RestClientException("502"), request()),
                HttpStatus.SERVICE_UNAVAILABLE,
                "CRM_UNAVAILABLE");
    }

    @Test
    void mapsTheDomainCrmUnavailableExceptionToServiceUnavailable() {
        ResponseEntity<ApiError> response = handler.handleCrmUnavailable(
                new CrmUnavailableException("crm down", new IllegalStateException("boom")), request());

        assertEnvelope(response, HttpStatus.SERVICE_UNAVAILABLE, "CRM_UNAVAILABLE");
        // The upstream failure detail is not echoed to the caller.
        assertThat(response.getBody().message()).isEqualTo("CRM service is unavailable");
    }

    @Test
    void mapsAnUnimplementedOperationToNotImplemented() {
        assertEnvelope(
                handler.handleNotImplemented(new UnsupportedOperationException("not yet"), request()),
                HttpStatus.NOT_IMPLEMENTED,
                "NOT_IMPLEMENTED");
    }

    @Test
    void mapsAnIllegalArgumentToBadRequest() {
        assertEnvelope(
                handler.handleIllegalArgument(new IllegalArgumentException("bad id"), request()),
                HttpStatus.BAD_REQUEST,
                "INVALID_REQUEST");
    }

    @Test
    void keepsACallerSuppliedCorrelationId() {
        ResponseEntity<ApiError> response = handler.handleAppointmentNotFound(
                new AppointmentNotFoundException(APPOINTMENT_ID),
                requestWithCorrelationId("  " + CORRELATION_ID + "  "));

        assertThat(response.getBody().correlationId()).isEqualTo(CORRELATION_ID.toString());
    }

    @Test
    void mintsACorrelationIdWhenTheCallerSendsAnUnusableOne() {
        ResponseEntity<ApiError> malformed = handler.handleAppointmentNotFound(
                new AppointmentNotFoundException(APPOINTMENT_ID), requestWithCorrelationId("not-a-uuid"));
        ResponseEntity<ApiError> blank = handler.handleAppointmentNotFound(
                new AppointmentNotFoundException(APPOINTMENT_ID), requestWithCorrelationId("   "));

        assertThat(malformed.getBody().correlationId()).isNotBlank().isNotEqualTo("not-a-uuid");
        assertThat(blank.getBody().correlationId()).isNotBlank();
    }

    /** Any real method parameter works: the handler only reads the binding result. */
    private static MethodParameter methodParameter() throws NoSuchMethodException {
        return new MethodParameter(GlobalExceptionHandlerTest.class.getDeclaredMethod("sample", String.class), 0);
    }

    @SuppressWarnings("unused")
    private static void sample(String value) {
        // Signature-only holder for MethodParameter.
    }
}

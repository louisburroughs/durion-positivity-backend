package com.positivity.location.internal.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.location.internal.exception.DuplicateResourceException;
import com.positivity.location.internal.exception.InvalidFieldException;
import com.positivity.shared.error.ApiError;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

/**
 * Pins how {@link LocationGlobalExceptionHandler} renders the module's
 * {@link ResponseStatusException}s: the ApiError envelope (ADR-0017 §3, #1720) with a machine-code
 * reason as {@code code}, and the correlation id in both the {@code X-Correlation-Id} header and the
 * body (ADR-0017 §4, #1729).
 */
@DisplayName("LocationGlobalExceptionHandler ApiError rendering (ADR-0017 §3/§4)")
class LocationGlobalExceptionHandlerTest {

    private static final Instant NOW = Instant.parse("2026-09-19T12:00:00Z");

    private final LocationGlobalExceptionHandler handler =
            new LocationGlobalExceptionHandler(Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    @DisplayName("a machine-code reason becomes the code; the inbound correlation id is echoed")
    void machineCodeReasonBecomesCode() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/locations/a/parents/a");
        request.addHeader(LocationGlobalExceptionHandler.X_CORRELATION_ID, "corr-location-409");

        ResponseEntity<ApiError> response = handler.handleResponseStatus(
                new ResponseStatusException(HttpStatus.CONFLICT, "CYCLE_DETECTED"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getHeaders().getFirst(LocationGlobalExceptionHandler.X_CORRELATION_ID))
                .isEqualTo("corr-location-409");
        ApiError body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.code()).isEqualTo("CYCLE_DETECTED");
        assertThat(body.message()).isEqualTo("Request conflicts with the current state of the resource");
        assertThat(body.status()).isEqualTo(409);
        assertThat(body.timestamp()).isEqualTo(NOW.toString());
        assertThat(body.correlationId()).isEqualTo("corr-location-409");
    }

    @Test
    @DisplayName("a free-text reason becomes the message under a status code")
    void freeTextReasonBecomesMessage() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/locations");

        ResponseEntity<ApiError> response = handler.handleResponseStatus(
                new ResponseStatusException(HttpStatus.BAD_REQUEST, "type is required"), request);

        ApiError body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.code()).isEqualTo("VALIDATION_ERROR");
        assertThat(body.message()).isEqualTo("type is required");
        assertThat(body.status()).isEqualTo(400);
    }

    @Test
    @DisplayName("no reason: status code and default message; a fresh id, identical in header and body")
    void noReasonGeneratesCorrelationId() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/locations/missing");

        ResponseEntity<ApiError> response =
                handler.handleResponseStatus(new ResponseStatusException(HttpStatus.NOT_FOUND), request);

        String header = response.getHeaders().getFirst(LocationGlobalExceptionHandler.X_CORRELATION_ID);
        assertThat(header).isNotBlank();
        ApiError body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.code()).isEqualTo("NOT_FOUND");
        assertThat(body.message()).isEqualTo("Requested resource was not found");
        assertThat(body.correlationId()).isEqualTo(header);
    }

    @Test
    @DisplayName("a blank inbound X-Correlation-Id is replaced by a generated one")
    void blankInboundCorrelationIdIsReplaced() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/locations/missing");
        request.addHeader(LocationGlobalExceptionHandler.X_CORRELATION_ID, "   ");

        ResponseEntity<ApiError> response = handler.handleResponseStatus(
                new ResponseStatusException(HttpStatus.NOT_FOUND, "LOCATION_NOT_FOUND"), request);

        String header = response.getHeaders().getFirst(LocationGlobalExceptionHandler.X_CORRELATION_ID);
        assertThat(header).isNotBlank().isNotEqualTo("   ");
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().correlationId()).isEqualTo(header);
    }

    @Test
    @DisplayName("#2252 - an InvalidFieldException answers its code with the field in fieldErrors")
    void invalidFieldRendersFieldErrors() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/mobile-units");
        request.addHeader(LocationGlobalExceptionHandler.X_CORRELATION_ID, "corr-field-422");

        ResponseEntity<ApiError> response = handler.handleInvalidField(
                InvalidFieldException.unknownReference(
                        "LOCATION_NOT_FOUND",
                        "baseLocationId",
                        "baseLocationId does not reference an existing location"),
                request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getHeaders().getFirst(LocationGlobalExceptionHandler.X_CORRELATION_ID))
                .isEqualTo("corr-field-422");
        ApiError body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.code()).isEqualTo("LOCATION_NOT_FOUND");
        assertThat(body.status()).isEqualTo(422);
        assertThat(body.correlationId()).isEqualTo("corr-field-422");
        assertThat(body.fieldErrors()).singleElement().satisfies(error -> {
            assertThat(error.field()).isEqualTo("baseLocationId");
            assertThat(error.message()).isEqualTo("baseLocationId does not reference an existing location");
        });
    }

    @Test
    @DisplayName("#2252 - a DuplicateResourceException keeps its machine code instead of a bare CONFLICT")
    void duplicateResourceKeepsItsCode() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/mobile-units");

        ResponseEntity<ApiError> response =
                handler.handleDuplicateResource(new DuplicateResourceException("MOBILE_UNIT_NAME_TAKEN"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        ApiError body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.code()).isEqualTo("MOBILE_UNIT_NAME_TAKEN");
        assertThat(body.message()).isEqualTo("Request conflicts with the current state of the resource");
        assertThat(body.status()).isEqualTo(409);
    }

    @Test
    @DisplayName("#2252 - a DuplicateResourceException without a machine code falls back to CONFLICT")
    void duplicateResourceWithoutCodeFallsBack() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/mobile-units");

        ResponseEntity<ApiError> response =
                handler.handleDuplicateResource(new DuplicateResourceException("name taken"), request);

        ApiError body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.code()).isEqualTo("CONFLICT");
        assertThat(body.message()).doesNotContain("name taken");
    }
}

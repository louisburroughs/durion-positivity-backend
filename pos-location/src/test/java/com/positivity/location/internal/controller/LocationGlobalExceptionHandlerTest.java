package com.positivity.location.internal.controller;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.server.ResponseStatusException;

/**
 * Proves the inherited Spring MVC handlers of {@link LocationGlobalExceptionHandler} answer with
 * the correlation id in both the {@code X-Correlation-Id} header and the ProblemDetail body
 * (ADR-0017 §4, issue #1729) while keeping the RFC 9457 body shape the module documents.
 */
@DisplayName("LocationGlobalExceptionHandler X-Correlation-Id header (ADR-0017 §4, #1729)")
class LocationGlobalExceptionHandlerTest {

    private final LocationGlobalExceptionHandler handler = new LocationGlobalExceptionHandler();

    @Test
    @DisplayName("echoes the inbound X-Correlation-Id in header and ProblemDetail body")
    void echoesInboundCorrelationId() throws Exception {
        MockHttpServletRequest servletRequest = new MockHttpServletRequest("POST", "/v1/locations");
        servletRequest.addHeader(LocationGlobalExceptionHandler.X_CORRELATION_ID, "corr-location-405");

        ResponseEntity<Object> response = handler.handleException(
                new HttpRequestMethodNotSupportedException("POST"), new ServletWebRequest(servletRequest));

        assertThat(response).isNotNull();
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(response.getHeaders().getFirst(LocationGlobalExceptionHandler.X_CORRELATION_ID))
                .isEqualTo("corr-location-405");
        assertThat(response.getBody()).isInstanceOf(ProblemDetail.class);
        ProblemDetail problem = (ProblemDetail) response.getBody();
        assertThat(problem.getProperties()).containsEntry("correlationId", "corr-location-405");
    }

    @Test
    @DisplayName("generates a non-blank id, identical in header and body, when none is inbound")
    void generatesCorrelationIdWhenAbsent() throws Exception {
        MockHttpServletRequest servletRequest = new MockHttpServletRequest("GET", "/v1/locations/missing");

        ResponseEntity<Object> response = handler.handleException(
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Location not found"),
                new ServletWebRequest(servletRequest));

        assertThat(response).isNotNull();
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        String header = response.getHeaders().getFirst(LocationGlobalExceptionHandler.X_CORRELATION_ID);
        assertThat(header).isNotBlank();
        ProblemDetail problem = (ProblemDetail) response.getBody();
        assertThat(problem).isNotNull();
        assertThat(problem.getProperties()).containsEntry("correlationId", header);
        assertThat(problem.getDetail()).isEqualTo("Location not found");
    }
}

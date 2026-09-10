package com.positivity.tenant.internal.controller;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.context.request.ServletWebRequest;

class TenantGlobalExceptionHandlerTest {

    private final TenantGlobalExceptionHandler handler = new TenantGlobalExceptionHandler();

    @Test
    void echoesInboundCorrelationId() throws Exception {
        MockHttpServletRequest servletRequest = new MockHttpServletRequest("POST", "/v1/platform/tenants");
        servletRequest.addHeader(TenantGlobalExceptionHandler.X_CORRELATION_ID, "corr-405");

        ResponseEntity<Object> response = handler.handleException(
                new HttpRequestMethodNotSupportedException("POST"), new ServletWebRequest(servletRequest));

        assertThat(response).isNotNull();
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(response.getHeaders().getFirst(TenantGlobalExceptionHandler.X_CORRELATION_ID))
                .isEqualTo("corr-405");
        ProblemDetail problem = (ProblemDetail) response.getBody();
        assertThat(problem.getProperties()).containsEntry("correlationId", "corr-405");
    }

    @Test
    void generatesACorrelationIdWhenAbsent() throws Exception {
        ResponseEntity<Object> response = handler.handleException(
                new HttpRequestMethodNotSupportedException("PUT"),
                new ServletWebRequest(new MockHttpServletRequest("PUT", "/v1/platform/tenants")));

        assertThat(response).isNotNull();
        String header = response.getHeaders().getFirst(TenantGlobalExceptionHandler.X_CORRELATION_ID);
        assertThat(header).isNotBlank();
        ProblemDetail problem = (ProblemDetail) response.getBody();
        assertThat(problem.getProperties()).containsEntry("correlationId", header);
    }
}

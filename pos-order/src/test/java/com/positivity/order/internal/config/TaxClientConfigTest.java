package com.positivity.order.internal.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** CAP:550 S32d: the pos-tax client forwards the inbound correlation id. */
@DisplayName("pos-tax client: correlation id")
class TaxClientConfigTest {

    @AfterEach
    void clear() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    @DisplayName("the inbound X-Correlation-Id is copied onto the call to pos-tax")
    void forwardsCorrelationId() throws Exception {
        MockHttpServletRequest inbound = new MockHttpServletRequest();
        inbound.addHeader("X-Correlation-Id", "corr-32d");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(inbound));
        MockClientHttpRequest outbound = new MockClientHttpRequest(HttpMethod.POST, URI.create("http://pos-tax/x"));

        TaxClientConfig.forwardCorrelationId()
                .intercept(outbound, new byte[0], (request, body) -> new MockClientHttpResponse());

        assertThat(outbound.getHeaders().getFirst("X-Correlation-Id")).isEqualTo("corr-32d");
    }

    @Test
    @DisplayName("outside a request nothing is added")
    void noInboundRequest() throws Exception {
        MockClientHttpRequest outbound = new MockClientHttpRequest(HttpMethod.POST, URI.create("http://pos-tax/x"));

        TaxClientConfig.forwardCorrelationId()
                .intercept(outbound, new byte[0], (request, body) -> new MockClientHttpResponse());

        assertThat(outbound.getHeaders().getFirst("X-Correlation-Id")).isNull();
    }
}

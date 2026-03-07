package com.positivity.order;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class BaseContractIntegrationTest {

    protected MockHttpServletRequestBuilder withGatewayAuth(MockHttpServletRequestBuilder requestBuilder) {
        return withGatewayAuth(requestBuilder, defaultAuthorities());
    }

    protected MockHttpServletRequestBuilder withGatewayAuth(
            MockHttpServletRequestBuilder requestBuilder, String authorities) {
        return requestBuilder
                .header("X-User", "00000000-0000-0000-0000-000000000001")
                .header("X-Authorities", authorities);
    }

    protected String defaultAuthorities() {
        return "*";
    }
}

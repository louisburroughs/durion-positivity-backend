package com.positivity.location;

import com.positivity.location.config.TestSecurityConfig;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestSecurityConfig.class)
public abstract class BaseContractIntegrationTest {

    protected MockHttpServletRequestBuilder withGatewayAuth(MockHttpServletRequestBuilder requestBuilder) {
        return requestBuilder
                .header("X-User", "location-test-user")
                .header("X-Authorities", defaultAuthorities());
    }

    protected String defaultAuthorities() {
        return "location:write,location:read,location.mobile-unit.manage,location.mobile-unit.read,"
                + "location.service-area.manage,location.service-area.read,"
                + "location.travel-buffer-policy.manage,location.travel-buffer-policy.read,"
                + "location:bay:manage,location:bay:read";
    }
}

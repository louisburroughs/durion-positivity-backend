package com.positivity.inventory.service.contract;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class BaseContractIntegrationTest {

    protected MockHttpServletRequestBuilder withGatewayAuth(MockHttpServletRequestBuilder requestBuilder) {
        return requestBuilder
                .header("X-User", "contract-test-user")
                .header("X-Authorities",
                        String.join(",",
                                "inventory:availability:read",
                                "inventory:adjustment:create",
                                "inventory:adjustment:approve",
                                "inventory:location:write",
                                "inventory:picking:manage"));
    }
}

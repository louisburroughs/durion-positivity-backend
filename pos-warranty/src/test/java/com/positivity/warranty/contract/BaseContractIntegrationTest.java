package com.positivity.warranty.contract;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Base for pos-warranty contract ITs: full Spring context over in-memory H2
 * (PostgreSQL mode), MockMvc against the real filter chain, gateway auth
 * supplied as the {@code X-User}/{@code X-Authorities} headers the imported
 * {@code GatewaySecurityConfig} trusts (ADR-0011/ADR-0014).
 *
 * <p>This is the module's first Failsafe layer (plan §5): pos-warranty's high
 * unit coverage never exercised its persistence or transaction boundaries, so
 * these ITs run controller → service → JPA → H2 end to end.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class BaseContractIntegrationTest {

    protected MockHttpServletRequestBuilder withClaimAuth(MockHttpServletRequestBuilder requestBuilder) {
        return requestBuilder
                .header("X-User", "contract-test-user")
                .header(
                        "X-Authorities",
                        String.join(
                                ",",
                                "warranty:claim:create",
                                "warranty:claim:view",
                                "warranty:claim:submit",
                                "warranty:claim:decide",
                                "warranty:claim:cancel"));
    }

    protected MockHttpServletRequestBuilder withViewOnlyAuth(MockHttpServletRequestBuilder requestBuilder) {
        return requestBuilder.header("X-User", "contract-test-user").header("X-Authorities", "warranty:claim:view");
    }
}

package com.positivity.vehiclefitment;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Base class for controller-layer integration tests in pos-vehicle-fitment.
 *
 * <p>Starts the full Spring Boot context with an in-memory H2 database and
 * MockMvc wired up. Subclasses can call {@link #withGatewayAuth} to inject
 * the gateway security headers ({@code X-User} / {@code X-Authorities}) that
 * the {@code GatewaySecurityConfig} filter uses to authenticate requests.</p>
 *
 * <p>ADR compliance:</p>
 * <ul>
 *   <li>ADR-0011 / ADR-0014: gateway-header-based stateless authentication</li>
 *   <li>ADR-0017: HTTP status code assertions (200, 201, 204, 400, 401, 404)</li>
 *   <li>ADR-0018: actor identity resolved from {@code X-User} header /
 *       SecurityContext, not from request body</li>
 * </ul>
 *
 * Issue: #44
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class BaseContractIntegrationTest {

    /**
     * Adds gateway authentication headers to the provided request builder.
     *
     * @param requestBuilder the MockMvc request to decorate
     * @return the request builder with {@code X-User} and {@code X-Authorities} headers set
     */
    protected MockHttpServletRequestBuilder withGatewayAuth(MockHttpServletRequestBuilder requestBuilder) {
        return requestBuilder
                .header("X-User", "contract-test-user")
                .header("X-Authorities", defaultAuthorities());
    }

    /**
     * Default authority string forwarded via the {@code X-Authorities} gateway header.
     * Override in subclasses to test with specific roles.
     *
     * @return wildcard authority granting full access in tests
     */
    protected String defaultAuthorities() {
        return "*";
    }
}

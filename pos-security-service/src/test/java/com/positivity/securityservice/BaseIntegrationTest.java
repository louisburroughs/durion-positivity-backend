package com.positivity.securityservice;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import tools.jackson.databind.ObjectMapper;

/**
 * Base class for integration tests in pos-security-service module.
 *
 * <p>
 * This class provides common setup for all integration tests including:
 * <ul>
 * <li>MockMvc configuration with Spring Security integration
 * <li>Gateway authentication header utilities
 * <li>Common test constants and patterns
 * </ul>
 *
 * <p>
 * Spring Boot 4.0 Note: @AutoConfigureMockMvc has been removed in Spring Boot
 * 4.0.
 * MockMvc must be manually configured via WebApplicationContext +
 * springSecurity().
 * This base class centralizes that configuration for all integration tests.
 *
 * <p>
 * Usage:
 * 
 * <pre>
 * &#64;SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
 * &#64;ActiveProfiles("test")
 * &#64;DisplayName("My Controller Tests")
 * public class MyControllerIT extends BaseIntegrationTest {
 *     // Test methods can use mockMvc and withAuth() directly
 * }
 * </pre>
 *
 * @see <a href=
 *      "https://spring.io/blog/2025/01/23/spring-boot-4-0-0-available-now">Spring
 *      Boot 4.0 Migration</a>
 */
public abstract class BaseIntegrationTest {

    @Autowired
    protected WebApplicationContext webApplicationContext;

    @Autowired
    protected ObjectMapper objectMapper;

    protected MockMvc mockMvc;

    // Gateway header values — mirrors what pos-api-gateway injects after JWT
    // validation
    protected static final String TEST_USER = "testuser";
    protected static final String TEST_AUTHORITIES = String.join(",",
            "ROLE_ADMIN",
            "security:auth:login",
            "security:auth:refresh",
            "security:auth:validate",
            "security:auth:revoke",
            "security:users:create",
            "security:users:view",
            "security:users:update",
            "security:users:delete",
            "security:roles:create",
            "security:roles:view",
            "security:roles:update",
            "security:roles:delete",
            "security:permissions:register",
            "security:permissions:view");

    /**
     * Initialize MockMvc with Spring Security integration before each test.
     *
     * <p>
     * In Spring Boot 4.0, MockMvc must be explicitly configured with the
     * WebApplicationContext and Spring Security filter chain. This method is
     * called before each test to ensure a fresh MockMvc instance.
     *
     * <p>
     * Subclasses can override this method to add additional MockMvc configuration,
     * but should call super.setUpMockMvc() first:
     * 
     * <pre>
     * &#64;BeforeEach
     * void setUp() {
     *     super.setUpMockMvc();
     *     // Additional setup
     * }
     * </pre>
     */
    @BeforeEach
    public void setUpMockMvc() {
        this.mockMvc = MockMvcBuilders
                .webAppContextSetup(webApplicationContext)
                .defaultRequest(withAuth(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/")))
                .apply(springSecurity())
                .build();
    }

    /**
     * Adds gateway authentication headers to a request builder.
     *
     * <p>
     * Mirrors the headers injected by pos-api-gateway after JWT validation.
     * These headers populate the SecurityContext via GatewayAuthoritiesFilter.
     *
     * <p>
     * Usage:
     * 
     * <pre>
     * mockMvc.perform(withAuth(post("/v1/users"))
     *         .contentType(MediaType.APPLICATION_JSON)
     *         .content(payload))
     *         .andExpect(status().isCreated());
     * </pre>
     *
     * @param builder the MockMvc request builder to augment
     * @return the builder with X-User and X-Authorities headers added
     */
    protected MockHttpServletRequestBuilder withAuth(MockHttpServletRequestBuilder builder) {
        return builder
                .header("X-User", TEST_USER)
                .header("X-Authorities", TEST_AUTHORITIES);
    }

    /**
     * Adds gateway authentication headers with custom authorities to a request
     * builder.
     *
     * <p>
     * Useful for testing authorization boundaries where specific permissions are
     * needed.
     *
     * <p>
     * Usage:
     * 
     * <pre>
     * mockMvc.perform(withAuth(post("/v1/users"), "security:users:view")
     *         .contentType(MediaType.APPLICATION_JSON)
     *         .content(payload))
     *         .andExpect(status().isForbidden());
     * </pre>
     *
     * @param builder     the MockMvc request builder to augment
     * @param authorities comma-separated authority strings
     * @return the builder with X-User and custom X-Authorities headers added
     */
    protected MockHttpServletRequestBuilder withAuth(MockHttpServletRequestBuilder builder, String authorities) {
        return builder
                .header("X-User", TEST_USER)
                .header("X-Authorities", authorities);
    }
}

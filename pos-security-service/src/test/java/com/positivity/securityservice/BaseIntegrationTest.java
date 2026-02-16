package com.positivity.securityservice;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

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
 * Spring Boot 4.0 Note: {@code @AutoConfigureMockMvc} has been removed in
 * Spring Boot 4.0.
 * MockMvc must be manually configured via WebApplicationContext +
 * springSecurity().
 * This base class centralizes that configuration for all integration tests.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public abstract class BaseIntegrationTest {

    @Autowired
    protected WebApplicationContext webApplicationContext;

    @Autowired
    protected ObjectMapper objectMapper;

    protected MockMvc mockMvc;

    protected static final String TEST_USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001").toString();
    protected static final String TEST_AUTHORITIES = String.join(",",
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
    protected static final String TEST_CORRELATION_ID = "test-correlation-id";

    @BeforeEach
    public void setUpMockMvc() {
        this.mockMvc = MockMvcBuilders
                .webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();
    }

    protected MockHttpServletRequestBuilder withAuth(MockHttpServletRequestBuilder builder) {
        return builder
                .header("X-User-Id", TEST_USER_ID)
                .header("X-Authorities", TEST_AUTHORITIES)
                .header("X-Correlation-Id", TEST_CORRELATION_ID);
    }

    protected MockHttpServletRequestBuilder withAuth(
            MockHttpServletRequestBuilder builder,
            String authorities) {
        return builder
                .header("X-User-Id", TEST_USER_ID)
                .header("X-Authorities", authorities)
                .header("X-Correlation-Id", TEST_CORRELATION_ID);
    }

    protected MockHttpServletRequestBuilder withAuth(
            MockHttpServletRequestBuilder builder,
            String userId,
            String authorities,
            String correlationId) {
        return builder
                .header("X-User-Id", userId)
                .header("X-Authorities", authorities)
                .header("X-Correlation-Id", correlationId);
    }
}

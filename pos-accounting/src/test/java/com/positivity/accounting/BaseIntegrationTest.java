package com.positivity.accounting;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.positivity.accounting.config.TestPaymentGatewayConfig;

import tools.jackson.databind.ObjectMapper;

/**
 * Base class for integration tests in pos-accounting module.
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
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestPaymentGatewayConfig.class)
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
            "accounting:je:view",
            "accounting:je:create",
            "accounting:je:post",
            "accounting:je:reverse",
            "accounting:coa:view",
            "accounting:coa:create",
            "accounting:coa:edit",
            "accounting:coa:deactivate",
            "accounting:events:view",
            "accounting:events:submit",
            "accounting:events:retry",
            "accounting:events:reprocess",
            "accounting:posting_rules:view",
            "accounting:posting_rules:create",
            "accounting:posting_rules:publish",
            "accounting:posting_rules:archive",
            "accounting:ap:view",
            "accounting:ap:pay",
            "accounting:ap-payment:execute",
            "accounting:ap-payment:read",
            "accounting:vendor-bill:read",
            "accounting:mappings:view",
            "accounting:mappings:create",
            "accounting:gl-mapping:create",
            "accounting:gl-mapping:resolve",
            "accounting:audit:view",
            "accounting:credit-memo:view",
            "accounting:credit-memo:read",
            "accounting:credit-memo:create",
            "accounting:credit-memo:issue",
            "accounting:credit-memo:apply",
            "accounting:invoice-payment:view",
            "accounting:invoice-payment:create",
            "accounting:payment-application:view",
            "accounting:payment-application:apply",
            "accounting:payment:apply",
            "accounting:payment:reverse",
            "accounting:financial-reporting:view",
            "reporting:view:financial-statements",
            "accounting:posting-category:view",
            "accounting:posting-category:create",
            "accounting:posting-category:edit",
            "accounting:posting-category:deactivate",
            "accounting:mapping-key:view",
            "accounting:mapping-key:create",
            "accounting:mapping-key:edit",
            "accounting:mapping-key:deactivate");

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
     * mockMvc.perform(withAuth(post("/v1/accounting/journal-entries"))
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
     * mockMvc.perform(withAuth(post("/v1/accounting/journal-entries"), "accounting:je:view")
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

package com.positivity.catalog;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
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
public abstract class BaseIntegrationTest {

    @Autowired
    protected WebApplicationContext webApplicationContext;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    protected MockMvc mockMvc;

    protected static final String TEST_USER = "testuser";
    protected static final String TEST_AUTHORITIES = String.join(",",
            "ROLE_ADMIN",
            "ROLE_CATALOG_VIEW",
            "ROLE_CATALOG_EDIT",
            "ROLE_CATALOG_DELETE",
            "product:lifecycle:update",
            "product:lifecycle:override_discontinued");

    @BeforeEach
    public void setUpMockMvc() {
        resetDatabaseState();
        this.mockMvc = MockMvcBuilders
                .webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();
    }

    private void resetDatabaseState() {
        jdbcTemplate.execute("SET REFERENTIAL_INTEGRITY FALSE");
        try {
            jdbcTemplate.queryForList(
                    "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = 'PUBLIC' AND TABLE_TYPE = 'BASE TABLE'",
                    String.class).forEach(table -> jdbcTemplate.execute("TRUNCATE TABLE \"" + table + "\""));
        } finally {
            jdbcTemplate.execute("SET REFERENTIAL_INTEGRITY TRUE");
        }
    }

    protected MockHttpServletRequestBuilder withAuth(MockHttpServletRequestBuilder builder) {
        return builder
                .header("X-User", TEST_USER)
                .header("X-Authorities", TEST_AUTHORITIES);
    }

    protected MockHttpServletRequestBuilder withAuth(MockHttpServletRequestBuilder builder, String authorities) {
        return builder
                .header("X-User", TEST_USER)
                .header("X-Authorities", authorities);
    }
}

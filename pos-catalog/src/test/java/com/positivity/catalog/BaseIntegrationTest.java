package com.positivity.catalog;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

import com.positivity.catalog.config.TestSecurityConfig;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestSecurityConfig.class)
public abstract class BaseIntegrationTest {

    @Autowired
    protected WebApplicationContext webApplicationContext;

    @Autowired
    protected ObjectMapper objectMapper;

    protected MockMvc mockMvc;

    protected static final String TEST_USER = "testuser";
    protected static final String TEST_AUTHORITIES = String.join(",",
            "catalog:product:view",
            "catalog:product:create",
            "catalog:product:edit",
            "catalog:product:delete",
            "catalog:category:view",
            "catalog:category:create",
            "catalog:category:edit",
            "catalog:category:delete",
            "catalog:service_type:view",
            "catalog:service_type:create",
            "catalog:service_type:edit",
            "catalog:variant:view",
            "catalog:variant:create",
            "catalog:variant:edit");

    @BeforeEach
    public void setUpMockMvc() {
        this.mockMvc = MockMvcBuilders
                .webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();
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

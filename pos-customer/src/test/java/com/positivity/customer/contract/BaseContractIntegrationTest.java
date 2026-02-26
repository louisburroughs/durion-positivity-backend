package com.positivity.customer.contract;

import com.positivity.customer.config.TestSecurityConfig;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Base class for CAP-252 contract integration tests.
 * Sets up MockMvc with gateway-auth headers (X-User, X-Authorities)
 * per ADR-0011 and ADR-0014.
 *
 * <p>
 * Tests extend this class to inherit MockMvc and auth header helpers.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestSecurityConfig.class)
public abstract class BaseContractIntegrationTest {

    @Autowired
    protected MockMvc mockMvc;

    /** Default user header value for party-view-authorized calls. */
    protected static final String TEST_USER = "test-user";

    /**
     * Authority header granting PARTY_VIEW for authorized calls.
     * Reflects the gateway auth pattern per ADR-0011 / ADR-0014.
     */
    protected static final String PARTY_VIEW_AUTHORITY = "crm:party:view";
}
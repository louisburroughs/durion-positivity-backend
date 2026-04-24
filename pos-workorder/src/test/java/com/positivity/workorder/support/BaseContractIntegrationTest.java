package com.positivity.workorder.support;

import com.positivity.workorder.config.TestSecurityConfig;
import com.positivity.workorder.contract.ContractTestConfiguration;
import io.restassured.http.ContentType;
import io.restassured.module.mockmvc.RestAssuredMockMvc;
import io.restassured.module.mockmvc.specification.MockMvcRequestSpecification;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Shared contract-test scaffolding for REST Assured based tests.
 *
 * <p>
 * Test requests include both gateway auth headers and a synthetic bearer
 * token. The token exists only to provide a decodable {@code userId} claim
 * for {@code GatewayAuthoritiesFilter} in test runtime. It is not signed or
 * validated and must never be reused outside tests.
 * </p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestSecurityConfig.class, ContractTestConfiguration.class})
public abstract class BaseContractIntegrationTest {

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    protected static final String SYSTEM_USER_ID = "00000000-0000-0000-0000-000000000001";
    protected static final String TEST_BEARER_TOKEN = buildTestOnlyBearerToken(SYSTEM_USER_ID);

    protected static final String TEST_AUTHORITIES = String.join(
            ",",
            "workorder:approval_config:view",
            "workorder:approval_config:create",
            "workorder:approval_config:edit",
            "workorder:approval_config:delete",
            "workorder:estimate:view",
            "workorder:estimate:create",
            "workorder:estimate:edit",
            "workorder:estimate:delete",
            "workorder:estimate:submit",
            "workorder:estimate:decline",
            "workorder:estimate:reopen",
            "workorder:estimate:approve",
            "workorder:estimate:promote",
            "workorder:estimate:calculate",
            "workorder:estimate_item:add",
            "workorder:estimate_item:edit",
            "workorder:estimate_item:delete",
            "workorder:estimate_item:view",
            "workorder:estimate_snapshot:create",
            "workorder:estimate_snapshot:view",
            "workorder:change_request:create",
            "workorder:change_request:approve",
            "workorder:change_request:decline",
            "workorder:change_request:emergency_override",
            "workorder:change_request:view",
            "workorder:workorder:view",
            "workorder:workorder:create",
            "workorder:workorder:edit",
            "workorder:workorder:delete",
            "workorder:workorder:approve",
            "workorder:workorder:start",
            "workorder:start",
            "workorder:workorder:complete",
            "workorder:workorder:generate_invoice",
            "workorder:workorder:reopen_completed",
            "workorder:workorder:assign-technician",
            "workorder:invoice:view",
            "workorder:invoice:create",
            "workorder:parts:view",
            "workorder:parts:add",
            "workorder:labor:view",
            "workorder:labor:add",
            // Issue CAP-139 Story #68: work session timekeeping authorities
            "timekeeping:work_session:create",
            "timekeeping:work_session:stop",
            "timekeeping:work_session:break_start",
            "timekeeping:work_session:break_stop",
            // Issue CAP-139 Story #66: time entry approval authorities
            "TimeEntry:Approve",
            "TimeEntry:Reject");

    @BeforeEach
    void configureRestAssuredDefaults() {
        purgeTestData();
        RestAssuredMockMvc.mockMvc(mockMvc);
    }

    /**
     * Clears all application tables for test isolation while tolerating FK-heavy
     * schemas.
     */
    protected void purgeTestData() {
        jdbcTemplate.execute("SET REFERENTIAL_INTEGRITY FALSE");
        try {
            var tableNames = jdbcTemplate.queryForList(
                    "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES "
                            + "WHERE TABLE_SCHEMA = 'PUBLIC' AND TABLE_TYPE = 'BASE TABLE'",
                    String.class);
            for (String tableName : tableNames) {
                jdbcTemplate.execute("TRUNCATE TABLE \"" + tableName + "\"");
            }
        } finally {
            jdbcTemplate.execute("SET REFERENTIAL_INTEGRITY TRUE");
        }
    }

    protected MockMvcRequestSpecification givenWithGatewayAuth() {
        return RestAssuredMockMvc.given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + TEST_BEARER_TOKEN)
                .header("X-User-Id", SYSTEM_USER_ID)
                .header("X-User", SYSTEM_USER_ID)
                .header("X-Authorities", TEST_AUTHORITIES);
    }

    protected MockHttpServletRequestBuilder withAuthMvc(MockHttpServletRequestBuilder req) {
        return req.header("Authorization", "Bearer " + TEST_BEARER_TOKEN)
                .header("X-User-Id", SYSTEM_USER_ID)
                .header("X-User", SYSTEM_USER_ID)
                .header("X-Authorities", TEST_AUTHORITIES);
    }

    /**
     * Builds a synthetic JWT-like token for tests only.
     *
     * <p>
     * This token is intentionally unsigned and used only so
     * {@code GatewayAuthoritiesFilter} can decode the payload and extract
     * {@code userId}. It must never be used in production code.
     * </p>
     */
    private static String buildTestOnlyBearerToken(String userId) {
        String headerJson = "{\"alg\":\"HS256\",\"typ\":\"JWT\"}";
        String payloadJson = "{\"userId\":\"" + userId + "\"}";
        String encodedHeader =
                Base64.getUrlEncoder().withoutPadding().encodeToString(headerJson.getBytes(StandardCharsets.UTF_8));
        String encodedPayload =
                Base64.getUrlEncoder().withoutPadding().encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8));
        return encodedHeader + "." + encodedPayload + ".test-signature-not-verified";
    }
}

package com.positivity.workorder.support;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import com.positivity.workorder.config.TestSecurityConfig;
import com.positivity.workorder.contract.ContractTestConfiguration;

import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;

/**
 * Shared contract-test scaffolding for REST Assured based tests.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({ TestSecurityConfig.class, ContractTestConfiguration.class })
public abstract class BaseContractIntegrationTest {

    protected static final UUID SYSTEM_USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    protected static final String TEST_AUTHORITIES = String.join(",",
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
            "workorder:workorder:complete",
            "workorder:workorder:generate_invoice",
            "workorder:workorder:reopen_completed",
            "workorder:workorder:assign-technician",
            "workorder:invoice:view",
            "workorder:invoice:create",
            "workorder:parts:view",
            "workorder:parts:add",
            "workorder:labor:view",
            "workorder:labor:add");

    @LocalServerPort
    private int port;

    @BeforeEach
    void configureRestAssuredDefaults() {
        RestAssured.port = port;
    }

    protected RequestSpecification givenWithGatewayAuth() {
        return RestAssured.given()
                .contentType(ContentType.JSON)
                .header("X-User-Id", SYSTEM_USER_ID.toString())
                .header("X-User", SYSTEM_USER_ID.toString())
                .header("X-Authorities", TEST_AUTHORITIES);
    }

    protected MockHttpServletRequestBuilder withAuthMvc(MockHttpServletRequestBuilder req) {
        return req
                .header("X-User-Id", SYSTEM_USER_ID.toString())
                .header("X-User", SYSTEM_USER_ID.toString())
                .header("X-Authorities", TEST_AUTHORITIES);
    }
}

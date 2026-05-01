package com.positivity.mcp.internal.orchestration.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Unit tests for {@link AdminFacadeTool}: verifies RestClient call shapes.
 */
class AdminFacadeToolTest {

    private static final String SECURITY_BASE_URL = "http://pos-security-service/v1/admin";
    private static final String USERS_BASE_URL = "http://pos-security-service/v1/users";

    private MockRestServiceServer mockServer;
    private AdminFacadeTool tool;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        mockServer = MockRestServiceServer.bindTo(builder).build();
        tool = new AdminFacadeTool(builder, SECURITY_BASE_URL, USERS_BASE_URL);
    }

    @Test
    @DisplayName("getSystemStatus returns constant UP string without HTTP call")
    void getSystemStatus_returnsConstantStatusString() {
        String result = tool.getSystemStatus();

        assertThat(result).isNotNull().contains("UP");
    }

    @Test
    @DisplayName("getUserPermissions sends GET /users/{userId}/roles to security service")
    void getUserPermissions_sendsGetToRolesEndpoint() {
        mockServer
                .expect(requestTo(SECURITY_BASE_URL + "/users/USR-001/roles"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"userId\":\"USR-001\",\"roles\":[]}", MediaType.APPLICATION_JSON));

        String result = tool.getUserPermissions("USR-001");

        mockServer.verify();
        assertThat(result).isNotEmpty().contains("USR-001");
    }

    @Test
    @DisplayName("getAuditLog sends GET /audit?q={query} to security service")
    void getAuditLog_sendsGetToAuditEndpoint() {
        mockServer
                .expect(requestTo(SECURITY_BASE_URL + "/audit?q=login"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"entries\":[]}", MediaType.APPLICATION_JSON));

        String result = tool.getAuditLog("login");

        mockServer.verify();
        assertThat(result).isNotEmpty();
    }
}

package com.positivity.mcp.internal.orchestration.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.positivity.security.common.GatewaySecurityConstants;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Unit tests for {@link AdminFacadeTool}. Expected verbs and URIs derive from
 * {@code facade-contract.yaml} (#1519 WS-0.3), never from literals duplicating the configuration.
 */
class AdminFacadeToolTest {

    private static final String BASE_URL = "http://api-gateway";
    private static final UUID CURRENT_USER_ID = UUID.fromString("00000000-0000-7000-8000-000000000222");

    private MockRestServiceServer mockServer;
    private AdminFacadeTool tool;

    private static FacadeContractManifest.Entry contract(String toolMethod) {
        return FacadeContractManifest.entry("AdminFacadeTool." + toolMethod);
    }

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        mockServer = MockRestServiceServer.bindTo(builder).build();
        tool = new AdminFacadeTool(
                builder,
                BASE_URL,
                BASE_URL,
                contract("listUsers").template(),
                contract("getUserPermissions").template(),
                contract("getAuditLog").template());
    }

    @Test
    @DisplayName("listUsers calls the security-service users list path, not the bare base URL")
    void listUsers_callsUsersListPath() {
        mockServer
                .expect(requestTo(BASE_URL + "/security-service/v1/users"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        String result = tool.listUsers();

        assertThat(result).isEqualTo("[]");
        mockServer.verify();
    }

    @Test
    @DisplayName("getSystemStatus returns constant UP string without HTTP call")
    void getSystemStatus_returnsConstantStatusString() {
        String result = tool.getSystemStatus();

        assertThat(result).isNotNull().contains("UP");
        mockServer.verify();
    }

    @Test
    @DisplayName("getUserPermissions sends GET /users/{userId}/permissions to security service")
    void getUserPermissions_sendsGetToPermissionsEndpoint() {
        FacadeContractManifest.Entry entry = contract("getUserPermissions");
        mockServer
                .expect(requestTo(BASE_URL + entry.expand(Map.of("userId", "USR-001"))))
                .andExpect(method(entry.httpMethod()))
                .andRespond(withSuccess("{\"userId\":\"USR-001\",\"roles\":[]}", MediaType.APPLICATION_JSON));

        String result = tool.getUserPermissions("USR-001");

        mockServer.verify();
        assertThat(result).isNotEmpty().contains("USR-001");
    }

    @Test
    @DisplayName("getAuditLog sends GET /audit/events?eventType={query} to security service")
    void getAuditLog_sendsGetToAuditEndpoint() {
        FacadeContractManifest.Entry entry = contract("getAuditLog");
        mockServer
                .expect(requestTo(BASE_URL + entry.expand(Map.of("query", "login"))))
                .andExpect(method(entry.httpMethod()))
                .andRespond(withSuccess("{\"entries\":[]}", MediaType.APPLICATION_JSON));

        String result = tool.getAuditLog("login");

        mockServer.verify();
        assertThat(result).isNotEmpty();
    }

    @Test
    @DisplayName("getMyPermissions resolves authenticated UUID and calls user permissions endpoint")
    void getMyPermissions_usesAuthenticatedUserId() {
        FacadeContractManifest.Entry entry = contract("getMyPermissions");
        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                "admin.alpha", "n/a", List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        authentication.setDetails(Map.of(
                GatewaySecurityConstants.DETAIL_USERNAME,
                "admin.alpha",
                GatewaySecurityConstants.DETAIL_USER_ID,
                CURRENT_USER_ID));
        SecurityContextHolder.getContext().setAuthentication(authentication);
        mockServer
                .expect(requestTo(BASE_URL + entry.expand(Map.of("userId", CURRENT_USER_ID.toString()))))
                .andExpect(method(entry.httpMethod()))
                .andRespond(withSuccess(
                        "{\"userId\":\"" + CURRENT_USER_ID + "\",\"roles\":[]}", MediaType.APPLICATION_JSON));

        String result = tool.getMyPermissions();

        mockServer.verify();
        assertThat(result).contains(CURRENT_USER_ID.toString());
        SecurityContextHolder.clearContext();
    }
}

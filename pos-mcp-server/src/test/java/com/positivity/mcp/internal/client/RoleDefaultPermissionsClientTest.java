package com.positivity.mcp.internal.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class RoleDefaultPermissionsClientTest {

    private static final String URL = "http://security-service/v1/roles/MANAGER/default-permissions";

    @Test
    void returnsPermissions_onSuccess() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(URL))
                .andExpect(header("X-User", "pos-mcp-server"))
                .andExpect(header("X-Authorities", "security:role:view"))
                .andRespond(withSuccess(
                        "{\"role\":\"MANAGER\",\"permissions\":[\"ROLE_MANAGER\",\"crm:party:view\"]}",
                        MediaType.APPLICATION_JSON));

        RoleDefaultPermissionsClient client = new RoleDefaultPermissionsClient(builder, "http://security-service");

        assertThat(client.defaultPermissions("MANAGER")).containsExactly("ROLE_MANAGER", "crm:party:view");
        server.verify();
    }

    @Test
    void failsSoftToEmpty_onError() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(URL)).andRespond(withServerError());

        RoleDefaultPermissionsClient client = new RoleDefaultPermissionsClient(builder, "http://security-service");

        assertThat(client.defaultPermissions("MANAGER")).isEmpty();
    }
}

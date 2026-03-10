package com.positivity.securityservice.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEvent;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.positivity.securityservice.PosSecurityServiceApplication;
import com.positivity.securityservice.internal.service.TokenRevocationManager;
import com.positivity.securityservice.BaseContractIntegrationTest;

/**
 * Contract behavior tests for RBAC role/permission matrix endpoints.
 *
 * Issue: #42
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = PosSecurityServiceApplication.class, properties = {
                "security.jwt.secret=test-jwt-secret-key-01234567890123456789"
})
@ActiveProfiles("test")
@RecordApplicationEvents
@WithMockUser(username = "system-admin", roles = { "ADMIN" })
@DisplayName("Issue #42 RBAC Role/Permission Contract Behavior")
class RolePermissionContractBehaviorIT extends BaseContractIntegrationTest {

        @MockitoBean
        private TokenRevocationManager tokenRevocationManager;

        private MockHttpServletRequestBuilder withSystemAdminAuth(MockHttpServletRequestBuilder builder) {
                return withAuth(builder,
                                "ROLE_ADMIN,security:permission:register,security:permission:view,security:roles:create,security:roles:update,security:roles:view");
        }

        // Issue #42: AC1 validates domain:resource:action permission naming on
        // registration.
        @Test
        @DisplayName("AC1: registerPermissions accepts valid permission naming convention")
        void ac1_registerPermissions_acceptsValidPermissionFormat() throws Exception {
                String payload = objectMapper.writeValueAsString(Map.of(
                                "permissions", List.of(Map.of(
                                                "id", "pricing:price_book:edit",
                                                "description", "Edit price book entries"))));

                mockMvc.perform(withSystemAdminAuth(post("/v1/users/permissions/registerPermissions")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(payload)))
                                .andExpect(status().isOk());
        }

        // Issue #42: AC1 rejects non-conforming permission key naming format.
        @Test
        @DisplayName("AC1: registerPermissions rejects invalid permission naming convention")
        void ac1_registerPermissions_rejectsInvalidPermissionFormat() throws Exception {
                String payload = objectMapper.writeValueAsString(Map.of(
                                "permissions", List.of(Map.of(
                                                "id", "invalid_key",
                                                "description", "Invalid key"))));

                mockMvc.perform(withSystemAdminAuth(post("/v1/users/permissions/registerPermissions")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(payload)))
                                .andExpect(status().isBadRequest());
        }

        // Issue #42: AC2 ensures permission registration is idempotent.
        @Test
        @DisplayName("AC2: registerPermissions is idempotent for duplicate registration")
        void ac2_registerPermissions_isIdempotent() throws Exception {
                String permissionKey = "pricing:price_book:edit";
                String payload = objectMapper.writeValueAsString(Map.of(
                                "permissions", List.of(Map.of(
                                                "id", permissionKey,
                                                "description", "Edit price book entries"))));

                mockMvc.perform(withSystemAdminAuth(post("/v1/users/permissions/registerPermissions")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(payload)))
                                .andExpect(status().isOk());

                mockMvc.perform(withSystemAdminAuth(post("/v1/users/permissions/registerPermissions")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(payload)))
                                .andExpect(status().isOk());

                String queryResponse = mockMvc.perform(withSystemAdminAuth(get("/v1/users/permissions")
                                .param("domain", "pricing")))
                                .andExpect(status().isOk())
                                .andReturn()
                                .getResponse()
                                .getContentAsString();

                @SuppressWarnings("unchecked")
                List<Map<String, Object>> permissions = objectMapper.readValue(queryResponse, List.class);
                long duplicateCount = permissions.stream()
                                .filter(permission -> permissionKey.equals(String.valueOf(permission.get("name"))))
                                .count();
                assertThat(duplicateCount).isEqualTo(1);
        }

        // Issue #42: AC3 verifies allow decision for principal with role-permission
        // grant.
        @Test
        @DisplayName("AC3: authorization decision returns allow when principal has granted permission")
        void ac3_authorizationDecision_returnsAllow() throws Exception {
                String rolePayload = objectMapper.writeValueAsString(Map.of(
                                "name", "PricingAnalyst",
                                "description", "Pricing analyst role"));

                String roleResponse = mockMvc.perform(withSystemAdminAuth(post("/v1/users/roles")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(rolePayload)))
                                .andExpect(status().isCreated())
                                .andReturn()
                                .getResponse()
                                .getContentAsString();

                @SuppressWarnings("unchecked")
                Map<String, Object> createdRole = objectMapper.readValue(roleResponse, Map.class);
                String roleId = String.valueOf(createdRole.get("id"));

                mockMvc.perform(withSystemAdminAuth(put("/v1/users/roles/{roleId}/permissions/grant", roleId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(Map.of(
                                                "permission", "pricing:msrp:edit")))))
                                .andExpect(status().isOk());

                mockMvc.perform(withSystemAdminAuth(
                                post("/v1/users/principals/{principalId}/roles/{roleId}", "userA", roleId)))
                                .andExpect(status().isOk());

                mockMvc.perform(withSystemAdminAuth(get("/v1/users/authorization/decision")
                                .param("principalId", "userA")
                                .param("permission", "pricing:msrp:edit")))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.decision").value("allow"));
        }

        // Issue #42: AC4 verifies deny-by-default behavior for unassigned principal.
        @Test
        @DisplayName("AC4: authorization decision returns deny by default")
        void ac4_authorizationDecision_returnsDenyByDefault() throws Exception {
                mockMvc.perform(withSystemAdminAuth(get("/v1/users/authorization/decision")
                                .param("principalId", "userB")
                                .param("permission", "pricing:msrp:edit")))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.decision").value("deny"));
        }

        // Issue #42: AC5 verifies role.permission.grant audit event emission when
        // granting.
        @Test
        @DisplayName("AC5: granting permission emits role.permission.grant audit event")
        void ac5_grantPermission_emitsAuditEvent(ApplicationEvents applicationEvents) throws Exception {
                String rolePayload = objectMapper.writeValueAsString(Map.of(
                                "name", "PricingApprover",
                                "description", "Pricing approver role"));

                String roleResponse = mockMvc.perform(withSystemAdminAuth(post("/v1/users/roles")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(rolePayload)))
                                .andExpect(status().isCreated())
                                .andReturn()
                                .getResponse()
                                .getContentAsString();

                @SuppressWarnings("unchecked")
                Map<String, Object> createdRole = objectMapper.readValue(roleResponse, Map.class);
                String roleId = String.valueOf(createdRole.get("id"));

                mockMvc.perform(withSystemAdminAuth(put("/v1/users/roles/{roleId}/permissions/grant", roleId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(Map.of(
                                                "permission", "pricing:msrp:edit")))))
                                .andExpect(status().isOk());

                boolean grantEventObserved = applicationEvents.stream(ApplicationEvent.class)
                                .anyMatch(event -> event.toString().contains("role.permission.grant"));

                assertThat(grantEventObserved).isTrue();
        }
}

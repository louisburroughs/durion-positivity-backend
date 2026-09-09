package com.positivity.securityservice.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.securityservice.BaseContractIntegrationTest;
import com.positivity.securityservice.PosSecurityServiceApplication;
import com.positivity.securityservice.internal.entity.User;
import com.positivity.securityservice.internal.repository.UserRepository;
import com.positivity.securityservice.internal.service.TokenRevocationManager;
import com.positivity.shared.id.UUIDv7Generator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEvent;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Contract behavior tests for RBAC role/permission matrix endpoints.
 *
 * Issue: #42
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = PosSecurityServiceApplication.class,
        properties = {"security.jwt.secret=test-jwt-secret-key-01234567890123456789"})
@ActiveProfiles("test")
@RecordApplicationEvents
@WithMockUser(
        username = "system-admin",
        roles = {"ADMIN"})
@DisplayName("Issue #42 RBAC Role/Permission Contract Behavior")
class RolePermissionContractBehaviorIT extends BaseContractIntegrationTest {

    @MockitoBean
    private TokenRevocationManager tokenRevocationManager;

    @Autowired
    private UserRepository userRepository;

    private MockHttpServletRequestBuilder withSystemAdminAuth(MockHttpServletRequestBuilder builder) {
        return withAuth(
                builder,
                "ROLE_ADMIN,security:permission:register,security:permission:view,security:role:create,security:role:edit,security:role:view,security:role:assign,security:authorization:decide");
    }

    // Issue #42: AC1 validates domain:resource:action permission naming on
    // registration.
    @Test
    @DisplayName("AC1: registerPermissions accepts valid permission naming convention")
    void ac1_registerPermissions_acceptsValidPermissionFormat() throws Exception {
        String payload = objectMapper.writeValueAsString(Map.of(
                "permissions",
                List.of(Map.of(
                        "id", "pricing:price_book:edit",
                        "description", "Edit price book entries"))));

        mockMvc.perform(withSystemAdminAuth(post("/v1/permissions/registerPermissions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)))
                .andExpect(status().isOk());
    }

    // Issue #42: AC1 rejects non-conforming permission key naming format.
    @Test
    @DisplayName("AC1: registerPermissions rejects invalid permission naming convention")
    void ac1_registerPermissions_rejectsInvalidPermissionFormat() throws Exception {
        String payload = objectMapper.writeValueAsString(Map.of(
                "permissions",
                List.of(Map.of(
                        "id", "invalid_key",
                        "description", "Invalid key"))));

        mockMvc.perform(withSystemAdminAuth(post("/v1/permissions/registerPermissions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)))
                .andExpect(status().isBadRequest());
    }

    // Issue #42: AC2 ensures permission registration is idempotent.
    @Test
    @DisplayName("AC2: registerPermissions is idempotent for duplicate registration")
    void ac2_registerPermissions_isIdempotent() throws Exception {
        String permissionKey = "pricing:price_book:edit";
        String payload = objectMapper.writeValueAsString(
                Map.of("permissions", List.of(Map.of("id", permissionKey, "description", "Edit price book entries"))));

        mockMvc.perform(withSystemAdminAuth(post("/v1/permissions/registerPermissions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)))
                .andExpect(status().isOk());

        mockMvc.perform(withSystemAdminAuth(post("/v1/permissions/registerPermissions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)))
                .andExpect(status().isOk());

        String queryResponse = mockMvc.perform(
                        withSystemAdminAuth(get("/v1/permissions").param("domain", "pricing")))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        // Response is Page<PermissionDto>; deserialize as Map and extract content list
        @SuppressWarnings("unchecked")
        Map<String, Object> permPage = objectMapper.readValue(queryResponse, Map.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> permissions = (List<Map<String, Object>>) permPage.get("content");
        long duplicateCount = permissions.stream()
                .filter(permission -> permissionKey.equals(String.valueOf(permission.get("name"))))
                .count();
        assertThat(duplicateCount).isEqualTo(1);
    }

    // Issue #42: AC3 verifies allow person-decision for a user with a granted
    // role-permission (ADR-0061 amendment 2026-09-09, #1914 phase 4: the
    // string-keyed principal matrix this test used to exercise is retired;
    // the equivalent off-session decision now resolves through a user's
    // linked personId).
    @Test
    @DisplayName("AC3: person-decision returns allow when the user's role has the granted permission")
    void ac3_personDecision_returnsAllow() throws Exception {
        String rolePayload = objectMapper.writeValueAsString(Map.of(
                "name", "PricingAnalyst",
                "description", "Pricing analyst role"));

        String roleResponse = mockMvc.perform(withSystemAdminAuth(post("/v1/roles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rolePayload)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        @SuppressWarnings("unchecked")
        Map<String, Object> createdRole = objectMapper.readValue(roleResponse, Map.class);
        String roleId = String.valueOf(createdRole.get("id"));

        mockMvc.perform(withSystemAdminAuth(put("/v1/roles/{roleId}/permissions/grant", roleId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("permission", "pricing:msrp:edit")))))
                .andExpect(status().isOk());

        UUID personId = UUIDv7Generator.generate();
        User user = new User();
        user.setId(UUIDv7Generator.generate());
        user.setUsername("userA-" + System.currentTimeMillis());
        user.setPassword("{noop}test-password");
        user.setPersonId(personId);
        user = userRepository.save(user);

        mockMvc.perform(withSystemAdminAuth(put("/v1/users/{userId}/roles/{roleId}", user.getId(), roleId)))
                .andExpect(status().isCreated());

        mockMvc.perform(withSystemAdminAuth(get("/v1/users/authorization/person-decision")
                        .param("personId", personId.toString())
                        .param("permission", "pricing:msrp:edit")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision").value("allow"));
    }

    // Issue #42: AC4 verifies deny-by-default behavior for a personId with no
    // linked user.
    @Test
    @DisplayName("AC4: person-decision returns deny by default")
    void ac4_personDecision_returnsDenyByDefault() throws Exception {
        mockMvc.perform(withSystemAdminAuth(get("/v1/users/authorization/person-decision")
                        .param("personId", UUIDv7Generator.generate().toString())
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

        String roleResponse = mockMvc.perform(withSystemAdminAuth(post("/v1/roles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rolePayload)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        @SuppressWarnings("unchecked")
        Map<String, Object> createdRole = objectMapper.readValue(roleResponse, Map.class);
        String roleId = String.valueOf(createdRole.get("id"));

        mockMvc.perform(withSystemAdminAuth(put("/v1/roles/{roleId}/permissions/grant", roleId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("permission", "pricing:msrp:edit")))))
                .andExpect(status().isOk());

        boolean grantEventObserved = applicationEvents.stream(ApplicationEvent.class)
                .anyMatch(event -> event.toString().contains("role.permission.grant"));

        assertThat(grantEventObserved).isTrue();
    }
}

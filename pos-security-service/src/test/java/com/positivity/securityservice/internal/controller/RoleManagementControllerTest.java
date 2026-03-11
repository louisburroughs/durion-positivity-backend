package com.positivity.securityservice.internal.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.FilterChain;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import com.positivity.securityservice.internal.dto.PermissionDto;
import com.positivity.securityservice.internal.dto.RoleDto;
import com.positivity.securityservice.internal.exception.DuplicateRoleNameException;
import com.positivity.securityservice.internal.service.CustomUserDetailsService;
import com.positivity.securityservice.internal.security.JwtAuthenticationFilter;
import com.positivity.securityservice.service.RoleManagementService;
import com.positivity.securityservice.service.RolePermissionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Story #62 controller slice tests for {@link RoleController}.
 *
 * <p>All 13 acceptance criteria from the Story #62 spec (13 tests,
 * 8 RED + 5 GREEN). Tests are {@link WebMvcTest} slices — service logic is
 * fully mocked; no database or full-context boot required.
 *
 * <h3>Status Summary</h3>
 * <table>
 *   <tr><th>SC</th><th>Path</th><th>Status</th><th>Reason if RED</th></tr>
 *   <tr><td>SC1</td><td>POST /v1/roles → 201</td><td>GREEN</td><td>—</td></tr>
 *   <tr><td>SC2</td><td>POST /v1/roles → 409 duplicate</td><td>GREEN</td><td>—</td></tr>
 *   <tr><td>SC3</td><td>GET /v1/roles → 200</td><td>GREEN</td><td>—</td></tr>
 *   <tr><td>SC4</td><td>GET /v1/roles/{id} → 200</td><td><b>RED</b></td><td>existing /{name} returns null body</td></tr>
 *   <tr><td>SC5</td><td>GET /v1/roles/{id} → 404</td><td><b>RED</b></td><td>existing /{name} returns 200</td></tr>
 *   <tr><td>SC6</td><td>DELETE /v1/roles/{id} → 204</td><td><b>RED</b></td><td>no endpoint → 404</td></tr>
 *   <tr><td>SC7</td><td>PUT /v1/roles/{id}/permissions/{p} → 204</td><td><b>RED</b></td><td>only /grant endpoint → 404</td></tr>
 *   <tr><td>SC8</td><td>DELETE /v1/roles/{id}/permissions/{p} → 204</td><td><b>RED</b></td><td>existing endpoint returns 200</td></tr>
 *   <tr><td>SC9</td><td>PUT /v1/users/{u}/roles/{r} → 204</td><td><b>RED</b></td><td>no endpoint → 404</td></tr>
 *   <tr><td>SC10</td><td>DELETE /v1/users/{u}/roles/{r} → 204</td><td><b>RED</b></td><td>no endpoint → 404</td></tr>
 *   <tr><td>SC11</td><td>GET /v1/users/{u}/permissions → 200</td><td><b>RED</b></td><td>path mismatch → 404</td></tr>
 *   <tr><td>SC12</td><td>Unauthenticated → 401</td><td>GREEN</td><td>—</td></tr>
 *   <tr><td>SC13</td><td>VIEWER role → 403</td><td>GREEN</td><td>—</td></tr>
 * </table>
 */
@WebMvcTest({ RoleController.class, UserRoleController.class })
@DisplayName("RoleManagementControllerTest — Story #62")
class RoleManagementControllerTest {

    private static final Clock  TEST_CLOCK     = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    private static final UUID   ROLE_ID        = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID   USER_ID        = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final String PERMISSION_KEY = "securityrolescreate"; // no colons to avoid URL encoding complexity

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RoleManagementService roleManagementService;
    @MockitoBean
    private RolePermissionService rolePermissionService;

    // Security infrastructure beans required by SecurityConfig
    @MockitoBean
    private JwtAuthenticationFilter jwtAuthenticationFilter;
    @MockitoBean
    private CustomUserDetailsService customUserDetailsService;

    /**
     * Make the mocked JWT filter a passthrough so it does not swallow requests.
     * {@code @WithMockUser} directly populates the {@link org.springframework.security.core.context.SecurityContext}
     * before the filter chain runs, so no real token processing is needed.
     */
    @BeforeEach
    void configureJwtFilterPassthrough() throws Exception {
        doAnswer(inv -> {
            ((FilterChain) inv.getArgument(2)).doFilter(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(jwtAuthenticationFilter).doFilter(any(), any(), any());
    }

    // ── SC1: POST /v1/roles → 201 ────────────────────────────────────────────

    /**
     * SC1: A valid role name returns 201 Created with the new Role JSON.
     *
     * <p>Story mapping: Functional Behavior 1 — create role.
     * <p>ADR-0017: Success response for POST is 201 Created with the created resource body.
     * <p>Status: <strong>GREEN</strong> — {@code POST /v1/roles} exists and returns 201.
     */
    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("SC1 (GREEN): POST /v1/roles → 201 Created with role JSON")
    void createRole_validName_returns201() throws Exception {
        RoleDto dto = RoleDto.builder()
                .id(ROLE_ID)
                .name("ShopManager")
                .createdBy("admin")
                .createdAt(Instant.now(TEST_CLOCK))
                .build();
        when(roleManagementService.createRole("ShopManager", null)).thenReturn(dto);

        mockMvc.perform(post("/v1/roles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"ShopManager\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(ROLE_ID.toString()))
                .andExpect(jsonPath("$.name").value("ShopManager"));
    }

    // ── SC2: POST /v1/roles duplicate → 409 ──────────────────────────────────

    /**
     * SC2: A duplicate (case-insensitive) role name returns 409 Conflict.
     *
     * <p>Story mapping: Alternate Flow — "Duplicate Role Creation → 409".
     * <p>ADR-0017: 409 Conflict for uniqueness-constraint violations.
     * <p>Status: <strong>GREEN</strong> after scaffold — {@link DuplicateRoleNameException}
     * is mapped to 409 by the {@code GlobalExceptionHandler}.
     */
    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("SC2 (GREEN-after-scaffold): POST /v1/roles duplicate name → 409 Conflict")
    void createRole_duplicateName_returns409() throws Exception {
        when(roleManagementService.createRole(anyString(), any()))
                .thenThrow(new DuplicateRoleNameException("Role already exists: ShopManager"));

        mockMvc.perform(post("/v1/roles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"ShopManager\"}"))
                .andExpect(status().isConflict());
    }

    // ── SC3: GET /v1/roles → 200 ─────────────────────────────────────────────

    /**
     * SC3: GET /v1/roles returns 200 OK with the list of all roles.
     *
     * <p>Status: <strong>GREEN</strong> — endpoint exists.
     */
    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("SC3 (GREEN): GET /v1/roles → 200 with role list")
    void getAllRoles_returns200() throws Exception {
        RoleDto dto = RoleDto.builder().id(ROLE_ID).name("Dispatcher").build();
        when(roleManagementService.getAllRoles()).thenReturn(List.of(dto));

        mockMvc.perform(get("/v1/roles"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Dispatcher"));
    }

    // ── SC4: GET /v1/roles/{id} → 200 ────────────────────────────────────────

    /**
     * SC4: GET /v1/roles/{id} by UUID returns 200 with the Role JSON.
     *
     * <p>Story mapping: Functional Behavior 1 — view a specific role by UUID.
     * <p><strong>RED</strong>: No UUID-typed {@code GET /v1/roles/{id}} exists.
     * The existing {@code GET /{name}} (String) is matched; it calls
     * {@code roleManagementService.getRoleByName(uuid-string)} (not mocked) → returns null
     * → controller returns {@code ResponseEntity.ok(null)} → 200 with no JSON body
     * → {@code jsonPath("$.id")} fails.
     *
     * <p>Expected failure: {@code org.assertj.core.error.ShouldNotBeNull}
     * or {@code No value at JSON path "$.id"}.
     */
    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("GET /v1/roles/{id} → 200 with role JSON")
    void getRoleById_existingId_returns200() throws Exception {
        RoleDto dto = RoleDto.builder().id(ROLE_ID).name("ShopManager").build();
        when(roleManagementService.getRoleById(ROLE_ID)).thenReturn(Optional.of(dto));

        mockMvc.perform(get("/v1/roles/{id}", ROLE_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(ROLE_ID.toString()));
    }

    // ── SC5: GET /v1/roles/{id} → 404 ────────────────────────────────────────

    /**
     * SC5: GET /v1/roles/{id} for a non-existent UUID returns 404 Not Found.
     *
     * <p><strong>RED</strong>: Existing {@code GET /{name}} returns 200 with null body
     * when the mock returns null → expected 404 but was 200.
     */
    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("GET /v1/roles/{unknown-id} → 404 Not Found")
    void getRoleById_nonExistentId_returns404() throws Exception {
        when(roleManagementService.getRoleById(any(UUID.class))).thenReturn(Optional.empty());

        mockMvc.perform(get("/v1/roles/{id}", UUID.fromString("00000000-0000-0000-0000-000000000099")))
                .andExpect(status().isNotFound());
    }

    // ── SC6: DELETE /v1/roles/{id} → 204 ─────────────────────────────────────

    /**
     * SC6: DELETE /v1/roles/{id} deletes the role (cascade) and returns 204 No Content.
     *
     * <p><strong>RED</strong>: No {@code DELETE /v1/roles/{id}} (single segment) endpoint
     * exists. Existing DELETE endpoints require more path segments or start with "assignments".
     *
     * <p>Expected failure: {@code expected 204 but was 404}.
     */
    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("DELETE /v1/roles/{id} → 204 No Content")
    void deleteRole_returns204() throws Exception {
        mockMvc.perform(delete("/v1/roles/{id}", ROLE_ID))
                .andExpect(status().isNoContent());
    }

    // ── SC7: PUT /v1/roles/{id}/permissions/{permId} → 204 ───────────────────

    /**
     * SC7: PUT /v1/roles/{id}/permissions/{permissionId} assigns a permission; returns 204.
     *
     * <p><strong>RED</strong>: Only {@code PUT /{roleId}/permissions/grant} exists
     * (literal "grant" path segment, body-based). The Story #62 spec requires the
     * permission key in the path and a 204 response.
     *
     * <p>Expected failure: {@code expected 204 but was 404} (no match for path with
     * variable permission segment).
     */
    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("PUT /v1/roles/{id}/permissions/{permKey} → 204 No Content")
    void assignPermission_returns204() throws Exception {
        mockMvc.perform(put("/v1/roles/{id}/permissions/{permKey}", ROLE_ID, PERMISSION_KEY))
                .andExpect(status().isNoContent());
    }

    // ── SC8: DELETE /v1/roles/{id}/permissions/{permKey} → 204 ───────────────

    /**
     * SC8: DELETE /v1/roles/{id}/permissions/{permKey} revokes a permission; returns 204.
     *
     * <p><strong>RED</strong>: Existing {@code DELETE /{roleId}/permissions/{permissionKey}}
     * returns HTTP 200 with a {@link RoleDto} body. Story #62 requires 204 No Content.
     *
     * <p>Expected failure: {@code expected 204 but was 200}.
     */
    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("DELETE /v1/roles/{id}/permissions/{permKey} → 204 No Content")
    void revokePermission_returns204() throws Exception {
        mockMvc.perform(delete("/v1/roles/{id}/permissions/{permKey}", ROLE_ID, PERMISSION_KEY))
                .andExpect(status().isNoContent());
    }

    // ── SC9: PUT /v1/users/{userId}/roles/{roleId} → 204 ─────────────────────

    /**
     * SC9: PUT /v1/users/{userId}/roles/{roleId} assigns a role to a user; returns 204.
     *
     * <p><strong>RED</strong>: No endpoint matches this path in {@link RoleController}
     * (mapped to {@code /v1/roles} and {@code /v1/users/roles} base only).
     *
     * <p>Expected failure: {@code expected 204 but was 404}.
     */
    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("PUT /v1/users/{userId}/roles/{roleId} → 204 No Content")
    void assignRoleToUser_returns204() throws Exception {
        mockMvc.perform(put("/v1/users/{userId}/roles/{roleId}", USER_ID, ROLE_ID))
                .andExpect(status().isNoContent());
    }

    // ── SC10: DELETE /v1/users/{userId}/roles/{roleId} → 204 ─────────────────

    /**
     * SC10: DELETE /v1/users/{userId}/roles/{roleId} revokes a role from a user; returns 204.
     *
     * <p><strong>RED</strong>: No endpoint exists.
     *
     * <p>Expected failure: {@code expected 204 but was 404}.
     */
    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("DELETE /v1/users/{userId}/roles/{roleId} → 204 No Content")
    void revokeRoleFromUser_returns204() throws Exception {
        mockMvc.perform(delete("/v1/users/{userId}/roles/{roleId}", USER_ID, ROLE_ID))
                .andExpect(status().isNoContent());
    }

    // ── SC11: GET /v1/users/{userId}/permissions → 200 ───────────────────────

    /**
     * SC11: GET /v1/users/{userId}/permissions returns 200 with the effective permissions.
     *
     * <p><strong>RED</strong>: The existing effective-permissions endpoint is at
     * {@code GET /v1/roles/permissions/user/{userId}}, not at the Story #62 path.
     * No handler matches {@code /v1/users/{userId}/permissions} in the loaded {@link RoleController}.
     *
     * <p>Expected failure: {@code expected 200 but was 404}.
     */
    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("GET /v1/users/{userId}/permissions → 200 effective permissions")
    void getEffectivePermissions_returns200() throws Exception {
        when(roleManagementService.getUserPermissions(USER_ID))
                .thenReturn(Set.of(PermissionDto.builder().name("security:roles:create").build()));

        mockMvc.perform(get("/v1/users/{userId}/permissions", USER_ID))
                .andExpect(status().isOk());
    }

    // ── SC12: Unauthenticated → 401 ──────────────────────────────────────────

    /**
     * ADR-0017 / SC12: All unauthenticated requests return 401 Unauthorized.
     *
     * <p>Story mapping: Alternate Flow — "Unauthorized Management → system must deny access".
     * Spring Security enforces authentication for all {@code /v1/**} paths.
     */
    @Test
    @DisplayName("SC12: unauthenticated POST /v1/roles → 401 Unauthorized")
    void createRole_unauthenticated_returns401() throws Exception {
        mockMvc.perform(post("/v1/roles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"TestRole\"}"))
                .andExpect(status().isUnauthorized());
    }

    // ── SC13: Insufficient authority → 403 ───────────────────────────────────

    /**
     * ADR-0017 / SC13: Authenticated caller without ADMIN role returns 403 Forbidden.
     *
     * <p>Story mapping: Alternate Flow — "Unauthorized Management → system must deny access".
     * {@code @PreAuthorize("hasRole('ADMIN')")} enforces ADMIN-only access on
     * {@code POST /v1/roles}.
     */
    @Test
    @WithMockUser(roles = "VIEWER")
    @DisplayName("SC13: VIEWER on POST /v1/roles → 403 Forbidden")
    void createRole_viewerRole_returns403() throws Exception {
        mockMvc.perform(post("/v1/roles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"TestRole\"}"))
                .andExpect(status().isForbidden());
    }

    // ── Test slice configuration ──────────────────────────────────────────────

    @TestConfiguration
    @EnableMethodSecurity(prePostEnabled = true)
    static class SliceTestConfig {

        /** Provides the Clock bean required by {@link com.positivity.securityservice.internal.config.GlobalExceptionHandler}. */
        @Bean
        Clock clock() {
            return TEST_CLOCK;
        }

        @Bean
        SecurityExceptionControllerAdvice securityExceptionControllerAdvice() {
            return new SecurityExceptionControllerAdvice();
        }
    }

    /**
     * Minimal security exception handler for the MVC slice — translates Spring Security
     * exceptions to 401 / 403 HTTP status codes. {@code @Order(HIGHEST_PRECEDENCE)} ensures
     * this advice is consulted before the production {@link com.positivity.securityservice.internal.config.GlobalExceptionHandler}
     * which has a catch-all {@code @ExceptionHandler(Exception.class)} that would otherwise
     * return 500 for security exceptions.
     */
    @ControllerAdvice
    @Order(Ordered.HIGHEST_PRECEDENCE)
    static class SecurityExceptionControllerAdvice {

        @ExceptionHandler(AccessDeniedException.class)
        @ResponseStatus(HttpStatus.FORBIDDEN)
        void handleAccessDenied() {
            // Intentionally empty: status code mapping is the behavior under test.
        }

        @ExceptionHandler(AuthenticationException.class)
        @ResponseStatus(HttpStatus.UNAUTHORIZED)
        void handleUnauthenticated() {
            // Intentionally empty: status code mapping is the behavior under test.
        }

        /**
         * Returns 404 for requests to endpoints that do not yet exist (RED scaffold tests).
         * Without this, Spring's unhandled {@code NoResourceFoundException} falls through to
         * {@code GlobalExceptionHandler.handleException(Exception)} and returns 500.
         */
        @ExceptionHandler(NoResourceFoundException.class)
        @ResponseStatus(HttpStatus.NOT_FOUND)
        void handleNotFound() {
            // Intentionally empty: keeps RED scaffold endpoint assertions on 404.
        }
    }
}

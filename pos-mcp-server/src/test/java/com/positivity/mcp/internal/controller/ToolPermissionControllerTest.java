package com.positivity.mcp.internal.controller;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.mcp.internal.dto.ToolPermissionsResponse;
import com.positivity.mcp.internal.service.ToolPermissionAdminService;
import java.util.List;
import java.util.NoSuchElementException;
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
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;

/** Controller slice tests for ToolPermissionController (Gate 3 / #785). */
@WebMvcTest(ToolPermissionController.class)
@ActiveProfiles("test")
class ToolPermissionControllerTest {

    private static final String TOOL = "event-receiver_getactiveeventtypes";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ToolPermissionAdminService service;

    @Test
    @WithMockUser(authorities = "mcp:tool:view")
    @DisplayName("GET permissions with view authority → 200")
    void list_withViewAuthority_returns200() throws Exception {
        when(service.listPermissions(TOOL)).thenReturn(new ToolPermissionsResponse(TOOL, List.of("AUTHENTICATED")));

        mockMvc.perform(get("/v1/tools/{toolName}/permissions", TOOL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.toolName").value(TOOL))
                .andExpect(jsonPath("$.permissionCodes[0]").value("AUTHENTICATED"));
    }

    @Test
    @WithMockUser(authorities = "mcp:tool:manage")
    @DisplayName("POST grant with manage authority → 201")
    void grant_withManageAuthority_returns201() throws Exception {
        when(service.grantPermission(eq(TOOL), eq("event-receiver:event_type:view")))
                .thenReturn(
                        new ToolPermissionsResponse(TOOL, List.of("AUTHENTICATED", "event-receiver:event_type:view")));

        mockMvc.perform(post("/v1/tools/{toolName}/permissions", TOOL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"permissionCode\":\"event-receiver:event_type:view\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.permissionCodes[1]").value("event-receiver:event_type:view"));
    }

    @Test
    @WithMockUser(authorities = "mcp:tool:manage")
    @DisplayName("POST grant with an invalid permission code → 400")
    void grant_withInvalidCode_returns400() throws Exception {
        mockMvc.perform(post("/v1/tools/{toolName}/permissions", TOOL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"permissionCode\":\"NOT A CODE\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(authorities = "mcp:tool:manage")
    @DisplayName("DELETE revoke with manage authority → 204")
    void revoke_withManageAuthority_returns204() throws Exception {
        mockMvc.perform(delete("/v1/tools/{toolName}/permissions", TOOL).param("permissionCode", "AUTHENTICATED"))
                .andExpect(status().isNoContent());

        verify(service).revokePermission(TOOL, "AUTHENTICATED");
    }

    @Test
    @WithMockUser(authorities = "mcp:tool:view")
    @DisplayName("GET permissions for an unknown tool → 404")
    void list_unknownTool_returns404() throws Exception {
        when(service.listPermissions("nope"))
                .thenThrow(new NoSuchElementException("No discovered OpenAPI tool named: nope"));

        mockMvc.perform(get("/v1/tools/{toolName}/permissions", "nope")).andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(authorities = "other:authority")
    @DisplayName("POST grant without manage authority → 403")
    void grant_withoutAuthority_returns403() throws Exception {
        mockMvc.perform(post("/v1/tools/{toolName}/permissions", TOOL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"permissionCode\":\"AUTHENTICATED\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET permissions unauthenticated → 401")
    void list_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/v1/tools/{toolName}/permissions", TOOL)).andExpect(status().isUnauthorized());
    }

    @TestConfiguration
    @EnableMethodSecurity(prePostEnabled = true)
    static class SliceTestConfig {

        @Bean
        SecurityExceptionControllerAdvice securityExceptionControllerAdvice() {
            return new SecurityExceptionControllerAdvice();
        }
    }

    @ControllerAdvice
    static class SecurityExceptionControllerAdvice {

        @ExceptionHandler(AccessDeniedException.class)
        @ResponseStatus(HttpStatus.FORBIDDEN)
        void handleAccessDenied() {
            // 403 via @ResponseStatus
        }

        @ExceptionHandler(AuthenticationException.class)
        @ResponseStatus(HttpStatus.UNAUTHORIZED)
        void handleAuthenticationException() {
            // 401 via @ResponseStatus
        }
    }
}

package com.positivity.mcp.internal.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.mcp.internal.service.CurrentUserContextResolver;
import com.positivity.mcp.internal.security.McpPermissions;
import com.positivity.mcp.service.AgentOrchestrationService;
import com.positivity.mcp.service.CurrentUserContext;
import java.util.Set;
import java.util.UUID;
import java.util.List;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Controller slice tests for {@link McpChatController}.
 */
@WebMvcTest(McpChatController.class)
@ActiveProfiles("test")
class McpChatControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AgentOrchestrationService agentOrchestrationService;

    @MockitoBean
    private CurrentUserContextResolver currentUserContextResolver;

    @BeforeEach
    void stubUserContextResolver() {
        when(currentUserContextResolver.resolve(any(Authentication.class))).thenReturn(defaultUserContext());
    }

    @Test
    @WithMockUser(username = "test-user", authorities = McpPermissions.MCP_CHAT_EXECUTE)
    @DisplayName("POST /v1/mcp/chat with message returns 200 and response payload")
    void chat_withMessage_returns200() throws Exception {
        when(agentOrchestrationService.chat(any(CurrentUserContext.class), anyString()))
                .thenReturn("assistant reply");
        var authentication = new UsernamePasswordAuthenticationToken(
                "test-user",
                "n/a",
                List.of(
                        new SimpleGrantedAuthority("ROLE_USER"),
                        new SimpleGrantedAuthority(McpPermissions.MCP_CHAT_EXECUTE)));

        mockMvc.perform(post("/v1/mcp/chat")
                .principal(authentication)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"message\":\"test\"}"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.response").value("assistant reply"));
    }

    @Test
    @WithMockUser(username = "admin.alpha", authorities = McpPermissions.MCP_CHAT_EXECUTE)
    @DisplayName("POST /v1/mcp/chat prefers ROLE_ADMIN when present in authorities")
    void chat_withAdminRole_usesAdminRoleForOrchestration() throws Exception {
        CurrentUserContext adminContext = new CurrentUserContext(
                "admin.alpha",
                UUID.fromString("00000000-0000-7000-8000-000000000123"),
                "ROLE_ADMIN",
                Set.of("ROLE_ADMIN"),
                Set.of("ROLE_ADMIN", McpPermissions.MCP_CHAT_EXECUTE));
        when(currentUserContextResolver.resolve(any(Authentication.class))).thenReturn(adminContext);
        when(agentOrchestrationService.chat(any(CurrentUserContext.class), anyString()))
                .thenReturn("assistant reply");
        var authentication = new UsernamePasswordAuthenticationToken(
                "admin.alpha",
                "n/a",
                List.of(
                        new SimpleGrantedAuthority("ROLE_ADMIN"),
                        new SimpleGrantedAuthority(McpPermissions.MCP_CHAT_EXECUTE)));

        mockMvc.perform(post("/v1/mcp/chat")
                .principal(authentication)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"message\":\"test\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response").value("assistant reply"));

        verify(agentOrchestrationService)
                .chat(argThat(context -> context.username().equals("admin.alpha")
                        && context.userId().equals(adminContext.userId())
                        && context.primaryRole().equals("ROLE_ADMIN")), org.mockito.ArgumentMatchers.eq("test"));
    }

    @Test
    @WithMockUser(username = "test-user", authorities = McpPermissions.MCP_CHAT_EXECUTE)
    @DisplayName("POST /v1/mcp/chat orchestration failure returns 500 ApiError envelope")
    void chat_orchestrationFailure_returns500ApiError() throws Exception {
        when(agentOrchestrationService.chat(any(CurrentUserContext.class), anyString()))
                .thenThrow(new RuntimeException("boom"));

        mockMvc.perform(post("/v1/mcp/chat")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"message\":\"test\"}"))
                .andExpect(status().isInternalServerError())
                .andExpect(header().exists("X-Correlation-Id"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("INTERNAL_SERVER_ERROR"))
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.message").value("An unexpected error occurred"))
                .andExpect(jsonPath("$.correlationId").isNotEmpty())
                .andExpect(jsonPath("$.timestamp").isNotEmpty());
    }

    @Test
    @DisplayName("POST /v1/mcp/chat unauthenticated returns 401 ApiError envelope")
    void chat_unauthenticated_returns401() throws Exception {
        mockMvc.perform(post("/v1/mcp/chat")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"message\":\"test\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().exists("X-Correlation-Id"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.message").value("Authentication is required"))
                .andExpect(jsonPath("$.correlationId").isNotEmpty())
                .andExpect(jsonPath("$.timestamp").isNotEmpty());
    }

    @Test
    @DisplayName("POST /v1/mcp/chat: authenticated user without mcp:chat:execute authority is denied 403")
    @WithMockUser(authorities = "ROLE_USER")
    void postChat_withoutChatExecuteAuthority_returns403() throws Exception {
        mockMvc.perform(post("/v1/mcp/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"message\":\"test\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "test-user", authorities = McpPermissions.MCP_CHAT_EXECUTE)
    @DisplayName("POST /v1/mcp/chat with blank message returns 400 ApiError envelope")
    void chat_withBlankMessage_returns400() throws Exception {
        mockMvc.perform(post("/v1/mcp/chat")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"message\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(header().exists("X-Correlation-Id"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("message"))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.correlationId").isNotEmpty())
                .andExpect(jsonPath("$.timestamp").isNotEmpty());
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
            // HTTP 403 set by @ResponseStatus
        }

        @ExceptionHandler(AuthenticationException.class)
        @ResponseStatus(HttpStatus.UNAUTHORIZED)
        void handleAuthenticationException() {
            // HTTP 401 set by @ResponseStatus
        }
    }

    private static CurrentUserContext defaultUserContext() {
        return new CurrentUserContext(
                "test-user",
                UUID.fromString("00000000-0000-7000-8000-000000000122"),
                "ROLE_USER",
                Set.of("ROLE_USER"),
                Set.of("ROLE_USER", McpPermissions.MCP_CHAT_EXECUTE));
    }
}

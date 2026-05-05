package com.positivity.mcp.internal.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.mcp.internal.exception.RateLimitExceededException;
import com.positivity.mcp.internal.service.CurrentUserContextResolver;
import com.positivity.mcp.service.CurrentUserContext;
import com.positivity.mcp.service.StreamingAgentOrchestrationService;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import reactor.core.publisher.Flux;

@WebMvcTest(McpStreamingChatController.class)
@ActiveProfiles("test")
class McpStreamingChatControllerTest {

        @Autowired
        private MockMvc mockMvc;

        @MockitoBean
        private StreamingAgentOrchestrationService streamingSessionAgentManager;

        @MockitoBean
        private CurrentUserContextResolver currentUserContextResolver;

        @BeforeEach
        void stubUserContextResolver() {
                when(currentUserContextResolver.resolve(any(Authentication.class))).thenReturn(defaultUserContext());
        }

        @Test
        @WithMockUser(username = "stream-user", authorities = "mcp:chat:stream")
        @DisplayName("POST /v1/mcp/chat/stream returns SSE chat events")
        void streamChat_withValidRequest_returnsSse() throws Exception {
                when(streamingSessionAgentManager.streamChat(any(CurrentUserContext.class), org.mockito.ArgumentMatchers.anyString()))
                                .thenReturn(Flux.just("token-1", "token-2"));
                var authentication = new UsernamePasswordAuthenticationToken(
                                "stream-user", "n/a", java.util.List.of(new SimpleGrantedAuthority("ROLE_USER")));

                mockMvc.perform(post("/v1/mcp/chat/stream")
                                .principal(authentication)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"message\":\"hello\"}"))
                                .andExpect(status().isOk())
                                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                                .andExpect(content().string(org.hamcrest.Matchers.containsString("event:chat")))
                                .andExpect(content().string(org.hamcrest.Matchers.containsString("data:token-1")));
        }

        @Test
        @WithMockUser(username = "stream-user", authorities = "mcp:chat:stream")
        @DisplayName("POST /v1/mcp/chat/stream with blank message returns 400")
        void streamChat_withBlankMessage_returns400() throws Exception {
                mockMvc.perform(post("/v1/mcp/chat/stream")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"message\":\"\"}"))
                                .andExpect(status().isBadRequest());
        }

        @Test
        @WithMockUser(username = "stream-user", authorities = "mcp:chat:stream")
        @DisplayName("POST /v1/mcp/chat/stream when rate-limited returns 429 ApiError")
        void streamChat_whenRateLimited_returns429ApiError() throws Exception {
                when(streamingSessionAgentManager.streamChat(any(CurrentUserContext.class), org.mockito.ArgumentMatchers.anyString()))
                                .thenThrow(new RateLimitExceededException("Rate limit exceeded"));
                var authentication = new UsernamePasswordAuthenticationToken(
                                "stream-user", "n/a", java.util.List.of(new SimpleGrantedAuthority("ROLE_USER")));

                mockMvc.perform(post("/v1/mcp/chat/stream")
                                .principal(authentication)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"message\":\"hello\"}"))
                                .andExpect(status().isTooManyRequests())
                                .andExpect(header().exists("X-Correlation-Id"))
                                .andExpect(jsonPath("$.code").value("RATE_LIMIT_EXCEEDED"))
                                .andExpect(jsonPath("$.message").value("Rate limit exceeded"))
                                .andExpect(jsonPath("$.status").value(429));
        }

        private static CurrentUserContext defaultUserContext() {
                return new CurrentUserContext(
                                "stream-user",
                                UUID.fromString("00000000-0000-7000-8000-000000000124"),
                                "ROLE_USER",
                                Set.of("ROLE_USER"),
                                Set.of("ROLE_USER", "mcp:chat:stream"));
        }
}

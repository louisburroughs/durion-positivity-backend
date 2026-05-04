package com.positivity.mcp.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.mcp.internal.security.McpPermissions;
import com.positivity.mcp.service.McpRoleResolver;
import com.positivity.mcp.service.StreamingAgentOrchestrationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.CurrentSecurityContext;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@RestController
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth", scopes = { "mcp:chat:stream" })
@RequestMapping("/v1/mcp")
public class McpStreamingChatController {

        private static final Logger LOGGER = LoggerFactory.getLogger(McpStreamingChatController.class);

        private final StreamingAgentOrchestrationService streamingSessionAgentManager;
        private final McpRoleResolver mcpRoleResolver;

        public McpStreamingChatController(
                        @NonNull StreamingAgentOrchestrationService streamingSessionAgentManager,
                        @NonNull McpRoleResolver mcpRoleResolver) {
                this.streamingSessionAgentManager = streamingSessionAgentManager;
                this.mcpRoleResolver = mcpRoleResolver;
        }

        @Operation(summary = "Execute MCP streaming chat - returns SSE token stream")
        @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
        @PreAuthorize("hasAuthority('" + McpPermissions.MCP_CHAT_STREAM + "')")
        @EmitEvent(id = "MCP_CHAT_STREAM_EXECUTE", apiVersion = "1")
        public Flux<ServerSentEvent<String>> streamChat(
                        @RequestBody @Valid @NonNull StreamChatRequest request,
                        @CurrentSecurityContext(expression = "authentication") @NonNull Authentication authentication) {

                @NonNull
                String userId = authentication.getName();
                @NonNull
                String role = mcpRoleResolver.resolvePrimaryRole(authentication);
                LOGGER.debug("MCP streaming selected role principal={} selectedRole={} fallback={}", userId, role,
                                "ROLE_USER".equals(role));

                return streamingSessionAgentManager
                                .streamChat(userId, role, request.message())
                                .map(token -> ServerSentEvent.<String>builder(token).event("chat").build());
        }

        @Schema(name = "StreamChatRequest", description = "Streaming chat request payload", example = "{\"message\":\"Hello\"}")
        public record StreamChatRequest(@NotBlank @NonNull String message) {
        }
}

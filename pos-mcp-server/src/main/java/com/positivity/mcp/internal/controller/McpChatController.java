package com.positivity.mcp.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.mcp.internal.security.McpPermissions;
import com.positivity.mcp.service.AgentOrchestrationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.Objects;
import org.jspecify.annotations.NonNull;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.annotation.CurrentSecurityContext;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Chat endpoint that routes user messages through a per-user LangChain4j agent.
 * The agent has role-specific tools, Exa web search, and RAG.
 */
@RestController
@RequestMapping("/v1/mcp")
public class McpChatController {

    private final AgentOrchestrationService agentOrchestrationService;

    public McpChatController(@NonNull AgentOrchestrationService agentOrchestrationService) {
        this.agentOrchestrationService = agentOrchestrationService;
    }

    @Operation(summary = "Execute MCP chat message")
    @PostMapping("/chat")
    @PreAuthorize("hasAuthority('" + McpPermissions.MCP_CHAT_EXECUTE + "')")
    @EmitEvent(id = "MCP_CHAT_EXECUTE", apiVersion = "1")
    public ResponseEntity<ChatResponse> chat(
            @RequestBody @Valid @NonNull ChatRequest request,
            @CurrentSecurityContext(expression = "authentication") @NonNull Authentication authentication) {

        @NonNull String userId = authentication.getName();
        @NonNull String role = extractPrimaryRole(authentication);
        String response = agentOrchestrationService.chat(userId, role, request.message());
        return ResponseEntity.ok(new ChatResponse(response));
    }

    private @NonNull String extractPrimaryRole(@NonNull Authentication auth) {
        return Objects.requireNonNull(auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(Objects::nonNull)
                .filter(a -> a.startsWith("ROLE_"))
                .findFirst()
                .orElse("ROLE_USER"));
    }

    @Schema(name = "ChatRequest", description = "Chat request payload", example = "{\"message\":\"Hello\"}")
    public record ChatRequest(@NotBlank @NonNull String message) {}

    @Schema(name = "ChatResponse", description = "Chat response payload", example = "{\"response\":\"Hi!\"}")
    public record ChatResponse(@NonNull String response) {}
}

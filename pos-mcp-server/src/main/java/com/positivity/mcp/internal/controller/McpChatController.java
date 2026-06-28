package com.positivity.mcp.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.mcp.internal.security.McpPermissions;
import com.positivity.mcp.internal.service.CurrentUserContextResolver;
import com.positivity.mcp.service.AgentOrchestrationService;
import com.positivity.mcp.service.CurrentUserContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
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
@io.swagger.v3.oas.annotations.security.SecurityRequirement(
        name = "bearerAuth",
        scopes = {"mcp:chat:execute"})
@RequestMapping("/v1/mcp")
public class McpChatController {

    private static final Logger LOGGER = LoggerFactory.getLogger(McpChatController.class);

    private final AgentOrchestrationService agentOrchestrationService;
    private final CurrentUserContextResolver currentUserContextResolver;

    public McpChatController(
            @NonNull AgentOrchestrationService agentOrchestrationService,
            @NonNull CurrentUserContextResolver currentUserContextResolver) {
        this.agentOrchestrationService = agentOrchestrationService;
        this.currentUserContextResolver = currentUserContextResolver;
    }

    @Operation(
            summary = "Execute MCP chat message",
            description =
                    "Executes a chat message and returns the full response once complete. For streaming responses, use the /chat/stream endpoint which returns tokens as they are generated.",
            requestBody =
                    @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            required = true,
                            content =
                                    @io.swagger.v3.oas.annotations.media.Content(
                                            mediaType = org.springframework.http.MediaType.APPLICATION_JSON_VALUE,
                                            schema =
                                                    @io.swagger.v3.oas.annotations.media.Schema(
                                                            implementation = McpChatController.ChatRequest.class))))
    @PostMapping("/chat")
    @PreAuthorize("hasAuthority('" + McpPermissions.MCP_CHAT_EXECUTE + "')")
    @EmitEvent(id = "MCP_CHAT_EXECUTE", apiVersion = "1")
    public ResponseEntity<ChatResponse> chat(
            @RequestBody @Valid @NonNull ChatRequest request,
            @CurrentSecurityContext(expression = "authentication") @NonNull Authentication authentication) {

        CurrentUserContext currentUserContext = currentUserContextResolver.resolve(authentication);
        LOGGER.debug(
                "MCP chat selected userContext username={} userId={} selectedRole={} roleCount={} authorityCount={} fallback={}",
                currentUserContext.username(),
                currentUserContext.userId(),
                currentUserContext.primaryRole(),
                currentUserContext.roles().size(),
                currentUserContext.authorities().size(),
                "ROLE_USER".equals(currentUserContext.primaryRole()));
        String response = agentOrchestrationService.chat(currentUserContext, request.message());
        return ResponseEntity.ok(new ChatResponse(response));
    }

    @Schema(name = "ChatRequest", description = "Chat request payload", example = "{\"message\":\"Hello\"}")
    public record ChatRequest(
            @Schema(
                    description = "User chat message to send to the agent",
                    example = "Hello",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            @NotBlank
            @NonNull
            String message) {}

    @Schema(name = "ChatResponse", description = "Chat response payload", example = "{\"response\":\"Hi!\"}")
    public record ChatResponse(
            @Schema(
                    description = "Full agent response text",
                    example = "Hi!",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            @NonNull
            String response) {}
}

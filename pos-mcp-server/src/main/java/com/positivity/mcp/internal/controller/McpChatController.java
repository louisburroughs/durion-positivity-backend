package com.positivity.mcp.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.mcp.internal.config.AgentOrchestrationService;
import com.positivity.mcp.internal.config.CurrentUserContext;
import com.positivity.mcp.internal.security.McpPermissions;
import com.positivity.mcp.internal.service.CurrentUserContextResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
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
 * Chat endpoint that routes user messages through a per-user assistant runtime agent.
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

    @Operation(operationId = "executeMcpChat", summary = "Execute a Blocking MCP Chat Turn", description = """
                    Executes a single chat message against the caller's permission-scoped assistant agent and \
                    returns the complete response text in one blocking call.
                    Use this tool for a simple request-response chat turn; do not use streamMcpChat, which returns \
                    the same answer incrementally as Server-Sent Events.
                    Preconditions: the agent's tool set is selected from the caller's granted permission codes and \
                    active workflow state, so the same message can produce different results for different callers.
                    Required inputs: message (non-blank text); the acting user is derived from the authenticated \
                    principal, not from the body.
                    Emits a MCP_CHAT_EXECUTE event; the agent may invoke permission-gated tools, RAG retrieval and \
                    web search while producing the answer.
                    Returns 200 with the full response text, and 429 when the caller's chat rate limit is exceeded.
                    """)
    @PostMapping("/chat")
    @PreAuthorize("hasAuthority('" + McpPermissions.MCP_CHAT_EXECUTE + "')")
    @EmitEvent(id = "MCP_CHAT_EXECUTE", apiVersion = "1")
    public ResponseEntity<ChatResponse> chat(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Single user chat message for the assistant agent to answer in full.",
                            required = true,
                            content =
                                    @io.swagger.v3.oas.annotations.media.Content(
                                            mediaType = org.springframework.http.MediaType.APPLICATION_JSON_VALUE,
                                            examples =
                                                    @io.swagger.v3.oas.annotations.media.ExampleObject(
                                                            name = "Chat message",
                                                            value =
                                                                    "{\"message\":\"Show me the open workorders for today\"}")))
                    @RequestBody
                    @Valid
                    @NonNull
                    ChatRequest request,
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
        String response =
                agentOrchestrationService.chat(currentUserContext, request.message(), request.conversationId());
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
            String message,

            @Schema(
                    description = "Optional conversation id. Turns sharing an id share one memory; "
                            + "omit it to use the caller's default per-role conversation, and supply a "
                            + "distinct id per request to ask independent questions that do not inherit "
                            + "each other's history (#1735).",
                    example = "gate-q07",
                    requiredMode = Schema.RequiredMode.NOT_REQUIRED)
            @Nullable
            String conversationId) {}

    @Schema(name = "ChatResponse", description = "Chat response payload", example = "{\"response\":\"Hi!\"}")
    public record ChatResponse(
            @Schema(
                    description = "Full agent response text",
                    example = "Hi!",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            @NonNull
            String response) {}
}

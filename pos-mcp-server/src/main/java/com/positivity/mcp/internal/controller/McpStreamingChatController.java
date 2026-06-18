package com.positivity.mcp.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.mcp.internal.security.McpPermissions;
import com.positivity.mcp.internal.service.CurrentUserContextResolver;
import com.positivity.mcp.service.CurrentUserContext;
import com.positivity.mcp.service.StreamingAgentOrchestrationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
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
@io.swagger.v3.oas.annotations.security.SecurityRequirement(
        name = "bearerAuth",
        scopes = {"mcp:chat:stream"})
@RequestMapping("/v1/mcp")
public class McpStreamingChatController {

    private static final Logger LOGGER = LoggerFactory.getLogger(McpStreamingChatController.class);

    private final StreamingAgentOrchestrationService streamingSessionAgentManager;
    private final CurrentUserContextResolver currentUserContextResolver;

    public McpStreamingChatController(
            @NonNull StreamingAgentOrchestrationService streamingSessionAgentManager,
            @NonNull CurrentUserContextResolver currentUserContextResolver) {
        this.streamingSessionAgentManager = streamingSessionAgentManager;
        this.currentUserContextResolver = currentUserContextResolver;
    }

    @Operation(
            summary = "Execute MCP streaming chat - returns SSE token stream",
            description =
                    "Executes a streaming chat message and returns a stream of tokens as Server-Sent Events (SSE). Each token is sent as an individual SSE with event type 'chat'.",
            responses = {
                @ApiResponse(
                        responseCode = "200",
                        description = "Stream of SSE chat tokens",
                        content =
                                @Content(
                                        mediaType = MediaType.TEXT_EVENT_STREAM_VALUE,
                                        array =
                                                @ArraySchema(
                                                        schema =
                                                                @Schema(implementation = ServerSentEventString.class))))
            })
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @PreAuthorize("hasAuthority('" + McpPermissions.MCP_CHAT_STREAM + "')")
    @EmitEvent(id = "MCP_CHAT_STREAM_EXECUTE", apiVersion = "1")
    public Flux<ServerSentEvent<String>> streamChat(
            @RequestBody @Valid @NonNull StreamChatRequest request,
            @CurrentSecurityContext(expression = "authentication") @NonNull Authentication authentication) {

        CurrentUserContext currentUserContext = currentUserContextResolver.resolve(authentication);
        LOGGER.debug(
                "MCP streaming selected userContext username={} userId={} selectedRole={} roleCount={} authorityCount={} fallback={}",
                currentUserContext.username(),
                currentUserContext.userId(),
                currentUserContext.primaryRole(),
                currentUserContext.roles().size(),
                currentUserContext.authorities().size(),
                "ROLE_USER".equals(currentUserContext.primaryRole()));

        return streamingSessionAgentManager
                .streamChat(currentUserContext, request.message())
                .map(token ->
                        ServerSentEvent.<String>builder(token).event("chat").build());
    }

    @Schema(
            name = "StreamChatRequest",
            description = "Streaming chat request payload",
            example = "{\"message\":\"Hello\"}")
    public record StreamChatRequest(
            @Schema(
                            description = "User chat message to stream to the agent",
                            example = "Hello",
                            requiredMode = Schema.RequiredMode.REQUIRED)
                    @NotBlank
                    @NonNull
                    String message) {}

    @Schema(name = "ServerSentEventString", description = "A Server-Sent Event carrying a string data payload")
    public record ServerSentEventString(
            @Schema(
                            description = "Event identifier",
                            example = "1",
                            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
                    @Nullable
                    String id,
            @Schema(
                            description = "Event name",
                            example = "chat",
                            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
                    @Nullable
                    String event,
            @Schema(
                            description = "String data payload of the event, typically a response token",
                            example = "Hi",
                            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
                    @Nullable
                    String data,
            @Schema(
                            description = "Reconnection time in milliseconds the client should wait before retrying",
                            example = "3000",
                            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
                    @Nullable
                    Long retry,
            @Schema(
                            description = "Comment line carried by the event",
                            example = "keep-alive",
                            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
                    @Nullable
                    String comment) {}
}

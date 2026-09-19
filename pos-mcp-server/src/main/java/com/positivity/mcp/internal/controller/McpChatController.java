package com.positivity.mcp.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.mcp.internal.dto.ChatBlock;
import com.positivity.mcp.internal.security.McpPermissions;
import com.positivity.mcp.internal.service.ConversationTurnService;
import com.positivity.mcp.internal.service.ConversationTurnService.ChatTurnResult;
import com.positivity.shared.error.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
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

    private final ConversationTurnService conversationTurnService;

    public McpChatController(@NonNull ConversationTurnService conversationTurnService) {
        this.conversationTurnService = conversationTurnService;
    }

    @Operation(operationId = "executeMcpChat", summary = "Execute a Blocking MCP Chat Turn", description = """
                    Executes a single chat message against the caller's permission-scoped assistant agent and \
                    returns the complete response text in one blocking call.
                    Use this tool for a simple request-response chat turn; do not use streamMcpChat, which \
                    returns the same answer incrementally as Server-Sent Events.
                    Preconditions: the agent's tool set is selected from the caller's granted permission codes \
                    and active workflow state, so the same message can produce different results for \
                    different callers.
                    Required inputs: message (non-blank text); conversationId is optional and, when omitted, \
                    starts a new persisted conversation with fresh memory, while a UUID owned by the caller \
                    continues that conversation and a non-UUID value is the deprecated ephemeral isolation \
                    key (#1735) that is never persisted.
                    Emits a MCP_CHAT_EXECUTE event and persists both the user and the assistant turn, so a \
                    caller must not re-append them with appendMcpConversationMessage; the agent may invoke \
                    permission-gated tools, RAG retrieval and web search while producing the answer.
                    Returns 200 with the full response text, the resolved conversationId to echo on every \
                    follow-up turn, the persisted assistant messageId (null on the ephemeral path, or when \
                    the conversation was deleted or purged mid-turn), and a parallel blocks array segmented \
                    from that same text in source order, which is optional, may be empty, and is safely \
                    ignored by older clients that render response instead.
                    Returns 404 when conversationId is a UUID that does not exist or belongs to another \
                    subject, 409 CONVERSATION_BUSY when a turn is already running on the same conversation, \
                    and 429 when the caller's chat rate limit is exceeded.
                    """)
    @ApiResponse(responseCode = "200", description = "Chat turn answered")
    @ApiResponse(
            responseCode = "400",
            description = "Malformed request (blank message, message over the length limit)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "conversationId is a UUID not found, or owned by another subject",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "409",
            description = "A turn is already running on this conversation (CONVERSATION_BUSY)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "429",
            description = "Caller's chat rate limit exceeded",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
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
                    ChatRequest request) {

        ChatTurnResult result = conversationTurnService.runTurn(request.conversationId(), request.message());
        return ResponseEntity.ok(
                new ChatResponse(result.response(), result.blocks(), result.conversationId(), result.messageId()));
    }

    @Schema(name = "ChatRequest", description = "Chat request payload", example = "{\"message\":\"Hello\"}")
    public record ChatRequest(
            @Schema(
                    description = "User chat message to send to the agent. At most 32000 characters.",
                    example = "Hello",
                    maxLength = 32000,
                    requiredMode = Schema.RequiredMode.REQUIRED)
            @NotBlank
            @Size(min = 1, max = 32000)
            @NonNull
            String message,

            @Schema(
                    description = "Optional conversation id (#2073). Omit to start a new persisted "
                            + "conversation with fresh memory; the response's `conversationId` is the id to "
                            + "echo on every follow-up turn. A UUID owned by the caller reuses that "
                            + "conversation; a UUID not found or owned by another subject answers 404 "
                            + "`CONVERSATION_NOT_FOUND`. A non-UUID value is the deprecated ephemeral "
                            + "isolation key (#1735): memory-only, never persisted, echoed back unchanged so "
                            + "existing non-UUID callers keep working. Deliberately typed as a plain string "
                            + "(not a UUID) precisely to accept that deprecated non-UUID form.",
                    example = "0198f2b1-6c2a-7c3e-8f00-1234567890ab",
                    requiredMode = Schema.RequiredMode.NOT_REQUIRED)
            @Nullable
            String conversationId) {}

    @Schema(
            name = "ChatResponse",
            description = "Chat response payload",
            example = "{\"response\":\"Hi!\",\"blocks\":[{\"kind\":\"markdown\",\"markdown\":\"Hi!\"}],"
                    + "\"conversationId\":\"0198f2b1-6c2a-7c3e-8f00-1234567890ab\","
                    + "\"messageId\":\"0198f2b1-7a10-7b21-9c00-abcdef123456\"}")
    public record ChatResponse(
            @Schema(
                    description = "Full agent response text",
                    example = "Hi!",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            @NonNull
            String response,

            @ArraySchema(
                    schema = @Schema(implementation = ChatBlock.class),
                    arraySchema =
                            @Schema(
                                    description = "Typed rendering units segmented from `response`, in source "
                                            + "order; markdown runs interleave with table/code blocks wherever "
                                            + "they occur in the answer, so the first block is not guaranteed "
                                            + "to be markdown. Optional; older clients may ignore it. When "
                                            + "empty or absent, render `response` instead.",
                                    requiredMode = Schema.RequiredMode.NOT_REQUIRED))
            @NonNull
            List<ChatBlock> blocks,

            @Schema(
                    description = "Conversation id this turn was recorded against (#2073). Always populated: "
                            + "newly created, reused from the request, or (deprecated ephemeral path) the "
                            + "caller's own non-UUID key echoed back unchanged. Echo this value on the next "
                            + "turn's `conversationId` to continue the same conversation. Deliberately typed "
                            + "as a plain string (not a UUID) to also carry the deprecated non-UUID ephemeral "
                            + "form back to the caller unchanged.",
                    example = "0198f2b1-6c2a-7c3e-8f00-1234567890ab",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            @NonNull
            String conversationId,

            @Schema(
                    description = "Id of the persisted assistant message for this turn, or `null` in the "
                            + "deprecated ephemeral (non-UUID `conversationId`) path, or if the conversation "
                            + "was deleted/purged between the turn starting and finishing.",
                    nullable = true,
                    requiredMode = Schema.RequiredMode.NOT_REQUIRED)
            @Nullable
            UUID messageId) {}
}

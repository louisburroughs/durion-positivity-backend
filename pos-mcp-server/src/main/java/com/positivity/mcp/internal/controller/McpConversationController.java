package com.positivity.mcp.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.mcp.internal.dto.AppendMessageRequest;
import com.positivity.mcp.internal.dto.ConversationDetail;
import com.positivity.mcp.internal.dto.ConversationMessage;
import com.positivity.mcp.internal.dto.ConversationPolicy;
import com.positivity.mcp.internal.dto.ConversationSummary;
import com.positivity.mcp.internal.dto.CreateConversationRequest;
import com.positivity.mcp.internal.dto.MessageFeedbackRequest;
import com.positivity.mcp.internal.dto.UpdateConversationRequest;
import com.positivity.mcp.internal.security.McpPermissions;
import com.positivity.mcp.internal.service.ConversationService;
import com.positivity.shared.error.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Conversation history CRUD for the assistant modal (#2073).
 *
 * <p>Tenant ({@code tid}) and owner ({@code sub}) are resolved from the token by {@link
 * ConversationService} itself (ADR-0062) — never from a body, query parameter, header or route
 * value, so a caller can only ever reach their own conversations. An id belonging to another
 * subject answers identically to one that never existed: 404, never 403.
 *
 * <p>Reuses {@code mcp:chat:execute} for every route here rather than minting a new permission —
 * the data behind these endpoints is the caller's own chat history, so the permission that
 * already gates chatting is the right gate, and a new permission would need bitset/gateway/role
 * seed work this story does not include (every caller would 403 until that lands).
 *
 * <p>Operation descriptions follow {@code ../durion/docs/architecture/api/OPENAPI_DESCRIPTION_STANDARD.md} (ADR-0042 §1 and
 * §3): they are what an agent reads when choosing between these tools, and this module is
 * validated in {@code STRICT} mode by {@code pos-openapi-validation}.
 */
@RestController
@RequestMapping("/v1/mcp/conversations")
@Tag(name = "MCP Conversations", description = "Server-side conversation history for the assistant modal")
class McpConversationController {

    private final ConversationService conversationService;

    McpConversationController(@NonNull ConversationService conversationService) {
        this.conversationService = conversationService;
    }

    @GetMapping
    @Operation(operationId = "listMcpConversations", summary = "List My Conversations", description = """
                    Returns the caller's own conversation history, pinned first then \
                    most-recently-updated first, capped at 200 rows (no pagination).
                    Use this tool to populate the assistant's history rail; do not use it to read a \
                    conversation's turns, which getMcpConversation returns.
                    Preconditions: none; an empty list means the caller has no conversations yet.
                    Required inputs: none; tenant and owner come from the authenticated principal, never from \
                    a request parameter.
                    Emits a MCP_CONVERSATION_LIST event; unpinned conversations idle beyond the server's \
                    retention window are purged and pinned conversations are exempt, as reported by \
                    getMcpConversationPolicy.
                    Returns 200 with the caller's conversation summaries.
                    """)
    @ApiResponse(responseCode = "200", description = "Conversation summaries returned")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"mcp:chat:execute"})
    @PreAuthorize("hasAuthority('" + McpPermissions.MCP_CHAT_EXECUTE + "')")
    @EmitEvent(id = "MCP_CONVERSATION_LIST", apiVersion = "1")
    ResponseEntity<List<ConversationSummary>> list() {
        return ResponseEntity.ok(conversationService.list());
    }

    @GetMapping("/policy")
    @Operation(
            operationId = "getMcpConversationPolicy",
            summary = "Get Conversation Retention Policy",
            description = """
                    Returns the server-wide conversation retention policy, so the history rail's footer can \
                    state it truthfully instead of the old per-browser wording.
                    Use this tool to render retention wording in a client; do not infer the window from \
                    listMcpConversations, whose row count says nothing about when a conversation is purged.
                    Preconditions: none.
                    Required inputs: none; the policy is server-wide rather than per-caller.
                    No events are emitted; this is a read-only projection of configuration, under which \
                    unpinned conversations idle beyond retentionDays are purged while pinned conversations \
                    are exempt when pinnedExempt is true.
                    Returns 200 with the retention policy.
                    """)
    @ApiResponse(responseCode = "200", description = "Retention policy returned")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"mcp:chat:execute"})
    @PreAuthorize("hasAuthority('" + McpPermissions.MCP_CHAT_EXECUTE + "')")
    ResponseEntity<ConversationPolicy> policy() {
        return ResponseEntity.ok(conversationService.policy());
    }

    @PostMapping
    @Operation(operationId = "createMcpConversation", summary = "Start a New Conversation", description = """
                    Starts a new, empty conversation owned by the caller, optionally with an initial title.
                    Use this tool to start a conversation the caller can then append turns to; do not use it \
                    to continue an existing one, since executeMcpChat with an existing conversationId does that.
                    Preconditions: none.
                    Required inputs: none; title is optional (1..120 characters after trimming) and, when \
                    omitted, is derived from the first message later appended to the conversation.
                    Emits a MCP_CONVERSATION_CREATE event.
                    Returns 201 with the created conversation (empty message list), and 400 when title falls \
                    outside 1..120 characters after trimming.
                    """)
    @ApiResponse(responseCode = "201", description = "Conversation created")
    @ApiResponse(
            responseCode = "400",
            description = "Malformed request (title outside 1..120 characters after trimming)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"mcp:chat:execute"})
    @PreAuthorize("hasAuthority('" + McpPermissions.MCP_CHAT_EXECUTE + "')")
    @EmitEvent(id = "MCP_CONVERSATION_CREATE", apiVersion = "1")
    ResponseEntity<ConversationDetail> create(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Optional initial title for the new conversation; send an empty "
                                    + "object to let the server derive one from the first appended message.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                                            examples = {
                                                @ExampleObject(name = "Titled conversation", value = """
                                                                {"title":"Mechanic roster count"}
                                                                """),
                                                @ExampleObject(name = "Untitled conversation", value = "{}")
                                            }))
                    @RequestBody
                    @Valid
                    @NonNull
                    CreateConversationRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(conversationService.create(request));
    }

    @GetMapping("/{id}")
    @Operation(operationId = "getMcpConversation", summary = "Get a Conversation", description = """
                    Returns a single conversation, including its full message history, so a reopened \
                    conversation renders identically to when it was live.
                    Use this tool when a conversation id is already known; do not use listMcpConversations \
                    for this, since its summaries carry no messages.
                    Preconditions: the conversation must exist and belong to the caller.
                    Required inputs: id (conversation id) as a path parameter; there is no request body.
                    Emits a MCP_CONVERSATION_VIEW event.
                    Returns 404 (never 403) when the id does not exist or belongs to another subject, the two \
                    being indistinguishable on purpose.
                    """)
    @ApiResponse(responseCode = "200", description = "Conversation returned")
    @ApiResponse(
            responseCode = "400",
            description = "id path parameter is not a valid UUID",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "No such conversation for the caller",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"mcp:chat:execute"})
    @PreAuthorize("hasAuthority('" + McpPermissions.MCP_CHAT_EXECUTE + "')")
    @EmitEvent(id = "MCP_CONVERSATION_VIEW", apiVersion = "1")
    ResponseEntity<ConversationDetail> get(@PathVariable @NonNull UUID id) {
        return ResponseEntity.ok(conversationService.get(id));
    }

    @PatchMapping("/{id}")
    @Operation(operationId = "updateMcpConversation", summary = "Rename or Pin a Conversation", description = """
                    Applies a partial update to a conversation's title and pinned state; either field may be \
                    omitted.
                    Use this tool to rename or pin a conversation the caller owns; do not use it to add \
                    turns, which appendMcpConversationMessage does.
                    Preconditions: the conversation must exist and belong to the caller.
                    Required inputs: id (conversation id) as a path parameter, plus title (1..120 characters \
                    after trimming) and pinned in the body, either of which may be omitted; a body with \
                    neither set is a no-op 200, not a 400.
                    Emits a MCP_CONVERSATION_UPDATE event.
                    Returns 404 (never 403) when the id does not exist or belongs to another subject.
                    """)
    @ApiResponse(responseCode = "200", description = "Conversation updated (or left unchanged, if no fields set)")
    @ApiResponse(
            responseCode = "400",
            description =
                    "Malformed request (id not a valid UUID, or title outside 1..120 characters " + "after trimming)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "No such conversation for the caller",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"mcp:chat:execute"})
    @PreAuthorize("hasAuthority('" + McpPermissions.MCP_CHAT_EXECUTE + "')")
    @EmitEvent(id = "MCP_CONVERSATION_UPDATE", apiVersion = "1")
    ResponseEntity<ConversationDetail> update(
            @PathVariable @NonNull UUID id,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Fields to change on the conversation; an omitted field leaves the "
                                    + "stored value untouched.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                                            examples = {
                                                @ExampleObject(name = "Rename", value = """
                                                                {"title":"Mechanic roster (renamed)"}
                                                                """),
                                                @ExampleObject(name = "Pin", value = """
                                                                {"pinned":true}
                                                                """)
                                            }))
                    @RequestBody
                    @Valid
                    @NonNull
                    UpdateConversationRequest request) {
        return ResponseEntity.ok(conversationService.update(id, request));
    }

    @DeleteMapping("/{id}")
    @Operation(operationId = "deleteMcpConversation", summary = "Delete a Conversation", description = """
                    Deletes one conversation and every message in it.
                    Use this tool to remove a single conversation the caller owns; do not use \
                    clearMcpConversations, which deletes every conversation the caller has.
                    Preconditions: the conversation must exist and belong to the caller.
                    Required inputs: id (conversation id) as a path parameter; there is no request body.
                    Emits a MCP_CONVERSATION_DELETE event.
                    Returns 204 on success, and 404 (never 403) when the id does not exist or belongs to \
                    another subject.
                    """)
    @ApiResponse(responseCode = "204", description = "Conversation deleted")
    @ApiResponse(
            responseCode = "400",
            description = "id path parameter is not a valid UUID",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "No such conversation for the caller",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"mcp:chat:execute"})
    @PreAuthorize("hasAuthority('" + McpPermissions.MCP_CHAT_EXECUTE + "')")
    @EmitEvent(id = "MCP_CONVERSATION_DELETE", apiVersion = "1")
    ResponseEntity<Void> delete(@PathVariable @NonNull UUID id) {
        conversationService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping
    @Operation(operationId = "clearMcpConversations", summary = "Clear All My Conversations", description = """
                    Deletes every conversation the caller owns, in one call.
                    Use this tool for a deliberate clear-all-history action; do not use it to remove a single \
                    conversation, which deleteMcpConversation does.
                    Preconditions: none; a caller with zero conversations still succeeds.
                    Required inputs: none; there is no request body, and the owner comes from the \
                    authenticated principal.
                    Emits a MCP_CONVERSATION_CLEAR_ALL event.
                    Returns 204 whether or not the caller had any conversations, making the call idempotent.
                    """)
    @ApiResponse(responseCode = "204", description = "All of the caller's conversations deleted")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"mcp:chat:execute"})
    @PreAuthorize("hasAuthority('" + McpPermissions.MCP_CHAT_EXECUTE + "')")
    @EmitEvent(id = "MCP_CONVERSATION_CLEAR_ALL", apiVersion = "1")
    ResponseEntity<Void> deleteAll() {
        conversationService.deleteAll();
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/messages")
    @Operation(
            operationId = "appendMcpConversationMessage",
            summary = "Append a Message to a Conversation",
            description = """
                    Appends a client-authored turn directly to a conversation, for a one-time import of \
                    previously browser-only history or for a non-chat caller.
                    Use this tool only when the caller itself authored the turn; do not call it for a turn \
                    executeMcpChat already returned, which persists its own user and assistant messages, \
                    because re-appending them here duplicates the conversation.
                    Preconditions: the conversation must exist and belong to the caller.
                    Required inputs: id (conversation id) as a path parameter, plus role (user or assistant) \
                    and blocks (at most 64 entries, at most 256 KB serialized) in the body, with an optional \
                    raw-text content fallback.
                    Emits a MCP_CONVERSATION_MESSAGE_APPEND event.
                    Returns 201 with the stored message, and 404 (never 403) when the id does not exist or \
                    belongs to another subject.
                    """)
    @ApiResponse(responseCode = "201", description = "Message appended")
    @ApiResponse(
            responseCode = "400",
            description = "Malformed request (id not a valid UUID, invalid role, more than 64 blocks, "
                    + "an unrecognized block kind, or the serialized body over 256 KB)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "No such conversation for the caller",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"mcp:chat:execute"})
    @PreAuthorize("hasAuthority('" + McpPermissions.MCP_CHAT_EXECUTE + "')")
    @EmitEvent(id = "MCP_CONVERSATION_MESSAGE_APPEND", apiVersion = "1")
    ResponseEntity<ConversationMessage> appendMessage(
            @PathVariable @NonNull UUID id,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "The client-authored turn to store, never a turn executeMcpChat "
                                    + "already persisted.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                                            examples = @ExampleObject(name = "Imported user turn", value = """
                                                                    {"role":"user",
                                                                     "blocks":[{"kind":"markdown","markdown":"How many mechanics do I have?"}],
                                                                     "content":"How many mechanics do I have?"}
                                                                    """)))
                    @RequestBody
                    @Valid
                    @NonNull
                    AppendMessageRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(conversationService.appendMessage(id, request));
    }

    @PostMapping("/{id}/messages/{messageId}/feedback")
    @Operation(operationId = "setMcpMessageFeedback", summary = "Rate an Assistant Answer", description = """
                    Rates one assistant answer helpful or not helpful, with an optional reason and free-text \
                    comment.
                    Use this tool to record a reader's verdict on an answer; a repeat call replaces the \
                    earlier rating in full, so an omitted reason or comment clears the stored value rather \
                    than leaving it untouched.
                    Preconditions: the conversation must exist and belong to the caller, and messageId must \
                    address an assistant answer produced by a chat turn within it, since a client-appended \
                    assistant message cannot be rated.
                    Required inputs: id (conversation id) and messageId as path parameters, messageId being \
                    either the messageId executeMcpChat returned or a ConversationMessage id from this \
                    conversation's history, plus rating in the body, with reason and comment optional.
                    Emits a MCP_MESSAGE_FEEDBACK_SET event.
                    Returns 204 on success, and 404 (never 403) with the same body when the message does not \
                    exist, is in another conversation, is not owned by the caller, belongs to another tenant, \
                    has role user, was appended directly rather than produced by a chat turn, or was purged.
                    """)
    @ApiResponse(responseCode = "204", description = "Rating stored (replaces any prior rating)")
    @ApiResponse(
            responseCode = "400",
            description = "VALIDATION_ERROR: rating missing/blank/unknown, reason unknown or empty, or "
                    + "comment over 1000 characters after trimming; also a non-UUID id or messageId",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "MESSAGE_NOT_FOUND: no ratable assistant message with this id in a conversation "
                    + "the caller owns (also covers a client-appended assistant message, which is not a "
                    + "chat-turn answer)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"mcp:chat:execute"})
    @PreAuthorize("hasAuthority('" + McpPermissions.MCP_CHAT_EXECUTE + "')")
    @EmitEvent(id = "MCP_MESSAGE_FEEDBACK_SET", apiVersion = "1")
    ResponseEntity<Void> setFeedback(
            @PathVariable @NonNull UUID id,
            @PathVariable @NonNull UUID messageId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "The rating to store for this answer, replacing any rating the "
                                    + "caller set earlier.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                                            examples = {
                                                @ExampleObject(name = "Not helpful, with a reason", value = """
                                                                {"rating":"not_helpful","reason":"incorrect","comment":"The mechanic count excluded apprentices."}
                                                                """),
                                                @ExampleObject(name = "Helpful", value = """
                                                                {"rating":"helpful"}
                                                                """)
                                            }))
                    @RequestBody
                    @Valid
                    @NonNull
                    MessageFeedbackRequest request) {
        conversationService.setFeedback(id, messageId, request);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{id}/messages/{messageId}/feedback")
    @Operation(operationId = "clearMcpMessageFeedback", summary = "Withdraw a Rating", description = """
                    Withdraws the caller's rating of one assistant answer.
                    Use this tool to retract a rating set by setMcpMessageFeedback; do not use it to change \
                    one, since setMcpMessageFeedback replaces a rating in full.
                    Preconditions: the conversation must exist and belong to the caller, and messageId must \
                    address an assistant answer produced by a chat turn within it; a client-appended \
                    assistant message cannot be rated, so there is nothing to withdraw and the call answers \
                    404 rather than a silent 204.
                    Required inputs: id (conversation id) and messageId as path parameters; there is no \
                    request body.
                    Emits a MCP_MESSAGE_FEEDBACK_CLEAR event; the call is idempotent and answers 204 even \
                    when the message was never rated.
                    Returns 404 (never 403) when the message does not exist, is in another conversation, is \
                    not owned by the caller, belongs to another tenant, has role user, was appended directly \
                    rather than produced by a chat turn, or was purged.
                    """)
    @ApiResponse(responseCode = "204", description = "Rating cleared, or there was nothing to clear")
    @ApiResponse(
            responseCode = "400",
            description = "id or messageId path parameter is not a valid UUID",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "MESSAGE_NOT_FOUND: no ratable assistant message with this id in a conversation "
                    + "the caller owns (also covers a client-appended assistant message, which is not a "
                    + "chat-turn answer)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"mcp:chat:execute"})
    @PreAuthorize("hasAuthority('" + McpPermissions.MCP_CHAT_EXECUTE + "')")
    @EmitEvent(id = "MCP_MESSAGE_FEEDBACK_CLEAR", apiVersion = "1")
    ResponseEntity<Void> clearFeedback(@PathVariable @NonNull UUID id, @PathVariable @NonNull UUID messageId) {
        conversationService.clearFeedback(id, messageId);
        return ResponseEntity.noContent().build();
    }
}

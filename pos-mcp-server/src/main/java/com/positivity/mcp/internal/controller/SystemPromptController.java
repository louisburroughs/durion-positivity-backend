package com.positivity.mcp.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.mcp.internal.dto.SystemPromptRequest;
import com.positivity.mcp.internal.dto.SystemPromptResponse;
import com.positivity.mcp.internal.security.McpPermissions;
import com.positivity.mcp.internal.service.SystemPromptService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/prompts")
@Tag(name = "System Prompts", description = "Operations for managing MCP system prompts")
class SystemPromptController {

    private final SystemPromptService systemPromptService;

    SystemPromptController(@NonNull SystemPromptService systemPromptService) {
        this.systemPromptService = systemPromptService;
    }

    @GetMapping
    @Operation(operationId = "listSystemPrompts", summary = "List All MCP System Prompts", description = """
                    Returns every reusable MCP system prompt with its name and content.
                    Use this tool to browse prompts or to find a prompt id by name; use getSystemPrompt instead \
                    when the id is already known.
                    Preconditions: none; an empty list simply means no prompts have been created yet.
                    Required inputs: none; there are no filters, no paging and no request body.
                    No events are emitted and no state changes; this is a read-only projection.
                    Returns 200 with the full list of prompts.
                    """)
    @ApiResponse(responseCode = "200", description = "System prompts returned")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"mcp:system_prompt:view"})
    @PreAuthorize("hasAuthority('" + McpPermissions.SYSTEM_PROMPT_VIEW + "')")
    ResponseEntity<List<SystemPromptResponse>> list() {
        return ResponseEntity.ok(systemPromptService.findAll());
    }

    @GetMapping("/{id}")
    @Operation(operationId = "getSystemPrompt", summary = "Get an MCP System Prompt", description = """
                    Returns a single MCP system prompt by its identifier, including its full content.
                    Use this tool when the prompt id (UUID) is already known; use listSystemPrompts instead to \
                    discover ids or find a prompt by name.
                    Preconditions: a system prompt with the given id must exist.
                    Required inputs: id (UUID) as a path parameter; there is no request body.
                    No events are emitted and no state changes; this is a read-only projection.
                    Returns 404 when no system prompt exists for the supplied id.
                    """)
    @ApiResponse(responseCode = "200", description = "System prompt returned")
    @ApiResponse(responseCode = "404", description = "System prompt not found")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"mcp:system_prompt:view"})
    @PreAuthorize("hasAuthority('" + McpPermissions.SYSTEM_PROMPT_VIEW + "')")
    ResponseEntity<SystemPromptResponse> get(@PathVariable @NonNull UUID id) {
        return ResponseEntity.ok(systemPromptService.get(id));
    }

    @PostMapping
    @Operation(operationId = "createSystemPrompt", summary = "Create an MCP System Prompt", description = """
                    Creates a reusable MCP system prompt that agent orchestration can assign to assistant agents.
                    Use this tool to add a new named prompt; do not use updateSystemPrompt, which edits a prompt \
                    that already exists.
                    Preconditions: no prompt may already use the requested name, which is unique across prompts.
                    Required inputs: name (unique, human-readable) and content (the prompt text); both are \
                    mandatory and non-blank.
                    Emits a MCP_SYSTEM_PROMPT_CREATE event and invalidates any cached agents built from a prompt \
                    of that name so they rebuild with the new content.
                    Returns 201 with the stored prompt, and 409 when a prompt with the same name already exists.
                    """)
    @ApiResponse(responseCode = "201", description = "System prompt created")
    @ApiResponse(responseCode = "409", description = "A system prompt with the requested name already exists")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"mcp:system_prompt:create"})
    @PreAuthorize("hasAuthority('" + McpPermissions.SYSTEM_PROMPT_CREATE + "')")
    @EmitEvent(id = "MCP_SYSTEM_PROMPT_CREATE", apiVersion = "1")
    ResponseEntity<SystemPromptResponse> create(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "The named prompt text to store for assignment to assistant agents.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples =
                                                    @ExampleObject(name = "Workorder assistant prompt", value = """
                                                                    {"name":"Workorder Assistant",
                                                                     "content":"You are a helpful assistant that creates workorders."}
                                                                    """)))
                    @Validated
                    @RequestBody
                    @NonNull
                    SystemPromptRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(systemPromptService.create(request));
    }

    @PutMapping("/{id}")
    @Operation(operationId = "updateSystemPrompt", summary = "Update an MCP System Prompt", description = """
                    Replaces the name and content of an existing MCP system prompt.
                    Use this tool to edit prompt text or rename a prompt; do not use createSystemPrompt, which \
                    adds a new prompt alongside the existing ones.
                    Preconditions: the prompt must exist, and when the name is being changed no other prompt may \
                    already use the new name.
                    Required inputs: id (UUID) as a path parameter plus name and content in the body; both body \
                    fields are mandatory and fully replace the stored values.
                    Emits a MCP_SYSTEM_PROMPT_UPDATE event and invalidates cached agents built from the prompt so \
                    subsequent chats use the new content.
                    Returns 200 with the updated prompt, 404 when no prompt exists for the id, and 409 when the \
                    new name is already used by another prompt.
                    """)
    @ApiResponse(responseCode = "200", description = "System prompt updated")
    @ApiResponse(responseCode = "404", description = "System prompt not found")
    @ApiResponse(responseCode = "409", description = "A system prompt with the requested name already exists")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"mcp:system_prompt:update"})
    @PreAuthorize("hasAuthority('" + McpPermissions.SYSTEM_PROMPT_UPDATE + "')")
    @EmitEvent(id = "MCP_SYSTEM_PROMPT_UPDATE", apiVersion = "1")
    ResponseEntity<SystemPromptResponse> update(
            @PathVariable @NonNull UUID id,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Full replacement name and content for the stored prompt.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "Revised assistant prompt", value = """
                                                                    {"name":"Workorder Assistant",
                                                                     "content":"You are a concise assistant that creates and updates workorders."}
                                                                    """)))
                    @Validated
                    @RequestBody
                    @NonNull
                    SystemPromptRequest request) {
        return ResponseEntity.ok(systemPromptService.update(id, request));
    }

    @DeleteMapping("/{id}")
    @Operation(operationId = "deleteSystemPrompt", summary = "Delete an MCP System Prompt", description = """
                    Deletes an MCP system prompt so it can no longer be assigned to agent sessions.
                    Use this tool to retire a prompt; do not use updateSystemPrompt, which keeps the prompt and \
                    changes its fields.
                    Preconditions: none; deleting an id that does not exist is a no-op.
                    Required inputs: id (UUID) as a path parameter; there is no request body.
                    Emits a MCP_SYSTEM_PROMPT_DELETE event and, when the prompt existed, invalidates cached agents \
                    that were built from it.
                    Returns 204 whether or not the prompt existed, making the call idempotent.
                    """)
    @ApiResponse(responseCode = "204", description = "System prompt deleted")
    @ApiResponse(responseCode = "404", description = "System prompt not found")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"mcp:system_prompt:delete"})
    @PreAuthorize("hasAuthority('" + McpPermissions.SYSTEM_PROMPT_DELETE + "')")
    @EmitEvent(id = "MCP_SYSTEM_PROMPT_DELETE", apiVersion = "1")
    ResponseEntity<Void> delete(@PathVariable @NonNull UUID id) {
        systemPromptService.delete(id);
        return ResponseEntity.noContent().build();
    }
}

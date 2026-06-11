package com.positivity.mcp.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.mcp.internal.dto.SystemPromptRequest;
import com.positivity.mcp.internal.dto.SystemPromptResponse;
import com.positivity.mcp.internal.security.McpPermissions;
import com.positivity.mcp.service.SystemPromptService;
import io.swagger.v3.oas.annotations.Operation;
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
    @Operation(
            summary = "List system prompts",
            description = "List all configured MCP system prompts that can be assigned to agents")
    @ApiResponse(responseCode = "200", description = "System prompts returned")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"mcp:system_prompt:view"})
    @PreAuthorize("hasAuthority('" + McpPermissions.SYSTEM_PROMPT_VIEW + "')")
    ResponseEntity<List<SystemPromptResponse>> list() {
        return ResponseEntity.ok(systemPromptService.findAll());
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get system prompt", description = "Retrieve a single MCP system prompt by its identifier")
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
    @Operation(
            summary = "Create system prompt",
            description = "Create a new MCP system prompt that can be used during agent orchestration")
    @ApiResponse(responseCode = "201", description = "System prompt created")
    @ApiResponse(responseCode = "400", description = "System prompt request is invalid")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"mcp:system_prompt:create"})
    @PreAuthorize("hasAuthority('" + McpPermissions.SYSTEM_PROMPT_CREATE + "')")
    @EmitEvent(id = "MCP_SYSTEM_PROMPT_CREATE", apiVersion = "1")
    ResponseEntity<SystemPromptResponse> create(@Validated @RequestBody @NonNull SystemPromptRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(systemPromptService.create(request));
    }

    @PutMapping("/{id}")
    @Operation(
            summary = "Update system prompt",
            description = "Update an existing MCP system prompt by replacing its editable fields")
    @ApiResponse(responseCode = "200", description = "System prompt updated")
    @ApiResponse(responseCode = "404", description = "System prompt not found")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"mcp:system_prompt:update"})
    @PreAuthorize("hasAuthority('" + McpPermissions.SYSTEM_PROMPT_UPDATE + "')")
    @EmitEvent(id = "MCP_SYSTEM_PROMPT_UPDATE", apiVersion = "1")
    ResponseEntity<SystemPromptResponse> update(
            @PathVariable @NonNull UUID id, @Validated @RequestBody @NonNull SystemPromptRequest request) {
        return ResponseEntity.ok(systemPromptService.update(id, request));
    }

    @DeleteMapping("/{id}")
    @Operation(
            summary = "Delete system prompt",
            description = "Delete an MCP system prompt so it can no longer be selected for agent sessions")
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

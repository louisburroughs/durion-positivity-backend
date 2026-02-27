package com.positivity.mcp.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.mcp.internal.dto.SystemPromptRequest;
import com.positivity.mcp.internal.dto.SystemPromptResponse;
import com.positivity.mcp.internal.security.McpPermissions;
import com.positivity.mcp.internal.service.SystemPromptService;
import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.access.prepost.PreAuthorize;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1/prompts")
class SystemPromptController {

    private final SystemPromptService systemPromptService;

    SystemPromptController(@NonNull SystemPromptService systemPromptService) {
        this.systemPromptService = systemPromptService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('" + McpPermissions.SYSTEM_PROMPT_VIEW + "')")
    ResponseEntity<List<SystemPromptResponse>> list() {
        return ResponseEntity.ok(systemPromptService.findAll());
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('" + McpPermissions.SYSTEM_PROMPT_VIEW + "')")
    ResponseEntity<SystemPromptResponse> get(@PathVariable @NonNull UUID id) {
        return ResponseEntity.ok(systemPromptService.get(id));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('" + McpPermissions.SYSTEM_PROMPT_CREATE + "')")
    @EmitEvent(id = "MCP_SYSTEM_PROMPT_CREATE", apiVersion = "1")
    ResponseEntity<SystemPromptResponse> create(@Validated @RequestBody @NonNull SystemPromptRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(systemPromptService.create(request));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('" + McpPermissions.SYSTEM_PROMPT_UPDATE + "')")
    @EmitEvent(id = "MCP_SYSTEM_PROMPT_UPDATE", apiVersion = "1")
    ResponseEntity<SystemPromptResponse> update(@PathVariable @NonNull UUID id,
                                                @Validated @RequestBody @NonNull SystemPromptRequest request) {
        return ResponseEntity.ok(systemPromptService.update(id, request));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('" + McpPermissions.SYSTEM_PROMPT_DELETE + "')")
    @EmitEvent(id = "MCP_SYSTEM_PROMPT_DELETE", apiVersion = "1")
    ResponseEntity<Void> delete(@PathVariable @NonNull UUID id) {
        systemPromptService.delete(id);
        return ResponseEntity.noContent().build();
    }
}

package com.positivity.mcp.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.mcp.internal.dto.LlmApiConfigRequest;
import com.positivity.mcp.internal.dto.LlmApiConfigResponse;
import com.positivity.mcp.internal.security.McpPermissions;
import com.positivity.mcp.service.LlmApiConfigService;
import io.swagger.v3.oas.annotations.Operation;
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
@RequestMapping("/v1/llm-apis")
@Tag(name = "LLM API Configuration", description = "LLM provider API configuration management")
class LlmApiConfigController {

    private final LlmApiConfigService service;

    LlmApiConfigController(@NonNull LlmApiConfigService service) {
        this.service = service;
    }

    @GetMapping
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth", scopes = { "mcp:llm_api:view" })
    @PreAuthorize("hasAuthority('" + McpPermissions.LLM_API_VIEW + "')")
    @Operation(summary = "List LLM API configurations", description = "Retrieve all configured LLM API provider definitions that are available to the MCP server.", tags = {
            "LLM API Configuration" })
    ResponseEntity<List<LlmApiConfigResponse>> list() {
        return ResponseEntity.ok(service.list());
    }

    @GetMapping("/{id}")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth", scopes = { "mcp:llm_api:view" })
    @PreAuthorize("hasAuthority('" + McpPermissions.LLM_API_VIEW + "')")
    @Operation(summary = "Get an LLM API configuration", description = "Retrieve a single LLM API provider configuration by its identifier.", tags = {
            "LLM API Configuration" })
    ResponseEntity<LlmApiConfigResponse> get(@PathVariable @NonNull UUID id) {
        return ResponseEntity.ok(service.get(id));
    }

    @PostMapping
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth", scopes = { "mcp:llm_api:create" })
    @PreAuthorize("hasAuthority('" + McpPermissions.LLM_API_CREATE + "')")
    @EmitEvent(id = "MCP_LLM_API_CREATE", apiVersion = "1")
    @Operation(summary = "Create an LLM API configuration", description = "Create a new LLM API provider configuration for use by the MCP server.", tags = {
            "LLM API Configuration" })
    ResponseEntity<LlmApiConfigResponse> create(@RequestBody @Validated @NonNull LlmApiConfigRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(request));
    }

    @PutMapping("/{id}")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth", scopes = { "mcp:llm_api:update" })
    @PreAuthorize("hasAuthority('" + McpPermissions.LLM_API_UPDATE + "')")
    @EmitEvent(id = "MCP_LLM_API_UPDATE", apiVersion = "1")
    @Operation(summary = "Update an LLM API configuration", description = "Update an existing LLM API provider configuration by its identifier.", tags = {
            "LLM API Configuration" })
    ResponseEntity<LlmApiConfigResponse> update(
            @PathVariable @NonNull UUID id, @RequestBody @Validated @NonNull LlmApiConfigRequest request) {
        return ResponseEntity.ok(service.update(id, request));
    }

    @DeleteMapping("/{id}")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth", scopes = { "mcp:llm_api:delete" })
    @PreAuthorize("hasAuthority('" + McpPermissions.LLM_API_DELETE + "')")
    @EmitEvent(id = "MCP_LLM_API_DELETE", apiVersion = "1")
    @Operation(summary = "Delete an LLM API configuration", description = "Delete an existing LLM API provider configuration by its identifier.", tags = {
            "LLM API Configuration" })
    ResponseEntity<Void> delete(@PathVariable @NonNull UUID id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}

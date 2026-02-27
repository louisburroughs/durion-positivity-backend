package com.positivity.mcp.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.mcp.internal.dto.LlmApiConfigRequest;
import com.positivity.mcp.internal.dto.LlmApiConfigResponse;
import com.positivity.mcp.internal.security.McpPermissions;
import com.positivity.mcp.internal.service.LlmApiConfigService;
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
@RequestMapping("/v1/llm-apis")
class LlmApiConfigController {

    private final LlmApiConfigService service;

    LlmApiConfigController(@NonNull LlmApiConfigService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('" + McpPermissions.LLM_API_VIEW + "')")
    ResponseEntity<List<LlmApiConfigResponse>> list() {
        return ResponseEntity.ok(service.list());
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('" + McpPermissions.LLM_API_VIEW + "')")
    ResponseEntity<LlmApiConfigResponse> get(@PathVariable @NonNull UUID id) {
        return ResponseEntity.ok(service.get(id));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('" + McpPermissions.LLM_API_CREATE + "')")
    @EmitEvent(id = "MCP_LLM_API_CREATE", apiVersion = "1")
    ResponseEntity<LlmApiConfigResponse> create(@RequestBody @Validated @NonNull LlmApiConfigRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(request));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('" + McpPermissions.LLM_API_UPDATE + "')")
    @EmitEvent(id = "MCP_LLM_API_UPDATE", apiVersion = "1")
    ResponseEntity<LlmApiConfigResponse> update(@PathVariable @NonNull UUID id,
                                                @RequestBody @Validated @NonNull LlmApiConfigRequest request) {
        return ResponseEntity.ok(service.update(id, request));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('" + McpPermissions.LLM_API_DELETE + "')")
    @EmitEvent(id = "MCP_LLM_API_DELETE", apiVersion = "1")
    ResponseEntity<Void> delete(@PathVariable @NonNull UUID id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}

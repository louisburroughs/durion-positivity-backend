package com.positivity.mcp.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.mcp.internal.dto.LlmApiConfigRequest;
import com.positivity.mcp.internal.dto.LlmApiConfigResponse;
import com.positivity.mcp.internal.security.McpPermissions;
import com.positivity.mcp.internal.service.LlmApiConfigService;
import com.positivity.shared.error.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
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
@RequestMapping("/v1/llm-apis")
@Tag(name = "LLM API Configuration", description = "LLM provider API configuration management")
class LlmApiConfigController {

    private static final String CONFIG_EXAMPLE = """
            {"apiId":"openai-gpt4o",
             "model":"gpt-4o",
             "baseUrl":"https://api.openai.com/v1",
             "apiKey":"sk-proj-xxxxxxxxxxxxxxxx",
             "headers":{"X-Org-Id":"org-01960003"}}
            """;

    private final LlmApiConfigService service;

    LlmApiConfigController(@NonNull LlmApiConfigService service) {
        this.service = service;
    }

    @GetMapping
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"mcp:llm_api:view"})
    @PreAuthorize("hasAuthority('" + McpPermissions.LLM_API_VIEW + "')")
    @Operation(
            operationId = "listLlmApiConfigs",
            summary = "List All LLM API Configurations",
            description = """
                    Returns every configured LLM provider API definition available to the MCP server, including \
                    provider base URL and model.
                    Use this tool to enumerate provider configurations or to find a configuration id; use \
                    getLlmApiConfig instead when the id is already known.
                    Preconditions: none; an empty list simply means no provider has been configured yet.
                    Required inputs: none; there are no filters, no paging and no request body.
                    No events are emitted and no state changes; this is a read-only projection.
                    Returns 200 with the full list of configurations.
                    """,
            tags = {"LLM API Configuration"})
    ResponseEntity<List<LlmApiConfigResponse>> list() {
        return ResponseEntity.ok(service.list());
    }

    @GetMapping("/{id}")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"mcp:llm_api:view"})
    @PreAuthorize("hasAuthority('" + McpPermissions.LLM_API_VIEW + "')")
    @Operation(
            operationId = "getLlmApiConfig",
            summary = "Get an LLM API Configuration",
            description = """
                    Returns a single LLM provider API configuration by its identifier.
                    Use this tool when the configuration id (UUID) is already known; use listLlmApiConfigs instead \
                    to discover ids.
                    Preconditions: a configuration with the given id must exist.
                    Required inputs: id (UUID) as a path parameter; there is no request body.
                    No events are emitted and no state changes; this is a read-only projection.
                    Returns 200 when the configuration exists, and 404 LLM_API_NOT_FOUND in the ApiError \
                    envelope when the id is unknown.
                    """,
            tags = {"LLM API Configuration"})
    @ApiResponse(responseCode = "200", description = "The configuration for the requested id.")
    @ApiResponse(
            responseCode = "404",
            description = "No LLM API configuration exists for the requested id (LLM_API_NOT_FOUND).",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    ResponseEntity<LlmApiConfigResponse> get(@PathVariable @NonNull UUID id) {
        return ResponseEntity.ok(service.get(id));
    }

    @PostMapping
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"mcp:llm_api:create"})
    @PreAuthorize("hasAuthority('" + McpPermissions.LLM_API_CREATE + "')")
    @EmitEvent(id = "MCP_LLM_API_CREATE", apiVersion = "1")
    @Operation(
            operationId = "createLlmApiConfig",
            summary = "Create an LLM API Configuration",
            description = """
                    Creates a new LLM provider API configuration that the MCP server can use to invoke an external \
                    model.
                    Use this tool to register a new provider endpoint and credential; do not use \
                    updateLlmApiConfig, which modifies a configuration that already exists.
                    Preconditions: no configuration may already use the requested apiId, which is unique across \
                    configurations.
                    Required inputs: apiId (stable string identifier), model, baseUrl and apiKey; headers is an \
                    optional map of extra HTTP headers and defaults to empty.
                    Emits a MCP_LLM_API_CREATE event and stores the credential for later provider calls.
                    Returns 201 with the stored configuration, and 409 LLM_API_ID_CONFLICT in the ApiError \
                    envelope when another configuration already uses the requested apiId.
                    """,
            tags = {"LLM API Configuration"})
    @ApiResponse(responseCode = "201", description = "The stored configuration.")
    @ApiResponse(
            responseCode = "409",
            description = "Another configuration already uses the requested apiId (LLM_API_ID_CONFLICT).",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    ResponseEntity<LlmApiConfigResponse> create(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "LLM provider endpoint, model and credential to register.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples =
                                                    @ExampleObject(name = "OpenAI provider", value = CONFIG_EXAMPLE)))
                    @RequestBody
                    @Validated
                    @NonNull
                    LlmApiConfigRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(request));
    }

    @PutMapping("/{id}")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"mcp:llm_api:update"})
    @PreAuthorize("hasAuthority('" + McpPermissions.LLM_API_UPDATE + "')")
    @EmitEvent(id = "MCP_LLM_API_UPDATE", apiVersion = "1")
    @Operation(
            operationId = "updateLlmApiConfig",
            summary = "Update an LLM API Configuration",
            description = """
                    Replaces every editable field of an existing LLM provider API configuration identified by id.
                    Use this tool to change a provider's model, base URL, credential or headers; do not use \
                    createLlmApiConfig, which registers an additional configuration.
                    Preconditions: the configuration must exist, and when apiId is being changed the new apiId \
                    must not already be used by another configuration.
                    Required inputs: id (UUID) as a path parameter plus a full body with apiId, model, baseUrl and \
                    apiKey; headers defaults to empty when omitted, so a partial body is not merged.
                    Emits a MCP_LLM_API_UPDATE event.
                    Returns 200 with the updated configuration, 404 LLM_API_NOT_FOUND when the id is unknown, \
                    and 409 LLM_API_ID_CONFLICT when another configuration already uses the requested apiId — \
                    both in the ApiError envelope.
                    """,
            tags = {"LLM API Configuration"})
    @ApiResponse(responseCode = "200", description = "The updated configuration.")
    @ApiResponse(
            responseCode = "404",
            description = "No LLM API configuration exists for the requested id (LLM_API_NOT_FOUND).",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "409",
            description = "Another configuration already uses the requested apiId (LLM_API_ID_CONFLICT).",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    ResponseEntity<LlmApiConfigResponse> update(
            @PathVariable @NonNull UUID id,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Full replacement provider endpoint, model and credential values.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples =
                                                    @ExampleObject(
                                                            name = "Updated OpenAI provider",
                                                            value = CONFIG_EXAMPLE)))
                    @RequestBody
                    @Validated
                    @NonNull
                    LlmApiConfigRequest request) {
        return ResponseEntity.ok(service.update(id, request));
    }

    @DeleteMapping("/{id}")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"mcp:llm_api:delete"})
    @PreAuthorize("hasAuthority('" + McpPermissions.LLM_API_DELETE + "')")
    @EmitEvent(id = "MCP_LLM_API_DELETE", apiVersion = "1")
    @Operation(
            operationId = "deleteLlmApiConfig",
            summary = "Delete an LLM API Configuration",
            description = """
                    Deletes an LLM provider API configuration so the MCP server can no longer use it to invoke \
                    that provider.
                    Use this tool to retire a provider configuration; do not use updateLlmApiConfig, which keeps \
                    the configuration and changes its fields.
                    Preconditions: none; deleting an id that does not exist is a no-op.
                    Required inputs: id (UUID) as a path parameter; there is no request body.
                    Emits a MCP_LLM_API_DELETE event.
                    Returns 204 whether or not the configuration existed, making the call idempotent.
                    """,
            tags = {"LLM API Configuration"})
    ResponseEntity<Void> delete(@PathVariable @NonNull UUID id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}

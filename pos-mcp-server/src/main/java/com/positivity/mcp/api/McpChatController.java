package com.positivity.mcp.api;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.client.McpSyncClient;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Simple HTTP API for chatbot clients to send queries to the MCP server.
 * <p>
 * Chatbots can POST a JSON payload to {@code /v1/mcp/chat} specifying
 * which MCP tool to call and the arguments to pass. The controller will
 * synchronously call the tool via {@link McpSyncClient} and return the
 * {@link McpSchema.CallToolResult} as JSON.
 */
@Tag(name = "MCP Chat API", description = "Model Context Protocol (MCP) chatbot endpoint for invoking MCP tools")
@RestController
@RequestMapping("/v1/mcp")
public class McpChatController {

    private static final Logger logger = LoggerFactory.getLogger(McpChatController.class);

    private final McpSyncClient mcpSyncClient;
    private final ObjectMapper objectMapper;

    public McpChatController(McpSyncClient mcpSyncClient, ObjectMapper objectMapper) {
        this.mcpSyncClient = mcpSyncClient;
        this.objectMapper = objectMapper;
    }

    @Operation(summary = "Execute MCP Tool", description = "Synchronously execute an MCP tool with the provided arguments and return the result")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Tool execution succeeded", content = @Content(mediaType = "application/json", schema = @Schema(type = "object", description = "MCP CallToolResult containing tool output"), examples = @ExampleObject(value = "{\"content\": [{\"type\": \"text\", \"text\": \"Pong from positivity-ping\"}], \"isError\": false}"))),
            @ApiResponse(responseCode = "400", description = "Invalid request - missing or empty toolName", content = @Content(mediaType = "text/plain", examples = @ExampleObject(value = "'toolName' is required"))),
            @ApiResponse(responseCode = "500", description = "Tool execution failed", content = @Content(mediaType = "text/plain", examples = @ExampleObject(value = "Failed to execute MCP tool: Tool not found")))
    })
    @PostMapping("/chat")
    public ResponseEntity<?> chat(
            @RequestBody @Schema(name = "ChatRequest", description = "Request to execute an MCP tool", example = "{\"toolName\": \"positivity-ping\", \"arguments\": {}}") ChatRequest request) {
        if (request == null || request.toolName() == null || request.toolName().isBlank()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body("'toolName' is required");
        }

        JsonNode arguments = request.arguments();
        if (arguments == null) {
            arguments = objectMapper.createObjectNode();
        }

        try {
            // Convert JsonNode arguments to Map for CallToolRequest

            var argsMap = objectMapper.convertValue(arguments, java.util.Map.class);
            @SuppressWarnings("unchecked")
            McpSchema.CallToolRequest callToolRequest = new McpSchema.CallToolRequest(
                    request.toolName(),
                    argsMap);

            McpSchema.CallToolResult response = mcpSyncClient.callTool(callToolRequest);
            return ResponseEntity.ok(response);
        } catch (Exception ex) {
            logger.error("Failed to execute MCP tool '{}'", request.toolName(), ex);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Failed to execute MCP tool: " + ex.getMessage());
        }
    }

    /**
     * Request payload for {@link McpChatController#chat(ChatRequest)}.
     * <p>
     * Example:
     * {@code {"toolName": "positivity-ping", "arguments": {}}}
     */
    @Schema(name = "ChatRequest", description = "Payload for MCP tool execution request", example = "{\"toolName\": \"positivity-ping\", \"arguments\": {}}")
    public record ChatRequest(
            @Schema(description = "Name of the MCP tool to execute", example = "positivity-ping", requiredMode = Schema.RequiredMode.REQUIRED) String toolName,

            @Schema(description = "Arguments to pass to the MCP tool (optional, defaults to empty object)", example = "{}", requiredMode = Schema.RequiredMode.NOT_REQUIRED) ObjectNode arguments) {
    }
}

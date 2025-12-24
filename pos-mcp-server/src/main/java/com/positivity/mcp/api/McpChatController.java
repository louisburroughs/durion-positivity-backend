package com.positivity.mcp.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.mcp.server.McpSyncServer;
import io.mcp.server.tool.ToolResponse;
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
 * Chatbots can POST a JSON payload to {@code /api/mcp/chat} specifying
 * which MCP tool to call and the arguments to pass. The controller will
 * synchronously call the tool via {@link McpSyncServer} and return the
 * {@link ToolResponse} as JSON.
 */
@RestController
@RequestMapping("/api/mcp")
public class McpChatController {

    private static final Logger logger = LoggerFactory.getLogger(McpChatController.class);

    private final McpSyncServer mcpSyncServer;
    private final ObjectMapper objectMapper;

    public McpChatController(McpSyncServer mcpSyncServer, ObjectMapper objectMapper) {
        this.mcpSyncServer = mcpSyncServer;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/chat")
    public ResponseEntity<?> chat(@RequestBody ChatRequest request) {
        if (request == null || request.toolName() == null || request.toolName().isBlank()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body("'toolName' is required");
        }

        JsonNode arguments = request.arguments();
        if (arguments == null) {
            arguments = objectMapper.createObjectNode();
        }

        try {
            ToolResponse response = mcpSyncServer.callTool(request.toolName(), arguments);
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
    public record ChatRequest(String toolName, ObjectNode arguments) {
    }
}

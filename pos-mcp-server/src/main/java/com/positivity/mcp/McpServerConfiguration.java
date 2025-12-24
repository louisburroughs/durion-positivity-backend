package com.positivity.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mcp.json.JsonSchema;
import io.mcp.server.McpServer;
import io.mcp.server.McpServerBuilder;
import io.mcp.server.McpSyncServer;
import io.mcp.server.tool.Tool;
import io.mcp.server.tool.ToolResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;

/**
 * Central configuration for the Durion Positivity MCP server.
 * <p>
 * Exposes both the reactive {@link McpServer} and a blocking
 * {@link McpSyncServer} facade so that HTTP controllers can
 * synchronously call MCP tools on behalf of chatbot clients.
 */
@Configuration
public class McpServerConfiguration {

    private static final Logger logger = LoggerFactory.getLogger(McpServerConfiguration.class);

    @Bean
    public McpServer mcpServer(ObjectMapper objectMapper) {
        McpServer server = McpServerBuilder.builder()
                .objectMapper(objectMapper)
                .serverInfo("durion-positivity-mcp-server", "0.0.1")
                .capabilities(capabilities -> capabilities
                        .tools(true)
                        .resources(false)
                        .prompts(false)
                )
                .build();

        // Simple health-check style tool that can be used by
        // front-end and back-end agents to verify connectivity.
        Tool pingTool = Tool.builder()
                .name("positivity-ping")
                .description("Simple health check tool for the Durion Positivity MCP server.")
                .inputSchema(JsonSchema.object())
                .build();

        server.addToolHandler("positivity-ping", arguments ->
                Mono.just(
                        ToolResponse.success()
                                .addTextContent("Durion Positivity MCP server is running and reachable.")
                                .build()
                )
        );

        server.registerTool(pingTool).subscribe(
                unused -> logger.debug("Registered tool 'positivity-ping'"),
                error -> logger.error("Failed to register MCP tool 'positivity-ping'", error)
        );

        return server;
    }

    @Bean
    public McpSyncServer mcpSyncServer(McpServer server) {
        return server.toSyncServer();
    }
}

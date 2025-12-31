package com.positivity.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpServerTransportProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Central configuration for the Durion Positivity MCP server.
 * <p>
 * Exposes a blocking {@link McpSyncServer} facade so that HTTP controllers can
 * synchronously call MCP tools on behalf of chatbot clients.
 */
@Configuration
public class McpServerConfiguration {

        private static final Logger logger = LoggerFactory.getLogger(McpServerConfiguration.class);

        @Bean
        public McpSyncServer mcpSyncServer(ObjectMapper objectMapper) {
                // Define the ping tool with empty object schema
                McpSchema.Tool pingTool = McpSchema.Tool.builder()
                                .name("positivity-ping")
                                .description("Simple health check tool for the Durion Positivity MCP server.")
                                .inputSchema(new McpSchema.JsonSchema("object", java.util.Collections.emptyMap(), null,
                                                null, null, null))
                                .build();

                // Build the sync server with the ping tool
                McpJsonMapper jsonMapper = new JacksonMcpJsonMapper(objectMapper);
                McpServerTransportProvider transportProvider = new StdioServerTransportProvider(jsonMapper);
                McpSyncServer server = McpServer.sync(transportProvider)
                                .serverInfo("durion-positivity-mcp-server", "0.0.1")
                                .capabilities(McpSchema.ServerCapabilities.builder()
                                                .tools(true)
                                                .build())
                                .toolCall(pingTool, (exchange, request) -> McpSchema.CallToolResult.builder()
                                                .addContent(new McpSchema.TextContent(
                                                                "Durion Positivity MCP server is running and reachable."))
                                                .build())
                                .build();

                logger.info("MCP Server initialized with 'positivity-ping' tool");
                return server;
        }
}

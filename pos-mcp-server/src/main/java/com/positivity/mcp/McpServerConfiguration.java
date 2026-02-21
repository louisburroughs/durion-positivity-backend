package com.positivity.mcp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson.JacksonMcpJsonMapper;
import io.modelcontextprotocol.json.jackson.JacksonMcpJsonMapperSupplier;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpServerTransportProvider;

/**
 * Central configuration for the Durion Positivity MCP server.
 * <p>
 * Exposes a blocking {@link McpSyncServer} facade so that HTTP controllers can
 * synchronously call MCP tools on behalf of chatbot clients.
 */
@Configuration
@EnableConfigurationProperties(McpServerProperties.class)
public class McpServerConfiguration {

        private static final Logger logger = LoggerFactory.getLogger(McpServerConfiguration.class);
        private final McpJsonMapper jsonMapper;
        private final McpServerTransportProvider transportProvider;

        public McpServerConfiguration() {
                this.jsonMapper = new JacksonMcpJsonMapperSupplier().get();
                this.transportProvider = new StdioServerTransportProvider(jsonMapper);
        }

        // public static McpServerFeatures.SyncToolSpecification logPromptTool() {
        // McpSchema.JsonSchema inputSchema = new McpSchema.JsonSchema("object",
        // Map.of("prompt", String.class), List.of("prompt"), false, null, null);

        // return new McpServerFeatures.SyncToolSpecification(
        // new McpSchema.Tool(
        // "logPrompt", "Log Prompt", "Logs a provided prompt", inputSchema, null,
        // null, null),
        // (exchange, args) -> {
        // String prompt = (String) args.get("prompt");
        // return McpSchema.CallToolResult.builder()
        // .content(List.of(new McpSchema.TextContent(
        // "Input Prompt: " + prompt)))
        // .isError(false)
        // .build();
        // });
        // }

        // public static McpSyncServer createServer() {
        // JacksonMcpJsonMapper jsonMapper = new JacksonMcpJsonMapper(new
        // ObjectMapper());
        // StdioServerTransportProvider transportProvider = new
        // StdioServerTransportProvider(
        // jsonMapper);

        // return McpServer.sync(transportProvider)
        // .serverInfo("baeldung-demo-server", "0.0.1")
        // .capabilities(McpSchema.ServerCapabilities.builder()
        // .tools(true)
        // .logging()
        // .build())
        // .tools(LoggingTool.logPromptTool())
        // .build();
        // }

        @Bean
        public McpSyncServer mcpSyncServer() {
                // Define the ping tool with empty object schema
                McpSchema.Tool pingTool = McpSchema.Tool.builder()
                                .name("positivity-ping")
                                .description("Simple health check tool for the Durion Positivity MCP server.")
                                .inputSchema(new McpSchema.JsonSchema("object", java.util.Collections.emptyMap(), null,
                                                null, null, null))
                                .build();

                // Build the sync server with the ping tool
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

        @Bean(destroyMethod = "close")
        public static McpSyncClient getClient() {
                ServerParameters params = ServerParameters
                                .builder("npx")
                                .args("-y", "@modelcontextprotocol/server-everything")
                                .build();

                JacksonMcpJsonMapper jacksonMapper = new JacksonMcpJsonMapper(new ObjectMapper());
                McpClientTransport transport = new StdioClientTransport(params, jacksonMapper);

                return io.modelcontextprotocol.client.McpClient.sync(transport)
                                .build();

        }
}

package com.positivity.mcp.internal.config;

import io.modelcontextprotocol.server.transport.HttpServletSseServerTransportProvider;
import org.jspecify.annotations.NonNull;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "mcp.server")
public record McpServerProperties(
        @NonNull String baseUrl,
        @NonNull String messageEndpoint,
        @NonNull String sseEndpoint,
        @NonNull String openApiPath,
        Duration discoveryTimeout
) {
    public McpServerProperties {
        if (baseUrl == null) {
            baseUrl = "http://localhost:8080";
        }
        if (messageEndpoint == null) {
            messageEndpoint = "/mcp/message";
        }
        if (sseEndpoint == null) {
            sseEndpoint = HttpServletSseServerTransportProvider.DEFAULT_SSE_ENDPOINT;
        }
        if (openApiPath == null) {
            openApiPath = "/v3/api-docs";
        }
        if (discoveryTimeout == null) {
            discoveryTimeout = Duration.ofSeconds(5);
        }
    }
}

package com.positivity.mcp.internal.config;

import io.modelcontextprotocol.server.transport.HttpServletSseServerTransportProvider;
import java.time.Duration;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "mcp.server")
public record McpServerProperties(
        @NonNull String baseUrl,
        @NonNull String messageEndpoint,
        @NonNull String sseEndpoint,
        @NonNull String openApiPath,
        Duration discoveryTimeout,
        @NonNull List<String> includedServices,
        @NonNull List<String> includedPathPrefixes,
        String aggregateSpecUrl,
        @NonNull List<String> excludedPathFragments) {
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
        if (includedServices == null) {
            includedServices = List.of();
        }
        if (includedPathPrefixes == null) {
            includedPathPrefixes = List.of();
        }
        if (excludedPathFragments == null) {
            excludedPathFragments = List.of();
        }
    }

    public boolean includesService(@NonNull String serviceId) {
        if (includedServices.isEmpty()) {
            return true;
        }
        return includedServices.stream().anyMatch(candidate -> candidate.equalsIgnoreCase(serviceId));
    }

    public boolean includesPath(@NonNull String path) {
        if (includedPathPrefixes.isEmpty()) {
            return true;
        }
        return includedPathPrefixes.stream().anyMatch(path::startsWith);
    }

    public boolean excludesPath(@NonNull String path) {
        return excludedPathFragments.stream().anyMatch(path::contains);
    }
}

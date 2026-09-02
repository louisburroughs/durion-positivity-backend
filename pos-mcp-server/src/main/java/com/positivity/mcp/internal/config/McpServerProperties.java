package com.positivity.mcp.internal.config;

import io.modelcontextprotocol.server.transport.HttpServletSseServerTransportProvider;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param specIdentityAliases per-domain extra title tokens accepted by the spec-identity guard
 *     (#1632 follow-up), keyed by routing token — either its natural spelling (routing prefix
 *     without the leading slash, e.g. {@code vehicle-fitment}) or its normalized form
 *     ({@code vehiclefitment}); the guard honors both. Needed where a service's OpenAPI
 *     {@code info.title} does not contain its routing token — e.g. pos-catalog's title says
 *     "Product" and pos-people's says "Human Resources".
 */
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
        @NonNull List<String> excludedPathFragments,
        @NonNull Map<String, List<String>> specIdentityAliases) {
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
        if (specIdentityAliases == null) {
            specIdentityAliases = Map.of();
        }
    }

    /** Extra accepted identity tokens for a routing token; empty when none are configured. */
    public @NonNull List<String> identityAliasesFor(@NonNull String routingToken) {
        return specIdentityAliases.getOrDefault(routingToken, List.of());
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

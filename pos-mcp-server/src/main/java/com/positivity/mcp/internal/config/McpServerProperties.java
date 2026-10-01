package com.positivity.mcp.internal.config;

import io.modelcontextprotocol.server.transport.HttpServletSseServerTransportProvider;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.jspecify.annotations.NonNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.http.HttpMethod;

/**
 * @param specIdentityAliases per-domain extra title tokens accepted by the spec-identity guard
 *     (#1632 follow-up), keyed by routing token — either its natural spelling (routing prefix
 *     without the leading slash, e.g. {@code vehicle-fitment}) or its normalized form
 *     ({@code vehiclefitment}); the guard honors both. Needed where a service's OpenAPI
 *     {@code info.title} does not contain its routing token — e.g. pos-catalog's title says
 *     "Product" and pos-people's says "Human Resources".
 * @param excludedWritePathPatterns #2370: regular expressions, matched with {@link
 *     java.util.regex.Matcher#find()} against the routing-prefixed aggregate path (e.g. {@code
 *     /security-service/v1/audit/events}), whose non-GET operations are never discovered as agent
 *     tools. GET on a matching path stays discoverable. Anchor with {@code ^/<routing-prefix>} so a
 *     fragment such as {@code /events} does not reach business paths like {@code
 *     /accounting/v1/accounting/events}. Each entry must compile; a bad entry fails startup.
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
        @NonNull List<String> excludedWritePathPatterns,
        @NonNull Map<String, List<String>> specIdentityAliases) {
    /** Compiled form of every {@code excludedWritePathPatterns} entry ever seen; bounded by configuration size. */
    private static final ConcurrentMap<String, Pattern> COMPILED_WRITE_PATTERNS = new ConcurrentHashMap<>();

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
        if (excludedWritePathPatterns == null) {
            excludedWritePathPatterns = List.of();
        }
        for (String pattern : excludedWritePathPatterns) {
            try {
                compiled(pattern);
            } catch (PatternSyntaxException exception) {
                throw new IllegalArgumentException(
                        "mcp.server.excluded-write-path-patterns entry is not a valid regular expression: " + pattern,
                        exception);
            }
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

    /**
     * #2370: whether discovery must drop this operation because it writes to an audit or platform-event
     * surface. True only for a non-GET method on a routing-prefixed path that some {@code
     * excludedWritePathPatterns} entry matches; GET is never excluded here, so reading an audit log
     * stays a discoverable tool while emitting, altering or deleting evidence never is.
     */
    public boolean excludesWrite(@NonNull String path, @NonNull HttpMethod method) {
        if (HttpMethod.GET.equals(method) || excludedWritePathPatterns.isEmpty()) {
            return false;
        }
        return excludedWritePathPatterns.stream()
                .anyMatch(pattern -> compiled(pattern).matcher(path).find());
    }

    private static @NonNull Pattern compiled(@NonNull String pattern) {
        return COMPILED_WRITE_PATTERNS.computeIfAbsent(pattern, Pattern::compile);
    }
}

package com.positivity.gateway.filter;

import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * GlobalFilter: API Version Header to Path Rewriter
 *
 * Implements Pattern A: External callers use domain paths + X-API-Version
 * header
 * Gateway automatically rewrites to /{serviceId}/v{version}/** for internal
 * routing
 *
 * Example:
 * Client Request: GET /inventory/items/123
 * Header: X-API-Version: 1
 * Gateway Rewrites: /inventory/v1/items/123
 * Forwarded To: lb://inventory (Eureka service discovery)
 * Service Receives: /v1/items/123 (after gateway strips /inventory)
 *
 * Validation (STRICT):
 * - X-API-Version header REQUIRED
 * - Version MUST be numeric (e.g., 1, 2, 10)
 * - Missing or invalid header returns 400 Bad Request
 * - Path MUST start with /{serviceId}/... (e.g., /inventory/items)
 * - Empty or root path (/) returns 404 Not Found
 */
@Component
public class ApiVersionHeaderToPathFilter implements GlobalFilter, Ordered {

    private static final String VERSION_HEADER = "X-API-Version";
    private static final Logger log = LoggerFactory.getLogger(ApiVersionHeaderToPathFilter.class);

    @Override
    public Mono<Void> filter(ServerWebExchange exchange,
            org.springframework.cloud.gateway.filter.GatewayFilterChain chain) {

        var req = exchange.getRequest();
        var rawPath = req.getURI().getRawPath();

        // Bypass public paths - no version header required
        if (rawPath != null && (rawPath.startsWith("/actuator") ||
                rawPath.contains("/actuator") ||
                rawPath.startsWith("/swagger-ui") ||
                rawPath.startsWith("/v3/api-docs") ||
                rawPath.contains("/v3/api-docs") ||
                rawPath.startsWith("/swagger-resources") ||
                rawPath.startsWith("/eureka"))) {
            return chain.filter(exchange);
        }

        var version = req.getHeaders().getFirst(VERSION_HEADER);

        // STRICT: Require valid X-API-Version header for API calls
        if (version == null || version.isBlank() || !version.matches("\\d+")) {
            log.warn("Missing or invalid X-API-Version header in request to {}", rawPath);
            exchange.getResponse().setStatusCode(HttpStatus.BAD_REQUEST);
            return exchange.getResponse().setComplete();
        }

        if (rawPath == null || rawPath.isBlank() || rawPath.equals("/")) {
            log.warn("Invalid path: {}", rawPath);
            exchange.getResponse().setStatusCode(HttpStatus.NOT_FOUND);
            return exchange.getResponse().setComplete();
        }

        // Parse path: /{serviceId}/...
        // Split on first two slashes to separate service ID from remainder
        var parts = rawPath.split("/", 3); // ["", "inventory", "items/123"]
        if (parts.length < 2 || parts[1].isBlank()) {
            log.warn("Path does not contain service ID: {}", rawPath);
            exchange.getResponse().setStatusCode(HttpStatus.NOT_FOUND);
            return exchange.getResponse().setComplete();
        }

        var serviceId = parts[1].toLowerCase();
        var remainder = (parts.length == 3) ? "/" + parts[2] : "";

        // Rewrite path: /{serviceId}/v{version}/{remainder}
        var newPath = "/" + serviceId + "/v" + version + remainder;
        log.debug("Rewriting {} -> {} (version header: {})", rawPath, newPath, version);

        var mutated = exchange.mutate()
                .request(r -> r.path(newPath))
                .build();

        return chain.filter(mutated);
    }

    @Override
    public int getOrder() {
        // Run EARLY (highest precedence) before other filters
        return Ordered.HIGHEST_PRECEDENCE;
    }
}

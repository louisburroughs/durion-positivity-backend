package com.positivity.gateway.filter;

import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * {@link GlobalFilter} that rewrites inbound API paths by injecting the version
 * segment supplied via the {@code X-API-Version} request header.
 *
 * <h2>Routing Pattern</h2>
 * External callers address resources using a flat, service-prefixed path and
 * declare their desired API version in a header. The filter rewrites the path
 * before any downstream load-balancer or service filter runs:
 *
 * <pre>
 * Client:  GET /inventory/items/123   (X-API-Version: 1)
 * Gateway: GET /inventory/v1/items/123
 * Service: GET /v1/items/123          (after gateway strips /inventory)
 * </pre>
 *
 * <h2>Precedence</h2>
 * Runs at {@link Ordered#HIGHEST_PRECEDENCE} so every subsequent filter
 * (including the JWT validator) sees the already-rewritten path.
 *
 * <h2>Validation Rules (STRICT)</h2>
 * <ul>
 *   <li>{@code X-API-Version} header is <strong>required</strong> for all API
 *       calls not covered by the bypass list.
 *   <li>The version value must be a pure integer string (e.g. {@code 1},
 *       {@code 2}, {@code 10}). Non-numeric values return {@code 400}.
 *   <li>The path must contain a non-empty service-ID segment
 *       (e.g. {@code /inventory/...}). An empty or root path returns
 *       {@code 404}.
 * </ul>
 *
 * <h2>Bypass Conditions</h2>
 * The following path families skip version injection entirely:
 * <ul>
 *   <li>Actuator endpoints ({@code /actuator/**})
 *   <li>Swagger / OpenAPI UI ({@code /swagger-ui/**}, {@code /v3/api-docs/**})
 *   <li>Eureka dashboard ({@code /eureka/**})
 *   <li>Security-service auth bootstrap
 *       ({@code /security-service/v1/auth} and {@code /security-service/v1/auth/**})
 * </ul>
 *
 * <h2>/api Prefix Stripping</h2>
 * Paths that start with {@code /api/} are normalised (prefix stripped) before
 * all other processing. This covers direct gateway access from clients that
 * mirror the front-end Node.js proxy mount point. The stripped {@code /api}
 * prefix is appended to the {@code X-Forwarded-Prefix} header so downstream
 * services can rebuild public absolute URLs (e.g. the bulk-loader TUS
 * {@code Location} header).
 *
 * <h2>Already-Versioned Paths</h2>
 * If the inbound path already matches
 * {@code /{serviceId}/v\d+(/.*)?} the filter is a no-op; the path is forwarded
 * as-is. This allows internal or pre-versioned callers to bypass rewriting.
 */
@Component
public class ApiVersionHeaderToPathFilter implements GlobalFilter, Ordered {

    private static final String VERSION_HEADER = "X-API-Version";
    private static final String PATH_SEP = "/";
    private static final Pattern VERSIONED_SERVICE_PATH = Pattern.compile("^/[^/]+/v\\d+(?:/.*)?$");
    private static final Logger log = LoggerFactory.getLogger(ApiVersionHeaderToPathFilter.class);

    /**
     * Normalises the request path and injects the API version segment.
     *
     * <p>Processing order:
     * <ol>
     *   <li>Strip {@code /api} prefix if present (defence-in-depth; the
     *       Node.js proxy's {@code pathRewrite} handles this for SSR traffic,
     *       but direct gateway clients may still send the prefix).
     *   <li>Return immediately if the path matches a bypass pattern.
     *   <li>Return immediately if the path is already versioned.
     *   <li>Validate the {@code X-API-Version} header — {@code 400} if
     *       missing or non-numeric.
     *   <li>Rewrite the path to {@code /{serviceId}/v{version}/{remainder}}.
     * </ol>
     *
     * @param exchange the current server exchange
     * @param chain    the remaining filter chain
     * @return a {@link Mono} that completes when the response is written
     */
    @Override
    public Mono<Void> filter(
            ServerWebExchange exchange, org.springframework.cloud.gateway.filter.GatewayFilterChain chain) {

        var req = exchange.getRequest();
        var rawPath = req.getURI().getRawPath();

        if (rawPath != null && rawPath.startsWith("/api/")) {
            rawPath = rawPath.substring("/api".length());
            final var normalizedPath = rawPath;
            // Record the stripped prefix in X-Forwarded-Prefix (appended after any value set by
            // an outer proxy) so downstream services can rebuild public absolute URLs — e.g. the
            // bulk-loader TUS Location header. XForwardedHeadersFilter later appends the route
            // prefix removed by StripPrefix to the same header.
            exchange = exchange.mutate()
                    .request(r -> r.path(normalizedPath).headers(h -> h.add("X-Forwarded-Prefix", "/api")))
                    .build();
            log.debug("Stripped /api prefix; effective path={}", rawPath);
        }

        // Bypass public paths and auth bootstrap endpoints.
        if (shouldBypassVersioning(rawPath)) {
            return chain.filter(exchange);
        }

        // If caller already provided a versioned service path, do not rewrite again.
        if (isAlreadyVersionedPath(rawPath)) {
            return chain.filter(exchange);
        }

        var version = req.getHeaders().getFirst(VERSION_HEADER);

        // STRICT: Require valid X-API-Version header for API calls
        if (version == null || version.isBlank() || !version.matches("\\d+")) {
            log.warn(
                    "400 Bad request: missing/invalid X-API-Version; method={} path={} X-API-Version={}",
                    req.getMethod().name(),
                    rawPath,
                    version != null ? "'" + version + "'" : "<absent>");
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
        var parts = rawPath.split(PATH_SEP, 3);
        if (parts.length < 2 || parts[1].isBlank()) {
            log.warn("Path does not contain service ID: {}", rawPath);
            exchange.getResponse().setStatusCode(HttpStatus.NOT_FOUND);
            return exchange.getResponse().setComplete();
        }

        var serviceId = parts[1].toLowerCase();
        var remainder = (parts.length == 3) ? "/" + parts[2] : "";

        var newPath = PATH_SEP + serviceId + "/v" + version + remainder;
        log.debug("Rewriting {} -> {} (version header: {})", rawPath, newPath, version);

        var mutated = exchange.mutate().request(r -> r.path(newPath)).build();

        return chain.filter(mutated);
    }

    @Override
    public int getOrder() {
        // Run EARLY (highest precedence) before other filters
        return Ordered.HIGHEST_PRECEDENCE;
    }

    /**
     * Returns {@code true} if the path belongs to an infrastructure or
     * authentication-bootstrap endpoint that should never have a version
     * segment injected (actuator, Swagger UI, Eureka, security-service auth).
     *
     * @param rawPath the raw, decoded request path; may be {@code null}
     * @return {@code true} when version rewriting must be skipped
     */
    private static boolean shouldBypassVersioning(String rawPath) {
        if (rawPath == null) {
            return false;
        }
        return rawPath.startsWith("/actuator")
                || rawPath.contains("/actuator")
                || rawPath.startsWith("/swagger-ui")
                || rawPath.startsWith("/v3/api-docs")
                || rawPath.contains("/v3/api-docs")
                || rawPath.startsWith("/swagger-resources")
                || rawPath.startsWith("/eureka")
                || rawPath.equals("/security-service/v1/auth")
                || rawPath.startsWith("/security-service/v1/auth/");
    }

    /**
     * Returns {@code true} when the path already contains a version segment
     * matching the pattern {@code /{serviceId}/v\d+(/.*)?}, indicating no
     * rewrite is needed.
     *
     * @param rawPath the raw request path; may be {@code null}
     * @return {@code true} if the path is already in versioned form
     */
    private static boolean isAlreadyVersionedPath(String rawPath) {
        return rawPath != null && VERSIONED_SERVICE_PATH.matcher(rawPath).matches();
    }
}

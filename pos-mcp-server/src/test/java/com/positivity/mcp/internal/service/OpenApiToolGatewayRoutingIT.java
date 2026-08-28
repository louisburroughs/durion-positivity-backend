package com.positivity.mcp.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.positivity.mcp.internal.config.CurrentUserContext;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * #645: end-to-end verification that an OpenAPI-discovered tool invokes <em>through the gateway</em>
 * with no routing 404 — the failure mode #645 exists to rule out (a discovered op whose persisted
 * execution coordinates don't line up with a live gateway route). Drives the real
 * {@link OpenApiToolProvider} → {@code OpenApiOperationExecutor} → {@code OperationProxyFactory}
 * against the live gateway, so it exercises exactly the production invocation path.
 *
 * <p><strong>Read-only / side-effect-free:</strong> only a discovered <em>GET</em> op with no path
 * parameters is selected (a collection/list/count endpoint), so the call mutates nothing and a
 * healthy route returns 2xx rather than a legitimate resource-level 404 — making a {@code 404 Not
 * Found} an unambiguous routing miss.
 *
 * <p>A gateway that matched the route but rejected auth returns 401/403; a matched route with missing
 * query params returns 400; a healthy call returns 2xx. All of those prove the route resolved — only
 * an unmatched route 404s. So a bearer token is <em>optional</em>: supply {@code -Dmcp.eval.bearer=&lt;jwt&gt;}
 * (or {@code MCP_EVAL_BEARER}) to additionally exercise the downstream service (expect 2xx); without
 * it the 401/403 still proves routing.
 *
 * <p>Requires the live alpha stack (Postgres + pgvector with discovered tools + embeddings, and a
 * reachable gateway at the discovered {@code service_id} base URI). Disabled by default; starts a
 * context only with {@code -Dmcp.eval.live=true}. Boots lean + read-only (Flyway/web/Eureka/permission
 * registration off), same as {@link OpenApiToolPermissionGatingIT}.
 *
 * <pre>
 *   POS_MCP_DB_HOST=... POS_MCP_DB_PASSWORD=... OLLAMA_EMBEDDING_BASE_URL=... \
 *   ./mvnw -pl pos-mcp-server test -Dtest=OpenApiToolGatewayRoutingIT \
 *       -Dmcp.eval.live=true -Dspring.profiles.active=alpha [-Dmcp.eval.bearer=&lt;jwt&gt;]
 * </pre>
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "spring.flyway.enabled=false",
            "pos.security.permission-registration.enabled=false",
            "eureka.client.enabled=false"
        })
@ActiveProfiles("alpha")
@EnabledIfSystemProperty(named = "mcp.eval.live", matches = "true")
class OpenApiToolGatewayRoutingIT {

    private static final UUID USER_ID = UUID.fromString("00000000-0000-7000-8000-000000000645");

    @Autowired
    private OpenApiToolProvider openApiToolProvider;

    @Autowired
    private RequestScopedUserContext requestScopedUserContext;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void clearContext() {
        requestScopedUserContext.clear();
    }

    @Test
    @DisplayName("a discovered GET op invokes through the gateway with no routing 404 (#645)")
    void discoveredGetOpRoutesThroughGatewayWithoutNotFound() {
        // Pick a real discovered, embedded GET op with NO path parameters (a collection/list/count
        // endpoint), so the call is side-effect-free and a healthy route returns 2xx — making any
        // 404 an unambiguous routing miss rather than a legitimate "resource not found". Requires a
        // grantable permission so the provider will surface it. Skip cleanly if none is seeded.
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT t.id AS id, t.name AS name, t.description AS description,
                       t.http_path AS http_path, t.service_id AS service_id
                FROM mcp_tool t
                WHERE t.source = 'openapi' AND t.enabled AND t.embedding IS NOT NULL
                  AND t.http_method = 'GET'
                  AND t.http_path NOT LIKE '%{%'
                  AND t.service_id IS NOT NULL
                  AND EXISTS (SELECT 1 FROM mcp_tool_permission tp WHERE tp.tool_id = t.id)
                ORDER BY t.name
                LIMIT 1
                """);
        assumeTrue(!rows.isEmpty(), "no parameter-free GET OpenAPI tool discovered on the live stack");

        Map<String, Object> op = rows.getFirst();
        UUID toolId = (UUID) op.get("id");
        String toolName = (String) op.get("name");
        String description = (String) op.get("description");
        String httpPath = (String) op.get("http_path");

        // Grant exactly the op's required permissions (∪ AUTHENTICATED) so the provider offers it, and
        // relay a bearer token if one was supplied (lets the gateway execute the op as the caller).
        Set<String> perms = new HashSet<>(jdbcTemplate.queryForList(
                "SELECT permission_code FROM mcp_tool_permission WHERE tool_id = ?", String.class, toolId));
        perms.add("AUTHENTICATED");
        requestScopedUserContext.set(callerWith(perms), bearerHeader());

        // Query with the op's own description so semantic retrieval ranks it among the candidates.
        ToolCallback callback = openApiToolProvider.resolveToolCallbacks(description).stream()
                .filter(cb -> cb.getToolDefinition().name().equals(toolName))
                .findFirst()
                .orElse(null);
        assumeTrue(
                callback != null,
                "discovered GET op '" + toolName + "' not surfaced for its own description; cannot drive routing");

        // Invoke through the real executor → OperationProxyFactory → gateway. Empty args: a param-free
        // GET needs none, and any missing query params yield a downstream 400 (still a routed response).
        String result = callback.call("{}");
        String lower = result.toLowerCase(Locale.ROOT);

        // Distinguish an infra problem (gateway unreachable from this host) from a #645 routing miss —
        // the former is not what this test asserts, so skip rather than fail.
        assumeTrue(
                !(lower.contains("connection")
                        || lower.contains("timed out")
                        || lower.contains("timeout")
                        || lower.contains("unknownhost")
                        || lower.contains("unable to resolve")
                        || lower.contains("i/o error")
                        || lower.contains("no instance available")),
                "gateway not reachable from this host (infra, not a #645 routing failure): " + result);

        // The #645 assertion: the discovered path resolved through the gateway (no 404).
        // A 2xx body, or a routed 400/401/403/5xx, all pass; only an unmatched route surfaces a 404.
        // Match the status token, not the reason phrase — the WebClient error message renders the
        // reason case-variantly ("404 Not Found" vs "404 NOT_FOUND"), so key off the lowercased "404".
        assertThat(lower)
                .as("#645: discovered op %s [GET %s] must route through the gateway without a 404", toolName, httpPath)
                .doesNotContain("404");
    }

    private static String bearerHeader() {
        String raw = System.getProperty("mcp.eval.bearer", System.getenv("MCP_EVAL_BEARER"));
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String trimmed = raw.trim();
        return trimmed.regionMatches(true, 0, "Bearer ", 0, "Bearer ".length()) ? trimmed : "Bearer " + trimmed;
    }

    private static CurrentUserContext callerWith(Set<String> permissionCodes) {
        Set<String> roles = Set.of("ROLE_ADMIN");
        // authorities = roles ∪ permission codes, matching the production CurrentUserContext shape.
        Set<String> authorities = new HashSet<>(permissionCodes);
        authorities.addAll(roles);
        return new CurrentUserContext("it-user", USER_ID, "ROLE_ADMIN", roles, authorities, permissionCodes);
    }
}

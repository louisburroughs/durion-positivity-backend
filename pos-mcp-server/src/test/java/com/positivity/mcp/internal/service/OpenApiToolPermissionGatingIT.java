package com.positivity.mcp.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.positivity.mcp.service.CurrentUserContext;
import java.util.List;
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
 * Gate 3 / #779: end-to-end permission gating of OpenAPI-discovered tools. Drives the real
 * {@link OpenApiToolProvider} against the live pgvector-backed candidate query, moving the
 * permission-filter assertion from the unit level to integration level.
 *
 * <p>Requires the live alpha stack (Postgres + pgvector with discovered/seeded tools + embeddings).
 * Enabled only with {@code -Dmcp.eval.live=true}; it neither starts a context nor runs in offline CI:
 *
 * <pre>
 *   ./mvnw -o -pl pos-mcp-server test -Dtest=OpenApiToolPermissionGatingIT \
 *       -Dmcp.eval.live=true -Dspring.profiles.active=alpha
 * </pre>
 */
@SpringBootTest
@ActiveProfiles("alpha")
@EnabledIfSystemProperty(named = "mcp.eval.live", matches = "true")
class OpenApiToolPermissionGatingIT {

    private static final UUID USER_ID = UUID.fromString("00000000-0000-7000-8000-000000000779");

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
    @DisplayName("no request context → fail-closed: zero discovered tools")
    void failsClosedWithoutRequestContext() {
        // Holder is empty (no set()); the provider must expose nothing.
        assertThat(openApiToolProvider.resolveToolCallbacks("how many workorders are open"))
                .isEmpty();
    }

    @Test
    @DisplayName("a caller lacking a tool's permission never receives it; granting it does")
    void gatesDiscoveredToolByCallerPermission() {
        // Pick a real discovered, embedded tool that requires a concrete (non-AUTHENTICATED)
        // permission. Skip cleanly if the live registry has none seeded yet.
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT t.name AS name, t.description AS description, tp.permission_code AS perm
                FROM mcp_tool t
                JOIN mcp_tool_permission tp ON tp.tool_id = t.id
                WHERE t.source = 'openapi' AND t.embedding IS NOT NULL AND tp.permission_code <> 'AUTHENTICATED'
                LIMIT 1
                """);
        assumeTrue(!rows.isEmpty(), "no permission-gated OpenAPI tool discovered on the live stack");

        String toolName = (String) rows.getFirst().get("name");
        String description = (String) rows.getFirst().get("description");
        String requiredPermission = (String) rows.getFirst().get("perm");

        // Negative: a caller holding only AUTHENTICATED (not the required permission) must NOT be
        // offered the tool — even when the query is the tool's own description (the most favourable
        // case for semantic retrieval).
        requestScopedUserContext.set(callerWith(Set.of("AUTHENTICATED")));
        assertThat(toolNames(openApiToolProvider.resolveToolCallbacks(description)))
                .doesNotContain(toolName);
        requestScopedUserContext.clear();

        // Positive: granting the required permission makes the tool eligible; querying with its own
        // description places it among the resolved callbacks.
        requestScopedUserContext.set(callerWith(Set.of("AUTHENTICATED", requiredPermission)));
        assertThat(toolNames(openApiToolProvider.resolveToolCallbacks(description)))
                .contains(toolName);
    }

    private static List<String> toolNames(List<ToolCallback> callbacks) {
        return callbacks.stream()
                .map(callback -> callback.getToolDefinition().name())
                .toList();
    }

    private static CurrentUserContext callerWith(Set<String> permissionCodes) {
        return new CurrentUserContext(
                "it-user", USER_ID, "ROLE_ADMIN", Set.of("ROLE_ADMIN"), permissionCodes, permissionCodes);
    }
}

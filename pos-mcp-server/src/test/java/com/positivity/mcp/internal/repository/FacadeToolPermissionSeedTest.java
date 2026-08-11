package com.positivity.mcp.internal.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * #1115 regression guard: no facade tool may be granted the {@code AUTHENTICATED} pseudo-permission
 * alongside a privileged code. Every authenticated caller holds {@code AUTHENTICATED}, so such a
 * facade would pass the selection-layer permission gate for everyone — offering its privileged
 * operations to the model for any logged-in user.
 *
 * <p>The facade grants are Postgres-seed data ({@code V18__seed_facade_tool_permissions.sql}, fixed
 * by {@code V29__fix_facade_authenticated_gating.sql}); there is no offline Flyway path for them, so
 * this test asserts the invariant on the net effect of the migration SQL directly. It stays green
 * only while the seed keeps mixed-sensitivity facades off the {@code AUTHENTICATED} floor.
 */
class FacadeToolPermissionSeedTest {

    private static final Path MIGRATIONS = Paths.get(System.getProperty("user.dir"), "src/main/resources/db/migration");
    private static final String AUTHENTICATED = "AUTHENTICATED";

    // One INSERT block: VALUES ('code'), ('code2') ... WHERE mcp_tool.name = 'ToolName';
    private static final Pattern TOOL_NAME = Pattern.compile("mcp_tool\\.name\\s*=\\s*'([^']+)'");
    private static final Pattern QUOTED_CODE = Pattern.compile("\\('([^']+)'\\)");

    @Test
    @DisplayName("no facade grants AUTHENTICATED alongside a privileged permission (net of V18 + V29)")
    void noFacadeMixesAuthenticatedWithPrivilege() throws IOException {
        Map<String, Set<String>> grants = parseSeed(read("V18__seed_facade_tool_permissions.sql"));
        applyAuthenticatedDeletes(grants, read("V29__fix_facade_authenticated_gating.sql"));

        assertThat(grants).isNotEmpty();
        grants.forEach((tool, codes) -> {
            if (codes.contains(AUTHENTICATED)) {
                assertThat(codes)
                        .as(
                                "%s is granted AUTHENTICATED alongside privileged codes %s; gate it on the "
                                        + "privileged code(s) instead (see V29 / issue #1115)",
                                tool, codes)
                        .containsExactly(AUTHENTICATED);
            }
        });
    }

    @Test
    @DisplayName("the mixed facades no longer carry AUTHENTICATED after V29")
    void mixedFacadesLoseAuthenticated() throws IOException {
        Map<String, Set<String>> grants = parseSeed(read("V18__seed_facade_tool_permissions.sql"));
        applyAuthenticatedDeletes(grants, read("V29__fix_facade_authenticated_gating.sql"));

        assertThat(grants.get("WorkorderFacadeTool"))
                .doesNotContain(AUTHENTICATED)
                .contains("workorder:workorder:view");
        assertThat(grants.get("AdminFacadeTool"))
                .doesNotContain(AUTHENTICATED)
                .contains("security:user:view", "security:permission:view", "security:audit:view");
        // #1115 under-enumerated: TaxFacadeTool is a third mixed facade (tax:calculate + AUTHENTICATED).
        assertThat(grants.get("TaxFacadeTool")).doesNotContain(AUTHENTICATED).contains("tax:calculate");
    }

    // ── parsing ───────────────────────────────────────────────────────────────

    private static Map<String, Set<String>> parseSeed(String sql) {
        Map<String, Set<String>> grants = new LinkedHashMap<>();
        for (String block : sql.split("(?i)INSERT\\s+INTO\\s+mcp_tool_permission")) {
            Matcher nameMatcher = TOOL_NAME.matcher(block);
            if (!nameMatcher.find()) {
                continue;
            }
            String tool = nameMatcher.group(1);
            Set<String> codes = grants.computeIfAbsent(tool, t -> new LinkedHashSet<>());
            Matcher codeMatcher = QUOTED_CODE.matcher(block);
            while (codeMatcher.find()) {
                codes.add(codeMatcher.group(1));
            }
        }
        return grants;
    }

    /**
     * Apply the AUTHENTICATED-removal DELETE in V29: strip AUTHENTICATED from every tool named in the
     * migration's {@code name IN (...)} list. Parsed generically so the guard tracks the migration
     * rather than a hardcoded tool set.
     */
    private static void applyAuthenticatedDeletes(Map<String, Set<String>> grants, String v29) {
        if (!v29.toUpperCase(java.util.Locale.ROOT).contains("'" + AUTHENTICATED + "'")) {
            return;
        }
        Matcher inList = Pattern.compile("name\\s+IN\\s*\\(([^)]*)\\)", Pattern.CASE_INSENSITIVE)
                .matcher(v29);
        if (!inList.find()) {
            return;
        }
        Matcher names = Pattern.compile("'([^']+)'").matcher(inList.group(1));
        while (names.find()) {
            Set<String> codes = grants.get(names.group(1));
            if (codes != null) {
                codes.remove(AUTHENTICATED);
            }
        }
    }

    private static String read(String migration) throws IOException {
        // Strip -- line comments so the executable statements are parsed, not the SQL comments that
        // quote @PreAuthorize expressions like hasAuthority('...') / hasRole('ADMIN').
        return Files.readString(MIGRATIONS.resolve(migration)).replaceAll("(?m)--.*$", "");
    }
}

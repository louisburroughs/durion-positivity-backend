package com.positivity.mcp.internal.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * RAG {@code required-permissions} seed guard (#1525, the RAG counterpart of
 * {@code FacadeToolPermissionSeedTest}).
 *
 * <p>Static RAG documents are gated fail-closed by {@code PermissionAwareMetadataFilter} on the
 * codes each {@code mcp.rag.preload.docs} entry declares (base {@code application.yml} plus the
 * alpha overlay). Those codes live outside {@code mcp_tool_permission}, so nothing else checks them
 * against the platform permission model — a code retired by a grant sweep like #1499 (V28 in
 * pos-security-service) lingers silently and, because the filter is fail-closed, a doc whose whole
 * list is unheld becomes invisible to every caller (the pre-#1525 state of the purchase-order
 * docs).
 *
 * <p>Source of truth is pos-security-service's {@code R__seed_role_permissions.sql}: its
 * {@code INSERT INTO permissions} five-tuples are the registered catalog, and its
 * {@code INSERT INTO role_permissions} pairs are the live grants (V28-style retirements edit that
 * file in the same change, so it reflects the net grant state). Registration alone is not enough —
 * V28 kept the definition rows and deleted only grants — hence the granted-to-at-least-one-role
 * assertion, which is the check that would have caught the dead docs.
 */
class RagRequiredPermissionSeedTest {

    private static final Path MODULE_DIR = Paths.get(System.getProperty("user.dir"));
    private static final Path BASE_YML = MODULE_DIR.resolve("src/main/resources/application.yml");
    private static final Path ALPHA_YML = MODULE_DIR.resolve("src/main/resources/application-alpha.yml");
    // Cross-module by design (#1525): the grant model is pos-security-service seed data, and this
    // reactor checkout layout is what Surefire's user.dir resolves against.
    private static final Path SECURITY_SEED =
            MODULE_DIR.resolve("../pos-security-service/src/main/resources/db/migration/R__seed_role_permissions.sql");

    private static final String AUTHENTICATED = "AUTHENTICATED";

    // ('code', 'domain', 'resource', 'action', bitIndex) rows of INSERT INTO permissions
    private static final Pattern PERMISSION_DEFINITION =
            Pattern.compile("\\('([^']+)',\\s*'[^']*',\\s*'[^']*',\\s*'[^']*',\\s*\\d+\\)");
    // ('ROLE_NAME', 'permission:code') rows of INSERT INTO role_permissions
    private static final Pattern ROLE_GRANT = Pattern.compile("\\('[A-Z][A-Z0-9_]*',\\s*'([^']+)'\\)");

    @Test
    @DisplayName("every RAG required-permissions code is a registered permission")
    void everyRagCodeIsRegistered() throws IOException {
        Set<String> registered = registeredCodes();

        assertThat(registered).isNotEmpty();
        ragDocs()
                .forEach((file, docs) -> docs.forEach((doc, codes) -> assertThat(restricted(codes))
                        .as(
                                "%s doc '%s': every required-permissions code must exist in the permission "
                                        + "catalog (R__seed_role_permissions.sql INSERT INTO permissions)",
                                file, doc)
                        .isSubsetOf(registered)));
    }

    @Test
    @DisplayName("every RAG required-permissions code is granted to at least one role")
    void everyRagCodeIsGrantedToAtLeastOneRole() throws IOException {
        Set<String> granted = grantedCodes();

        assertThat(granted).isNotEmpty();
        ragDocs()
                .forEach((file, docs) -> docs.forEach((doc, codes) -> assertThat(restricted(codes))
                        .as(
                                "%s doc '%s': a required-permissions code no role holds makes the doc "
                                        + "invisible via that code (fail-closed filter); retarget it to the live "
                                        + "successor (see #1499/V28 and the #1525 walk)",
                                file, doc)
                        .isSubsetOf(granted)));
    }

    @Test
    @DisplayName("base and alpha preload docs stay in parity (ids and required-permissions)")
    void baseAndAlphaStayInParity() throws IOException {
        Map<String, Set<String>> base = loadDocs(BASE_YML);
        Map<String, Set<String>> alpha = loadDocs(ALPHA_YML);

        assertThat(alpha.keySet())
                .as("alpha overlay redefines the whole doc list, so ids must match the base corpus")
                .containsExactlyInAnyOrderElementsOf(base.keySet());
        base.forEach((doc, codes) -> assertThat(alpha.get(doc))
                .as(
                        "doc '%s': required-permissions must match between application.yml and the "
                                + "alpha overlay — every gating change lands in both files",
                        doc)
                .containsExactlyInAnyOrderElementsOf(codes));
    }

    @Test
    @DisplayName("no doc lists AUTHENTICATED alongside a privileged permission")
    void noDocMixesAuthenticatedWithPrivilege() throws IOException {
        ragDocs()
                .forEach((file, docs) -> docs.forEach((doc, codes) -> {
                    if (codes.contains(AUTHENTICATED)) {
                        assertThat(codes)
                                .as(
                                        "%s doc '%s': required-permissions is OR-semantics and every "
                                                + "authenticated caller holds AUTHENTICATED, so listing it beside "
                                                + "privileged codes opens the doc to everyone (#1115 failure mode)",
                                        file, doc)
                                .containsExactly(AUTHENTICATED);
                    }
                }));
    }

    // ── parsing ───────────────────────────────────────────────────────────────

    /** File label → (doc id → required-permissions), for both the base config and the alpha overlay. */
    private static Map<String, Map<String, Set<String>>> ragDocs() throws IOException {
        Map<String, Map<String, Set<String>>> all = new LinkedHashMap<>();
        all.put(BASE_YML.getFileName().toString(), loadDocs(BASE_YML));
        all.put(ALPHA_YML.getFileName().toString(), loadDocs(ALPHA_YML));
        return all;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Set<String>> loadDocs(Path yml) throws IOException {
        Map<String, Object> root = new Yaml().load(Files.readString(yml));
        Map<String, Object> mcp = (Map<String, Object>) root.get("mcp");
        Map<String, Object> rag = (Map<String, Object>) mcp.get("rag");
        Map<String, Object> preload = (Map<String, Object>) rag.get("preload");
        List<Map<String, Object>> docs = (List<Map<String, Object>>) preload.get("docs");

        Map<String, Set<String>> byId = new LinkedHashMap<>();
        for (Map<String, Object> doc : docs) {
            List<String> codes = (List<String>) doc.get("required-permissions");
            byId.put((String) doc.get("id"), codes == null ? Set.of() : Set.copyOf(codes));
        }
        return byId;
    }

    private static Set<String> restricted(Set<String> codes) {
        return codes.stream().filter(code -> !AUTHENTICATED.equals(code)).collect(Collectors.toSet());
    }

    private static Set<String> registeredCodes() throws IOException {
        return extract(PERMISSION_DEFINITION, securitySeed().split("(?i)INSERT\\s+INTO\\s+role_permissions", 2)[0]);
    }

    private static Set<String> grantedCodes() throws IOException {
        return extract(ROLE_GRANT, securitySeed().split("(?i)INSERT\\s+INTO\\s+role_permissions", 2)[1]);
    }

    private static Set<String> extract(Pattern pattern, String sql) {
        Matcher matcher = pattern.matcher(sql);
        Set<String> codes = new LinkedHashSet<>();
        while (matcher.find()) {
            codes.add(matcher.group(1));
        }
        return codes;
    }

    private static String securitySeed() throws IOException {
        // Strip -- line comments so retired codes quoted in prose (e.g. the V28 narrative) are not
        // parsed as live definitions or grants.
        return Files.readString(SECURITY_SEED).replaceAll("(?m)--.*$", "");
    }
}

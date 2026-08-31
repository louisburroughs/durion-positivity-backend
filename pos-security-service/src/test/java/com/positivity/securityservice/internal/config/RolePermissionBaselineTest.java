package com.positivity.securityservice.internal.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.securityservice.internal.enums.PermissionCode;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Guards the {@code role_permissions} baseline seeded by
 * {@code R__seed_role_permissions.sql}.
 *
 * <p>Authorities are resolved from the database now that the compiled role-to-authority
 * switch in {@code RoleAuthorityServiceImpl} is gone, which makes this SQL file the thing
 * that decides what every canonical role can do. Two properties matter and neither is
 * visible from the Java side any more:
 *
 * <ol>
 *   <li><b>No capability was lost in the migration.</b> {@code role-authority-legacy-baseline.tsv}
 *       is the exact expansion the deleted switch produced, captured before deletion. The seed
 *       must reproduce it row for row.</li>
 *   <li><b>No capability was silently widened.</b> In particular SYSTEM_ADMINISTRATOR is an
 *       admin/security role, not a superuser.</li>
 * </ol>
 *
 * <p>This is a pure parse of the migration, so it runs in {@code test} without a database.
 * {@code RolePermissionSeedIT} covers what only a real Postgres can prove (the SQL applies,
 * re-applies without duplicating, and rejects unknown names).
 *
 * <p><b>2026-08 ACCOUNT_MANAGER / CONTROLLER rescope.</b> Per #1499/#1512 (docs/rbac-permission-
 * role-audit-2026-08.md §6, decided 2026-08-25), ACCOUNT_MANAGER was narrowed to a
 * customer-accounts (AR) role and its accounting-management grants — chart of accounts, journal
 * entries, GL/posting-category/mapping-key/default-mapping configuration, posting rules,
 * accounting events, exports and AP — moved to the newly created CONTROLLER role ({@code
 * V24__seed_controller_role.sql}, revoked from ACCOUNT_MANAGER by {@code
 * V25__rescope_account_manager_to_customer_accounts.sql}). Six dead codes (accounting:ap:approve,
 * accounting:ap:reject and the accounting:mapping:* family) were retired at the same time. {@code
 * role-authority-legacy-baseline.tsv} was edited deliberately to match: the moved codes'
 * ACCOUNT_MANAGER rows were relabeled to CONTROLLER (the no-regression floor moves with the role
 * that now owns the capability) and the six retired codes' rows were deleted outright. A future
 * reader diffing this fixture against git history should read this as intentional, not drift.
 * The same rescope also re-points two other superseded families (§2 finding 1 and §3 of the same
 * audit): {@code workorder:start} rows were relabeled to {@code workorder:workorder:start}
 * (deduped against ADMIN, which already held the surviving code), and {@code shop:location:view}
 * / {@code shop:bay:view} rows were relabeled to {@code location:read} / {@code
 * location:bay:read} (with LOCATION_MANAGER's {@code shop:location/bay:create/edit} rows
 * relabeled to {@code location:write} / {@code location:bay:manage}). The 2026-08 task 5
 * retirement wave ({@code V28__retire_unenforced_permission_grants.sql}, docs/rbac-permission-
 * role-audit-2026-08.md §7) deleted the fixture's 38 rows for the 34 codes it retires outright
 * (enforced by no endpoint or capability check — ADMIN, plus LOCATION_MANAGER and
 * SERVICE_ADVISOR for two of them) rather than relabeling them to a successor: these are
 * deliberate retirements with no role that should keep the capability, not a rename. The
 * follow-on 2026-08 task 5 enforcement wave (§7 task 5, no versioned migration — seed-only and
 * purely additive) then paired 15 of the codes gated by pos-catalog/pos-price/pos-vehicle-inventory/
 * pos-vehicle-fitment's new {@code @PreAuthorize} checks with the role grants their personas need,
 * adding rows only and touching no legacy-baseline fixture row.
 */
@DisplayName("role_permissions baseline seed")
class RolePermissionBaselineTest {

    private static final String SEED = "db/migration/R__seed_role_permissions.sql";
    private static final String LEGACY_BASELINE = "role-authority-legacy-baseline.tsv";

    /** {@code ('name', 'domain', 'resource', 'action', 42),} — a permission definition row. */
    private static final Pattern PERMISSION_ROW =
            Pattern.compile("^\\s*\\('([^']+)', '([^']*)', '([^']*)', '([^']+)', (\\d+)\\),?$");

    /**
     * {@code ('ROLE_NAME', 'domain:resource:action'),} — a role grant row.
     *
     * <p>The permission half must be colon-bearing and space-free so this cannot also match the
     * {@code ('ROLE_NAME', 'description')} rows in the seed's role-creation block, whose second
     * value is prose.
     */
    private static final Pattern GRANT_ROW =
            Pattern.compile("^\\s*\\('([A-Z][A-Z_]+)', '([a-zA-Z][a-zA-Z0-9_.-]*(?::[a-zA-Z0-9_.-]+)+)'\\),?$");

    /**
     * {@code ('NAME'),} — a bare single-column row, as used by the section 4
     * "fail loudly if any baseline name did not resolve" assertion lists.
     */
    private static final Pattern ASSERTION_ROW = Pattern.compile("^\\s*\\('([^']+)'\\),?$");

    /** Directory holding the Flyway migrations, relative to the module root. */
    private static final Path MIGRATIONS = Path.of("src/main/resources/db/migration");

    /**
     * The bulk-load grants baseline (#1613 D8). Grants for every role that moved out of Flyway live
     * here now, so the policy invariants below have to read both files: the SQL seed alone is no
     * longer the whole role-to-permission baseline, and checking only it would quietly stop
     * policing most roles while still passing.
     */
    private static final Path BASELINE_GRANTS =
            Path.of("..", "scripts", "fixtures", "seed", "alpha", "security", "role-permissions.csv");

    /**
     * The #1512 revoke of SYSTEM_ADMINISTRATOR's out-of-band grants. Its keep list is a copy of
     * this seed's SYSTEM_ADMINISTRATOR block, which is what
     * {@link #v31KeepListMatchesTheSeededSystemAdministratorGrants} exists to police.
     */
    private static final Path V31_REVOKE =
            MIGRATIONS.resolve("V31__revoke_system_administrator_out_of_band_grants.sql");

    /**
     * A lower-case, colon-bearing quoted literal — a permission name. Deliberately not matched
     * against role names (upper-case, no colon) or the migration's {@code RAISE} messages (which
     * start upper-case and contain spaces), so the keep list is the only thing it picks up once
     * comments are stripped.
     */
    private static final Pattern QUOTED_PERMISSION = Pattern.compile("'([a-z][a-z0-9_.-]*(?::[a-z0-9_.-]+)+)'");

    /** A {@code --} comment, to end of line. */
    private static final Pattern SQL_LINE_COMMENT = Pattern.compile("--.*");

    /**
     * Roles the retired hardcoded switch expanded that no migration and no runtime initializer
     * ever creates. Both {@code user_roles} and {@code role_assignments} are foreign-keyed to
     * {@code roles(id)}, so no user could hold one — they were unreachable branches, and their
     * grants are deliberately not carried into the baseline.
     *
     * <p>CONTROLLER is deliberately absent from this set as of the 2026-08 ACCOUNT_MANAGER /
     * CONTROLLER rescope (#1499/#1512, docs/rbac-permission-role-audit-2026-08.md §6): {@code
     * V24__seed_controller_role.sql} now creates it, so it is reachable and this baseline grants
     * it the accounting-management authority {@code V25} revokes from ACCOUNT_MANAGER.
     */
    private static final Set<String> UNREACHABLE_LEGACY_ROLES =
            Set.of("ACCOUNTANT", "AP_CLERK", "CSR", "FLEET_MANAGER", "GL_ANALYST");

    /**
     * The two unratified "Candidate Roles v0" that {@code V3__seed_candidate_roles.sql}
     * created and {@code V23__drop_unratified_candidate_roles.sql} deletes (#1373).
     * Nothing in the codebase ever referenced either, and SECURITY_ADMIN's described
     * scope is already held by SYSTEM_ADMINISTRATOR. Granting to a deleted role would
     * resolve nothing and trip the seed's own assertion at startup, so this must stay
     * empty of grants — and V3 still creates them, which means
     * {@link #everyGrantedRoleIsCreatableAtMigrationTime} cannot catch the mistake.
     */
    private static final Set<String> DELETED_CANDIDATE_ROLES = Set.of("SECURITY_ADMIN", "READ_ONLY_SCHEDULER");

    /**
     * Roles that may create an inventory adjustment (#1373), matching the model the
     * seed's policy header documents.
     */
    private static final Set<String> ADJUSTMENT_CREATORS =
            Set.of("ADMIN", "INVENTORY_CONTROLLER", "INVENTORY_LEAD", "INVENTORY_MANAGER");

    /**
     * Roles that may approve an inventory adjustment (#1373). INVENTORY_LEAD is
     * deliberately absent: it raises requests, it does not approve them.
     */
    private static final Set<String> ADJUSTMENT_APPROVERS =
            Set.of("ADMIN", "INVENTORY_CONTROLLER", "INVENTORY_MANAGER");

    /**
     * Conversational entrypoints every role receives. Holding these grants reach to the
     * assistant, not to data: the assistant still enforces domain permissions per request.
     */
    private static final Set<String> ASSISTANT_BASELINE =
            Set.of("mcp:chat:execute", "mcp:chat:stream", "nlti:request:submit", "nlti:request:read");

    /** MCP administration that both ADMIN and SYSTEM_ADMINISTRATOR must hold. */
    private static final Set<String> MCP_ADMINISTRATION = Set.of(
            "mcp:system_prompt:view",
            "mcp:system_prompt:create",
            "mcp:system_prompt:update",
            "mcp:system_prompt:delete",
            "mcp:llm_api:view",
            "mcp:llm_api:create",
            "mcp:llm_api:update",
            "mcp:llm_api:delete",
            "mcp:tool:manage",
            "mcp:document:ingest");

    /**
     * Everything on the MCP administration surface, which no role outside ADMIN and
     * SYSTEM_ADMINISTRATOR may hold. Wider than {@link #MCP_ADMINISTRATION} because
     * {@code mcp:tool:view} is restricted too but is currently held by ADMIN alone, so it is
     * not something both admin roles are required to have.
     */
    private static final Set<String> MCP_ADMINISTRATION_SURFACE = Stream.concat(
                    MCP_ADMINISTRATION.stream(), Stream.of("mcp:tool:view"))
            .collect(Collectors.toUnmodifiableSet());

    /**
     * Scoped, non-domain exceptions SYSTEM_ADMINISTRATOR may hold outside the security:/mcp:/
     * nlti: prefixes it is otherwise confined to. Each is a deliberate carve-out, not a domain
     * grant: image:image:store is the 2026-08 §2 decision 3 image-upload grant shared by both
     * admin roles; the other five are the 2026-08 §2 recommended-grants matrix (accepted and
     * implemented 2026-08-25, docs/rbac-permission-role-audit-2026-08.md §2) narrow operational
     * escape hatches that belong to system administration rather than any single business
     * domain -- event replay, compliance visibility and supplier transmission triage.
     */
    private static final Set<String> SYSTEM_ADMIN_SCOPED_EXCEPTIONS = Set.of(
            "image:image:store",
            "workorder:events:replay",
            "people:compliance:view",
            "supplier:audit:read",
            "supplier:transmission:read",
            "supplier:transmission:resolve");

    private static Map<String, Set<String>> seededGrants;
    private static Set<String> definedPermissions;
    private static List<String> rawGrantRows;

    /**
     * Grants written by the SQL seed alone, before the bulk-load baseline is folded in (#1613 D8).
     * The two checks about this migration's own internal consistency — that it grants only to roles
     * a migration creates, and that its section-4 guard lists exactly what it grants — are about
     * this file, not about the platform's complete role set.
     */
    private static Map<String, Set<String>> sqlSeededGrants;

    @BeforeAll
    static void parseSeed() throws IOException {
        seededGrants = new TreeMap<>();
        definedPermissions = new LinkedHashSet<>();
        rawGrantRows = new ArrayList<>();

        for (String line : readResource(SEED).lines().toList()) {
            Matcher permission = PERMISSION_ROW.matcher(line);
            if (permission.matches()) {
                definedPermissions.add(permission.group(1));
                continue;
            }
            Matcher grant = GRANT_ROW.matcher(line);
            if (grant.matches()) {
                rawGrantRows.add(grant.group(1) + "\t" + grant.group(2));
                seededGrants
                        .computeIfAbsent(grant.group(1), role -> new TreeSet<>())
                        .add(grant.group(2));
            }
        }

        sqlSeededGrants = new TreeMap<>();
        seededGrants.forEach((role, permissions) -> sqlSeededGrants.put(role, new TreeSet<>(permissions)));

        mergeBaselineGrants();

        assertThat(seededGrants).as("no grant rows parsed out of %s", SEED).isNotEmpty();
    }

    /**
     * Folds the bulk-load baseline into {@link #seededGrants}, so every assertion here sees the
     * complete role-to-permission picture however it is split across Flyway and the load file.
     *
     * <p>Deliberately not folded into {@link #rawGrantRows}: that list backs the duplicate-row
     * check, which is about one file listing the same grant twice. The two sources overlap by
     * design for the roles Flyway still creates, and counting that as duplication would fail a test
     * for the thing the split is supposed to do.
     */
    private static void mergeBaselineGrants() throws IOException {
        List<String> lines = Files.readAllLines(BASELINE_GRANTS, StandardCharsets.UTF_8);
        for (String line : lines.subList(1, lines.size())) {
            if (line.isBlank()) {
                continue;
            }
            int comma = line.indexOf(',');
            String role = line.substring(0, comma).trim();
            String permissions = line.substring(comma + 1).trim();
            if (permissions.startsWith("\"") && permissions.endsWith("\"")) {
                permissions = permissions.substring(1, permissions.length() - 1);
            }
            for (String permission : permissions.split(";")) {
                if (!permission.isBlank()) {
                    seededGrants.computeIfAbsent(role, key -> new TreeSet<>()).add(permission.trim());
                }
            }
        }
    }

    @Test
    @DisplayName("still grants everything the retired hardcoded expansion gave every legacy role")
    void seed_losesNoLegacyCapability() throws IOException {
        Map<String, Set<String>> legacy = new TreeMap<>();
        for (String line : readResource(LEGACY_BASELINE).lines().toList()) {
            if (line.isBlank()) {
                continue;
            }
            String[] parts = line.split("\t", 2);
            legacy.computeIfAbsent(parts[0], role -> new TreeSet<>()).add(parts[1]);
        }

        assertThat(legacy).as("legacy baseline fixture is empty").isNotEmpty();

        // Containment, not equality: the baseline deliberately exceeds the legacy expansion now
        // (every role gains the assistant entrypoints). The fixture is the no-regression floor —
        // a capability the switch used to grant must never silently disappear.
        // Per role rather than in bulk, so a failure names the role that drifted.
        legacy.forEach((role, expected) -> assertThat(seededGrants.get(role))
                .as("grants for legacy role %s", role)
                .containsAll(expected));
    }

    @Test
    @DisplayName("SYSTEM_ADMINISTRATOR receives only admin and security capability, never a domain superset")
    void systemAdministrator_isSecurityScopedNotSuperuser() {
        Set<String> granted = seededGrants.get("SYSTEM_ADMINISTRATOR");

        assertThat(granted)
                .as("SYSTEM_ADMINISTRATOR must carry the security admin surface")
                .isNotEmpty();
        assertThat(granted)
                .as("SYSTEM_ADMINISTRATOR may hold security:*, MCP administration, the assistant "
                        + "entrypoints and the SYSTEM_ADMIN_SCOPED_EXCEPTIONS carve-outs only — "
                        + "never a broader domain authority")
                .allMatch(permission -> permission.startsWith("security:")
                        || permission.startsWith("mcp:")
                        || permission.startsWith("nlti:")
                        || SYSTEM_ADMIN_SCOPED_EXCEPTIONS.contains(permission));
        assertThat(granted).containsAll(MCP_ADMINISTRATION);

        // ADMIN is the all-domain role; SYSTEM_ADMINISTRATOR must stay strictly narrower.
        assertThat(granted)
                .as("SYSTEM_ADMINISTRATOR must not inherit ADMIN's domain authority")
                .isSubsetOf(seededGrants.get("ADMIN"))
                .hasSizeLessThan(seededGrants.get("ADMIN").size());
    }

    @Test
    @DisplayName("DISPATCHER receives scheduling and dispatch capability and nothing else")
    void dispatcher_isScopedToSchedulingAndDispatch() {
        assertThat(seededGrants.get("DISPATCHER"))
                .containsExactlyInAnyOrder(
                        "location:read",
                        "location:bay:read",
                        "shop:bay:assign",
                        "shop:schedule:view",
                        "shop:schedule:edit",
                        "shop:technician:view",
                        "appointments:view",
                        "appointments:create",
                        "appointments:reschedule",
                        "appointments:cancel",
                        "workorder:workorder:view",
                        "workorder:workorder:assign-technician",
                        "people:availability:view",
                        // the assistant entrypoints every role carries
                        "mcp:chat:execute",
                        "mcp:chat:stream",
                        "nlti:request:submit",
                        "nlti:request:read");
    }

    @Test
    @DisplayName("every granted permission is defined in the seed, so no grant is dropped by the FK join")
    void everyGrantedPermissionIsDefinedInTheSeed() {
        Set<String> granted =
                seededGrants.values().stream().flatMap(Set::stream).collect(Collectors.toCollection(TreeSet::new));

        assertThat(granted).isSubsetOf(definedPermissions);
    }

    @Test
    @DisplayName("every granted permission is in the PermissionCode catalog, so it can reach a JWT")
    void everyGrantedPermissionHasABitIndex() {
        Set<String> uncatalogued = seededGrants.values().stream()
                .flatMap(Set::stream)
                .filter(permission -> PermissionCode.fromCode(permission).isEmpty())
                .collect(Collectors.toCollection(TreeSet::new));

        assertThat(uncatalogued)
                .as("granted permissions with no bit index would be silently dropped from perm_bits")
                .isEmpty();
    }

    @Test
    @DisplayName("permission definitions carry the bit index the PermissionCode catalog assigns")
    void permissionDefinitionsMatchTheCatalogBitIndexes() throws IOException {
        Map<String, Integer> mismatches = new LinkedHashMap<>();
        for (String line : readResource(SEED).lines().toList()) {
            Matcher row = PERMISSION_ROW.matcher(line);
            if (!row.matches()) {
                continue;
            }
            int seeded = Integer.parseInt(row.group(5));
            PermissionCode.fromCode(row.group(1))
                    .filter(code -> code.bitIndex() != seeded)
                    .ifPresent(code -> mismatches.put(row.group(1), seeded));
        }

        assertThat(mismatches)
                .as("seeded bit indexes that disagree with PermissionCode")
                .isEmpty();
    }

    @Test
    @DisplayName("every role receives the assistant entrypoints")
    void everyRoleReceivesTheAssistantBaseline() {
        // Per role, so a failure names the role that is missing them rather than just failing.
        seededGrants.forEach((role, granted) ->
                assertThat(granted).as("assistant baseline for %s", role).containsAll(ASSISTANT_BASELINE));
    }

    @Test
    @DisplayName("MCP administration is reserved for ADMIN and SYSTEM_ADMINISTRATOR")
    void mcpAdministrationIsReservedToAdminRoles() {
        assertThat(seededGrants.get("ADMIN")).containsAll(MCP_ADMINISTRATION);
        assertThat(seededGrants.get("SYSTEM_ADMINISTRATOR")).containsAll(MCP_ADMINISTRATION);

        // Scan the whole restricted surface, not just what both admin roles must hold:
        // mcp:tool:view is equally restricted, and checking only MCP_ADMINISTRATION would let it
        // leak to any role undetected.
        Set<String> holders = seededGrants.entrySet().stream()
                .filter(entry -> entry.getValue().stream().anyMatch(MCP_ADMINISTRATION_SURFACE::contains))
                .map(Map.Entry::getKey)
                .collect(Collectors.toCollection(TreeSet::new));

        assertThat(holders)
                .as("MCP administration must not leak to operational or customer-facing roles")
                .containsExactly("ADMIN", "SYSTEM_ADMINISTRATOR");
    }

    @Test
    @DisplayName("SYSTEM_ADMINISTRATOR holds tool visibility and the NLTI audit ledger")
    void systemAdministratorHoldsToolViewAndNltiAudit() {
        assertThat(seededGrants.get("SYSTEM_ADMINISTRATOR")).contains("mcp:tool:view", "nlti:audit:read");
    }

    @Test
    @DisplayName("the NLTI audit ledger is reserved for ADMIN and SYSTEM_ADMINISTRATOR")
    void nltiAuditReadIsReservedToAdminRoles() {
        Set<String> holders = seededGrants.entrySet().stream()
                .filter(entry -> entry.getValue().contains("nlti:audit:read"))
                .map(Map.Entry::getKey)
                .collect(Collectors.toCollection(TreeSet::new));

        assertThat(holders)
                .as("the NLTI audit ledger exposes other principals' request history")
                .containsExactly("ADMIN", "SYSTEM_ADMINISTRATOR");
    }

    @Test
    @DisplayName("invoice:finalize:override is held by exactly ADMIN and the manager roles agreed on #1374")
    void finalizeOverrideIsHeldByExactlyTheAgreedManagerRoles() {
        Set<String> holders = seededGrants.entrySet().stream()
                .filter(entry -> entry.getValue().contains("invoice:finalize:override"))
                .map(Map.Entry::getKey)
                .collect(Collectors.toCollection(TreeSet::new));

        // pos-invoice's employee-number approval flow resolves the named manager's
        // authority through role_permissions, so an empty holder set silently breaks
        // manager-approval elevation (#1374). Equality rather than containment: the
        // permission caps what a service advisor can finalize, so widening the set is
        // as much a regression as losing it.
        assertThat(holders)
                .as("roles holding invoice:finalize:override")
                .containsExactly(
                        "ACCOUNT_MANAGER", "ADMIN", "GENERAL_MANAGER", "LOCATION_MANAGER", "MANAGER", "SHOP_MANAGER");
    }

    @Test
    @DisplayName("ADMIN holds both tool permissions")
    void adminHoldsToolViewAndManage() {
        assertThat(seededGrants.get("ADMIN")).contains("mcp:tool:view", "mcp:tool:manage");
    }

    @Test
    @DisplayName("contains no duplicate role/permission rows")
    void seed_containsNoDuplicateRows() {
        Set<String> unique = new LinkedHashSet<>(rawGrantRows);

        assertThat(rawGrantRows).hasSameSizeAs(unique);
    }

    @Test
    @DisplayName("every role granted to exists by the time the seed runs")
    void everyGrantedRoleIsCreatableAtMigrationTime() throws IOException {
        Set<String> creatable = new TreeSet<>();

        // Roles created by the other migrations, plus the ones this seed creates itself.
        //
        // Each statement is bounded at its terminating semicolon rather than by a character
        // window. Under-reading would fail a correct migration whose VALUES list is long;
        // over-reading is worse, because running past the statement into this file's own grant
        // block would harvest the role names out of ('ADMIN', 'crm:party:view') rows and the
        // guard would vacuously pass. "INSERT INTO roles" does not prefix-match
        // "INSERT INTO role_permissions", so the grant block is not picked up as a source.
        Pattern quotedRoleName = Pattern.compile("'([A-Z][A-Z_]{2,})'");
        try (var files = Files.list(MIGRATIONS)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".sql")).toList()) {
                String body = Files.readString(file);
                int from = body.indexOf("INSERT INTO roles");
                while (from >= 0) {
                    int end = body.indexOf(';', from);
                    Matcher name = quotedRoleName.matcher(body.substring(from, end < 0 ? body.length() : end));
                    while (name.find()) {
                        creatable.add(name.group(1));
                    }
                    from = body.indexOf("INSERT INTO roles", from + 1);
                }
            }
        }

        assertThat(creatable).as("no role-creating migration found").isNotEmpty();
        // #1613 D8: scoped to this file's own grants. Roles provisioned by bulk load exist after
        // Flyway by design, so they are not grant targets here — which is exactly why their grants
        // moved to the load file rather than staying and failing the seed's own guard.
        assertThat(sqlSeededGrants.keySet())
                .as("granting to a role no migration creates aborts startup: the JOIN resolves "
                        + "nothing and the seed's own assertion raises. Since #1440 every role "
                        + "this baseline grants to must be created by a SQL migration — roles "
                        + "created at runtime through the role-management API exist after "
                        + "Flyway and cannot be a grant target here.")
                .isSubsetOf(creatable);
    }

    @Test
    @DisplayName("does not grant to the legacy roles nothing can ever assign")
    void doesNotGrantToUnreachableLegacyRoles() {
        assertThat(seededGrants.keySet()).doesNotContainAnyElementsOf(UNREACHABLE_LEGACY_ROLES);
    }

    @Test
    @DisplayName("the inventory adjustment roles carry the capability their javadoc documents")
    void inventoryAdjustmentRolesCarryTheirDocumentedCapability() {
        // Equality, not containment: #1373 decided who may create and who may approve, and
        // widening either set is as much a regression as losing it. INVENTORY_MANAGER and
        // INVENTORY_CONTROLLER are permission-identical on purpose — location versus global
        // approval reach lives on role_assignments.scope_type, not in role_permissions.
        assertThat(holdersOf("inventory:adjustment:create"))
                .as("roles that may create an adjustment request")
                .isEqualTo(new TreeSet<>(ADJUSTMENT_CREATORS));
        assertThat(holdersOf("inventory:adjustment:approve"))
                .as("roles that may approve an adjustment")
                .isEqualTo(new TreeSet<>(ADJUSTMENT_APPROVERS));

        // Everyone who can act on an adjustment can also see one.
        assertThat(holdersOf("inventory:adjustment:view")).containsAll(ADJUSTMENT_CREATORS);
    }

    @Test
    @DisplayName("the negative-stock override is reserved for globally scoped approvers")
    void adjustmentOverrideIsReservedToGlobalApprovers() {
        // ScrapServiceImpl enforces this authority when a scrap would drive on-hand below
        // zero. Until #1373 it had no PermissionCode bit index at all, so it could not
        // travel in a JWT and the override path was unreachable for every user including
        // ADMIN. Equality pins the decision that only a global approver may use it.
        assertThat(holdersOf("inventory:adjustment:override"))
                .as("roles holding the negative-stock escape hatch")
                .containsExactly("ADMIN", "INVENTORY_CONTROLLER");
    }

    @Test
    @DisplayName("SHOP_MANAGER carries the shop surface its role description names")
    void shopManagerCarriesTheShopSurface() {
        assertThat(seededGrants.get("SHOP_MANAGER"))
                .as("V3 describes SHOP_MANAGER as full shop management: schedules, "
                        + "assignments and audit review. There is no shop audit permission "
                        + "in pos-shop-manager's manifest, so audit review is not granted.")
                .contains(
                        "location:read",
                        "location:bay:read",
                        "shop:bay:assign",
                        "shop:schedule:view",
                        "shop:schedule:edit",
                        "shop:technician:view");
    }

    @Test
    @DisplayName("the customer-facing roles hold the assistant entrypoints and nothing else")
    void customerFacingRolesHoldOnlyTheAssistantEntrypoints() {
        // #1373 confirmed zero domain capability as correct for both, rather than inherited.
        // Equality makes any future domain grant to an external-facing role a deliberate edit.
        assertThat(seededGrants.get("CUSTOMER")).isEqualTo(new TreeSet<>(ASSISTANT_BASELINE));
        assertThat(seededGrants.get("SELF_SERVICE_CUSTOMER")).isEqualTo(new TreeSet<>(ASSISTANT_BASELINE));
    }

    @Test
    @DisplayName("does not grant to the candidate roles V23 deletes")
    void doesNotGrantToDeletedCandidateRoles() {
        assertThat(seededGrants.keySet()).doesNotContainAnyElementsOf(DELETED_CANDIDATE_ROLES);
    }

    /** Roles holding {@code permission}, sorted, so a failure reads as a set difference. */
    private static Set<String> holdersOf(String permission) {
        return seededGrants.entrySet().stream()
                .filter(entry -> entry.getValue().contains(permission))
                .map(Map.Entry::getKey)
                .collect(Collectors.toCollection(TreeSet::new));
    }

    @Test
    @DisplayName("the resolution assertion lists exactly the roles and permissions the seed grants")
    void resolutionAssertionMatchesTheGrants() throws IOException {
        // Section 4 aborts the migration when a baseline name does not resolve, which is what
        // stops the JOINs above from silently under-granting. It is a hand-maintained copy of
        // the grant block, so it drifts in both directions and each direction breaks something
        // different: a name listed there but no longer granted aborts startup outright once the
        // role or permission goes away (this is what deleting SECURITY_ADMIN and
        // READ_ONLY_SCHEDULER in #1373 would otherwise have done), while a name granted but not
        // listed is a grant the assertion no longer covers, which is the failure it exists to
        // catch. Equality both ways is the only version of this that holds.
        List<String> lines = readResource(SEED).lines().toList();

        Set<String> assertedRoles = new TreeSet<>();
        Set<String> assertedPermissions = new TreeSet<>();
        for (String line : lines) {
            Matcher row = ASSERTION_ROW.matcher(line);
            if (!row.matches()) {
                continue;
            }
            String value = row.group(1);
            // The two lists are distinguishable by shape: permission names are colon-bearing
            // and lower-case, role names are neither.
            if (value.contains(":")) {
                assertedPermissions.add(value);
            } else if (value.matches("[A-Z][A-Z_]+")) {
                assertedRoles.add(value);
            }
        }

        assertThat(assertedRoles)
                .as("no role assertion list parsed out of %s", SEED)
                .isNotEmpty();

        // Section 4 guards this migration, so it is compared against this migration's grants.
        Set<String> grantedPermissions =
                sqlSeededGrants.values().stream().flatMap(Set::stream).collect(Collectors.toCollection(TreeSet::new));

        assertThat(assertedRoles)
                .as("roles asserted in section 4 vs roles actually granted to")
                .isEqualTo(new TreeSet<>(sqlSeededGrants.keySet()));
        assertThat(assertedPermissions)
                .as("permissions asserted in section 4 vs permissions actually granted")
                .isEqualTo(grantedPermissions);
    }

    @Test
    @DisplayName("V31's keep list is exactly the SYSTEM_ADMINISTRATOR grants this seed makes")
    void v31KeepListMatchesTheSeededSystemAdministratorGrants() throws IOException {
        // V31 (#1512) deletes every SYSTEM_ADMINISTRATOR grant *not* in a hardcoded list, because
        // SQL cannot read a repeatable seed in another file. That makes the list a second copy of
        // this block, and a copy drifts. Both directions are a defect, and they fail differently:
        // a name missing from V31 revokes authority the seed deliberately gives the role, and a
        // name in V31 that the seed no longer grants keeps an out-of-band grant alive past the
        // migration written to remove it. Equality is the only version of this that holds.
        Set<String> keepList = parsePermissionLiterals(Files.readString(V31_REVOKE));

        assertThat(keepList).as("no keep list parsed out of %s", V31_REVOKE).isNotEmpty();
        assertThat(keepList)
                .as("V31's keep list vs the seed's SYSTEM_ADMINISTRATOR grants")
                .isEqualTo(seededGrants.get("SYSTEM_ADMINISTRATOR"));
    }

    @Test
    @DisplayName("V31 revokes from SYSTEM_ADMINISTRATOR alone, never role-agnostically")
    void v31RevokeIsScopedToSystemAdministrator() throws IOException {
        // The #1512 investigation turned on V25-V28 deleting by permission_id with no role filter:
        // written to retire grants from the seeded roles, they also stripped 48 grants off
        // SYSTEM_ADMINISTRATOR, a role none of them mentions. V31 must not repeat that. A DELETE
        // against role_permissions here has to name the role it means.
        String body = SQL_LINE_COMMENT.matcher(Files.readString(V31_REVOKE)).replaceAll("");

        assertThat(body)
                .as("V31 must resolve the role it revokes from")
                .contains("FROM roles WHERE name = 'SYSTEM_ADMINISTRATOR'");
        assertThat(body)
                .as("every DELETE against role_permissions in V31 must be role-scoped")
                .containsPattern("DELETE FROM role_permissions\\s+WHERE role_id = sa_role_id");
        assertThat(countOccurrences(body, "DELETE FROM role_permissions"))
                .as("a second, unscoped DELETE would reintroduce the V25-V28 footgun")
                .isEqualTo(1);
    }

    /** Permission names quoted in {@code sql}, with {@code --} comments stripped first. */
    private static Set<String> parsePermissionLiterals(String sql) {
        // Comments first: this migration's header names permission families in prose, and the
        // audit tooling has already been bitten once by scoring a commented-out code as real
        // (docs/rbac-permission-role-audit-2026-08.md, task 7).
        String body = SQL_LINE_COMMENT.matcher(sql).replaceAll("");
        Set<String> names = new TreeSet<>();
        Matcher literal = QUOTED_PERMISSION.matcher(body);
        while (literal.find()) {
            names.add(literal.group(1));
        }
        return names;
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        for (int at = haystack.indexOf(needle); at >= 0; at = haystack.indexOf(needle, at + 1)) {
            count++;
        }
        return count;
    }

    private static String readResource(String name) throws IOException {
        try (InputStream in = RolePermissionBaselineTest.class.getClassLoader().getResourceAsStream(name)) {
            assertThat(in)
                    .as("resource %s not found on the test classpath", name)
                    .isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}

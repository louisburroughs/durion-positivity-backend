package com.positivity.accounting.tenancy;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Every table not whitelisted in {@code db/tenancy-global-tables.txt} carries the tenancy schema
 * (ADR-0062 §2: {@code tenant_id}, row-level security enabled and forced, the {@code tenant_isolation}
 * policy); every whitelisted table has no policy and no row-level security (it may still carry a
 * {@code tenant_id} column as plain data, as {@code event_outbox} does); and the application connects
 * as the non-owner {@code pos_app} role with no bypass (plan R-B7, and the first risk in the plan's
 * table).
 */
@DisplayName("Tenancy schema conformance (ADR-0062)")
class TenancySchemaConformanceIT extends PostgresTenancyTestBase {

    private static final String GLOBAL_TABLES = "db/tenancy-global-tables.txt";
    private static final Set<String> NOT_APPLICATION_TABLES = Set.of("flyway_schema_history");

    @Autowired
    private DataSource applicationDataSource;

    @Test
    void everyNonGlobalTableIsTenantScopedAndEveryGlobalTableIsNot() throws IOException {
        Set<String> global = globalTables();
        JdbcTemplate owner = new JdbcTemplate(ownerDataSource());
        List<Map<String, Object>> tables = owner.queryForList("""
                SELECT c.relname AS table_name,
                       c.relrowsecurity AS rls_enabled,
                       c.relforcerowsecurity AS rls_forced,
                       EXISTS (SELECT 1 FROM information_schema.columns col
                                WHERE col.table_schema = 'public' AND col.table_name = c.relname
                                  AND col.column_name = 'tenant_id') AS has_tenant_id,
                       EXISTS (SELECT 1 FROM pg_policies p
                                WHERE p.schemaname = 'public' AND p.tablename = c.relname
                                  AND p.policyname = 'tenant_isolation') AS has_policy
                  FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                 WHERE n.nspname = 'public' AND c.relkind = 'r'
                 ORDER BY c.relname
                """);
        assertThat(tables).isNotEmpty();

        Set<String> seen = new TreeSet<>();
        for (Map<String, Object> table : tables) {
            String name = (String) table.get("table_name");
            if (NOT_APPLICATION_TABLES.contains(name)) {
                continue;
            }
            seen.add(name);
            if (global.contains(name)) {
                assertThat(table.get("has_policy"))
                        .as("%s is global: no policy", name)
                        .isEqualTo(false);
                assertThat(table.get("rls_enabled"))
                        .as("%s is global: no RLS", name)
                        .isEqualTo(false);
            } else {
                assertThat(table.get("has_tenant_id"))
                        .as("%s must carry tenant_id", name)
                        .isEqualTo(true);
                assertThat(table.get("rls_enabled"))
                        .as("%s must have RLS enabled", name)
                        .isEqualTo(true);
                assertThat(table.get("rls_forced"))
                        .as("%s must have RLS forced", name)
                        .isEqualTo(true);
                assertThat(table.get("has_policy"))
                        .as("%s must carry the tenant_isolation policy", name)
                        .isEqualTo(true);
            }
        }
        assertThat(seen)
                .as("a whitelisted table that no longer exists is a stale whitelist entry")
                .containsAll(global);
    }

    /**
     * The two tables of #2526 are introduced after the baseline, so they are named here: compliant is
     * the tenancy schema they carry, not the file their {@code CREATE TABLE} sits in (TENANCY_SCHEMA,
     * "Adding a table"). Beyond the generic check above: the {@code (tenant_id, pk)} key, the tenant
     * index, and every unique constraint led by {@code tenant_id}.
     */
    @Test
    void theTenantTemplateTablesCarryTheFullTenancySchema() {
        JdbcTemplate owner = new JdbcTemplate(ownerDataSource());
        for (String table : List.of("accounting_template_state", "accounting_template_entry")) {
            assertThat(owner.queryForObject(
                            "SELECT count(*) FROM pg_policies WHERE schemaname = 'public' AND tablename = ? AND"
                                    + " policyname = 'tenant_isolation'",
                            Integer.class,
                            table))
                    .as("%s carries tenant_isolation", table)
                    .isEqualTo(1);
            assertThat(owner.queryForObject(
                            "SELECT ordinal_position FROM information_schema.columns WHERE table_schema = 'public'"
                                    + " AND table_name = ? AND column_name = 'tenant_id'",
                            Integer.class,
                            table))
                    .as("%s has tenant_id first", table)
                    .isEqualTo(1);
            assertThat(owner.queryForObject(
                            "SELECT count(*) FROM pg_indexes WHERE schemaname = 'public' AND indexname = ?",
                            Integer.class,
                            table + "_tenant_idx"))
                    .as("%s has its tenant index", table)
                    .isEqualTo(1);
            List<String> uniques = owner.queryForList(
                    "SELECT pg_get_constraintdef(c.oid) FROM pg_constraint c JOIN pg_class t ON t.oid = c.conrelid"
                            + " WHERE t.relname = ? AND c.contype = 'u'",
                    String.class,
                    table);
            assertThat(uniques)
                    .as("%s: the (tenant_id, pk) key and the natural key", table)
                    .hasSize(2)
                    .allMatch(definition -> definition.startsWith("UNIQUE (tenant_id"));
        }
        assertThat(owner.queryForObject(
                        "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname ="
                                + " 'uq_accounting_template_state_tenant'",
                        String.class))
                .as("one state row per tenant")
                .isEqualTo("UNIQUE (tenant_id)");
        assertThat(owner.queryForObject(
                        "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname ="
                                + " 'uq_accounting_template_entry_key'",
                        String.class))
                .as("one record per entry per tenant")
                .isEqualTo("UNIQUE (tenant_id, entry_key)");
    }

    /**
     * The customer-credit request-id index of #2524 (V6): unique per tenant, led by {@code tenant_id},
     * partial so credits issued before the column existed (NULL) never collide.
     */
    @Test
    void theCustomerCreditRequestIdIndexLeadsWithTenantId() {
        JdbcTemplate owner = new JdbcTemplate(ownerDataSource());
        String definition = owner.queryForObject(
                "SELECT indexdef FROM pg_indexes WHERE schemaname = 'public' AND indexname = 'uq_customer_credit_request_id'",
                String.class);
        assertThat(definition)
                .as("unique, led by tenant_id, partial on a present request id")
                .startsWith("CREATE UNIQUE INDEX uq_customer_credit_request_id ON public.customer_credit USING btree"
                        + " (tenant_id, request_id)")
                .endsWith("WHERE (request_id IS NOT NULL)");
    }

    /**
     * The replica columns of #2508 (V9): nullable, sized as the story states, on tables that keep their
     * tenancy schema (the generic check above covers RLS and the policy; this pins the columns).
     */
    @Test
    void theWalkInReplicaColumnsAreNullableOnTenantScopedTables() {
        JdbcTemplate owner = new JdbcTemplate(ownerDataSource());
        for (Object[] column : List.of(
                new Object[] {"ext_customer_party", "house_account", 20},
                new Object[] {"ext_location", "timezone", 64})) {
            Map<String, Object> definition = owner.queryForMap(
                    "SELECT data_type, character_maximum_length, is_nullable FROM information_schema.columns"
                            + " WHERE table_schema = 'public' AND table_name = ? AND column_name = ?",
                    column[0],
                    column[1]);
            assertThat(definition.get("data_type"))
                    .as("%s.%s type", column[0], column[1])
                    .isEqualTo("character varying");
            assertThat(((Number) definition.get("character_maximum_length")).intValue())
                    .as("%s.%s length", column[0], column[1])
                    .isEqualTo(column[2]);
            assertThat(definition.get("is_nullable"))
                    .as("%s.%s nullable", column[0], column[1])
                    .isEqualTo("YES");
            assertThat(owner.queryForObject(
                            "SELECT count(*) FROM pg_policies WHERE schemaname = 'public' AND tablename = ? AND"
                                    + " policyname = 'tenant_isolation'",
                            Integer.class,
                            column[0]))
                    .as("%s keeps tenant_isolation", column[0])
                    .isEqualTo(1);
        }
    }

    @Test
    void theApplicationConnectsAsANonOwnerRoleWithNoBypass() {
        JdbcTemplate app = new JdbcTemplate(applicationDataSource);
        Map<String, Object> role = app.queryForMap(
                "SELECT current_user AS name, rolsuper, rolbypassrls FROM pg_roles WHERE rolname = current_user");
        assertThat(role.get("name")).isEqualTo(APP_ROLE);
        assertThat(role.get("rolsuper")).isEqualTo(false);
        assertThat(role.get("rolbypassrls")).isEqualTo(false);

        Integer owned = app.queryForObject(
                "SELECT count(*) FROM pg_tables WHERE schemaname = 'public' AND tableowner = current_user",
                Integer.class);
        assertThat(owned)
                .as("pos_app owns nothing, so FORCE ROW LEVEL SECURITY is never moot")
                .isZero();

        // The transitional owner-role default (init-tenancy.sh) must not exist for pos_app.
        String setting = app.queryForObject("SELECT current_setting('app.current_tenant', true)", String.class);
        assertThat(setting == null || setting.isEmpty())
                .as("unbound checkout carries no tenant")
                .isTrue();
    }

    private static Set<String> globalTables() throws IOException {
        return new ClassPathResource(GLOBAL_TABLES)
                .getContentAsString(StandardCharsets.UTF_8)
                .lines()
                .map(line -> line.split("#", 2)[0].trim())
                .filter(line -> !line.isEmpty())
                .collect(Collectors.toSet());
    }
}

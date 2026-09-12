package com.positivity.accounting;

import com.positivity.tenancy.testing.TenantTestSupport;
import java.util.UUID;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestExecutionListeners;

/**
 * Persistence and service tests against the real PostgreSQL baseline ({@code db/migration}) in the
 * shared {@link AccountingPostgresContainer}: the whole Flyway chain plus the repeatable seed,
 * Hibernate {@code validate}, and the pool connected as the non-owner {@code pos_app} role — the
 * arrangement alpha runs (ADR-0062 §3).
 *
 * <p>It replaces the H2 database these tests used to boot, which ran Hibernate's own
 * {@code create-drop} DDL with Flyway switched off. Nothing tied that schema to the flattened
 * Postgres baseline the application actually meets: a test could assert against DDL that does not
 * ship, and could not see any of what the real schema enforces — the CHECK constraints, the
 * deferred journal-entry balance trigger, the seeded chart of accounts, or the dialect differences
 * that have twice turned an all-optional JPQL filter into a 500 in production (issues #1891, #1961).
 *
 * <p>Subclasses inherit:
 *
 * <ul>
 *   <li>{@code @SpringBootTest} in its default mock-web environment + the {@code pg} profile. A
 *       web application context, because the tests on this base import {@code TestSecurityConfig}
 *       and that contributes a {@code SecurityFilterChain}; but no server and no request, so
 *       {@code TenantContextFilter} never runs and the tenant this base binds stays bound for the
 *       whole test method instead of being cleared when a request ends. A test that does drive
 *       HTTP would need the tenant on every request instead, which is why the MockMvc-based
 *       controller and contract tests are not on this base.
 *   <li>the container's default database, with Flyway pointed at the owner role. Every test that
 *       shares this database rolls its work back; a test that commits takes a database of its own
 *       from {@link AccountingPostgresContainer#registerIsolatedDatabase} instead.
 *   <li>{@link TenantTestSupport#TENANT_A} bound around every test method by {@link
 *       TenantBindingTestExecutionListener}. The {@code pg} profile is strict — {@code
 *       pos.tenancy.default-tenant-id} is unset — so without a binding row-level security makes
 *       every insert fail and every read empty.
 * </ul>
 *
 * <p>Requires Docker.
 */
@SpringBootTest
@ActiveProfiles("pg")
@TestExecutionListeners(
        value = TenantBindingTestExecutionListener.class,
        mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS)
public abstract class PostgresIntegrationTestBase {

    /** The tenant every test in this module writes and reads as. */
    protected static final UUID TENANT = TenantTestSupport.TENANT_A;

    @DynamicPropertySource
    static void tenantAwareDatasource(DynamicPropertyRegistry registry) {
        AccountingPostgresContainer.registerDataSourceProperties(registry);
        registry.add("pos.tenancy.tenants", TENANT::toString);
    }
}

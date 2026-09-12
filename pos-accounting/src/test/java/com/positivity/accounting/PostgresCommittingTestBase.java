package com.positivity.accounting;

import com.positivity.tenancy.testing.TenantTestSupport;
import java.util.UUID;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.TestExecutionListeners;

/**
 * {@link PostgresIntegrationTestBase} for a test that <em>commits</em>: same real baseline, same
 * shared container, but a database of the test class's own.
 *
 * <p>These tests cannot be transactional. What they prove — a {@code REQUIRES_NEW} sequence
 * bootstrap, a unique-constraint race, an optimistic-lock collision, a period gate that reads what
 * another transaction committed — only happens across real commits, so each clears the transactional
 * tables it uses in its own setup or teardown instead. On one shared database that clearing reaches
 * rows another class committed, and a class that leaves rows behind after a failure poisons the next
 * one; the symptom is an off-by-a-few count in a class that did nothing wrong. A database apiece
 * removes the coupling, at the cost of one Flyway run each — and still only one container.
 *
 * <p>Subclasses supply that database themselves, because the name has to differ per class:
 *
 * <pre>
 * &#64;DynamicPropertySource
 * static void database(DynamicPropertyRegistry registry) {
 *     AccountingPostgresContainer.registerIsolatedDatabase(registry, "journal-entry-numbering");
 * }
 * </pre>
 *
 * <p>The connection is the schema owner rather than {@code pos_app}: Postgres default privileges are
 * per-database, so a freshly created database would otherwise need its own grant pass, and these
 * tests are not the ones that prove row-level security — {@code TenantIsolationIT} and
 * {@code TenancySchemaConformanceIT} are, on the shared database as {@code pos_app}. The tenant is
 * still bound by {@link TenantBindingTestExecutionListener}, so Hibernate stamps and filters
 * {@code tenant_id} exactly as in production.
 *
 * <p><strong>Threads.</strong> The listener binds the tenant on the test thread only. A test that
 * spawns its own threads — which these race tests do — must bind it there too, with
 * {@link TenantTestSupport#asTenant}; under the {@code pg} profile there is no default tenant to
 * fall back on, so an unbound worker thread writes rows stamped with the nil tenant that the test
 * thread then cannot see.
 *
 * <p>Requires Docker.
 */
@SpringBootTest
@ActiveProfiles("pg")
@TestExecutionListeners(
        value = TenantBindingTestExecutionListener.class,
        mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS)
public abstract class PostgresCommittingTestBase {

    /** The tenant every test in this module writes and reads as. */
    protected static final UUID TENANT = TenantTestSupport.TENANT_A;

    /**
     * Registers the common properties every isolated-database test needs; the datasource itself
     * comes from the subclass's own {@code @DynamicPropertySource}.
     *
     * @param registry the registry the calling {@code @DynamicPropertySource} was handed
     */
    protected static void registerCommonProperties(DynamicPropertyRegistry registry) {
        registry.add("pos.tenancy.tenants", TENANT::toString);
    }
}

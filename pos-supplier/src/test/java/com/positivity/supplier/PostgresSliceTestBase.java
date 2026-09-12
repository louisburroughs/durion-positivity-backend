package com.positivity.supplier;

import com.positivity.tenancy.testing.TenantTestSupport;
import java.util.UUID;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestExecutionListeners;

/**
 * Persistence slices against the real PostgreSQL baseline ({@code db/migration}) in the shared
 * {@link SupplierPostgresContainer}, with Hibernate {@code validate} and the pool connected as the
 * non-owner {@code pos_app} role — the arrangement alpha runs (ADR-0062 §3).
 *
 * <p>It replaces the hand-forked H2 migration chain these slices used to boot. That fork existed
 * because the flattened Postgres baseline is not H2-compatible (row-level security,
 * {@code app_current_tenant()}), and nothing kept it in step with the baseline: a slice could
 * validate its entities against DDL the application would never meet.
 *
 * <p>Subclasses add only what they need — {@code @Import}, extra properties, {@code @Transactional}
 * overrides. They inherit:
 *
 * <ul>
 *   <li>{@code @DataJpaTest} + {@code @AutoConfigureTestDatabase(NONE)} + the {@code pg} profile
 *   <li>the container's datasource, with Flyway pointed at the owner role
 *   <li>{@link TenantTestSupport#TENANT_A} bound around every test method by {@link
 *       TenantBindingTestExecutionListener}. The {@code pg} profile is strict — {@code
 *       pos.tenancy.default-tenant-id} is unset — so without a binding row-level security makes
 *       every insert fail and every read empty. Binding it centrally mirrors production, where the
 *       edge binds the tenant and application code never does.
 * </ul>
 *
 * <p>Requires Docker.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("pg")
@TestExecutionListeners(
        value = TenantBindingTestExecutionListener.class,
        mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS)
@ResourceLock(SupplierPostgresContainer.RESOURCE_LOCK)
public abstract class PostgresSliceTestBase {

    /** The tenant every slice in this module writes and reads as. */
    protected static final UUID TENANT = TenantTestSupport.TENANT_A;

    @DynamicPropertySource
    static void tenantAwareDatasource(DynamicPropertyRegistry registry) {
        SupplierPostgresContainer.registerDataSourceProperties(registry);
        // The fleet a TenantIterator sweep visits (SupplierYamlBootstrap, ExchangeAuditRetentionJob).
        // It binds nothing — pos.tenancy.default-tenant-id stays unset and the runtime stays strict —
        // it only stops the static registry from being empty, which would make a per-tenant job sweep
        // zero tenants and read as "the job did nothing" rather than as a misconfigured test.
        registry.add("pos.tenancy.tenants", TENANT::toString);
    }
}

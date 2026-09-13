package com.positivity.warranty;

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
 * {@link WarrantyPostgresContainer}, with Hibernate {@code validate} and the pool connected as the
 * non-owner {@code pos_app} role — the arrangement alpha runs (ADR-0062 §3).
 *
 * <p>A repository test that boots H2 cannot see the class of defect issue #1891 is about: an
 * all-optional JPQL filter whose placeholder PostgreSQL cannot type is rejected at parse time on
 * PostgreSQL and accepted by H2, so the endpoint 500s in production while its test stays green.
 * Only a statement actually issued to PostgreSQL settles it.
 *
 * <p>Subclasses add only what they need — {@code @Import}, extra properties, {@code @Transactional}
 * overrides. They inherit:
 *
 * <ul>
 *   <li>{@code @DataJpaTest} + {@code @AutoConfigureTestDatabase(NONE)} + the {@code pg} profile
 *   <li>the container's datasource, with Flyway pointed at the owner role
 *   <li>no JPA auditing: {@code JpaConfig} is an application {@code @Configuration} the
 *       slice does not load, so a fixture pins its own {@code createdAt}/{@code updatedAt} — both
 *       columns are {@code NOT NULL} — and a test that asserts on ordering by them controls them
 *       exactly rather than racing the wall clock.
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
@ResourceLock(WarrantyPostgresContainer.RESOURCE_LOCK)
public abstract class PostgresSliceTestBase {

    /** The tenant every slice in this module writes and reads as. */
    protected static final UUID TENANT = TenantTestSupport.TENANT_A;

    @DynamicPropertySource
    static void tenantAwareDatasource(DynamicPropertyRegistry registry) {
        WarrantyPostgresContainer.registerDataSourceProperties(registry);
        // The fleet a TenantIterator sweep would visit. It binds nothing — pos.tenancy.default-tenant-id
        // stays unset and the runtime stays strict — it only stops the static registry from being empty.
        registry.add("pos.tenancy.tenants", TENANT::toString);
    }
}

package com.positivity.customer;

import com.positivity.tenancy.testing.TenantTestSupport;
import java.util.UUID;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestExecutionListeners;

/**
 * Full-context service-level tests against the real PostgreSQL baseline in the shared {@link
 * CustomerPostgresContainer}: the whole Flyway chain plus the repeatable seed, Hibernate
 * {@code validate}, and the pool connected as the non-owner {@code pos_app} role — the arrangement
 * alpha runs (ADR-0062 §3).
 *
 * <p>The counterpart to {@link PostgresSliceTestBase} for tests that need the service layer rather
 * than the repositories alone. It is deliberately {@code WebEnvironment.NONE}: these tests drive
 * services directly, and without a servlet container {@code TenantContextFilter} never runs, so the
 * tenant this base binds stays bound for the whole test method instead of being cleared when a
 * request ends.
 *
 * <p>{@link TenantTestSupport#TENANT_A} is bound around every test method by {@link
 * TenantBindingTestExecutionListener}; the {@code pg} profile is strict, so without it row-level
 * security makes every insert fail and every read empty.
 *
 * <p>Requires Docker.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("pg")
@TestExecutionListeners(
        value = TenantBindingTestExecutionListener.class,
        mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS)
public abstract class PostgresIntegrationTestBase {

    /** The tenant every test in this module writes and reads as. */
    protected static final UUID TENANT = TenantTestSupport.TENANT_A;

    @DynamicPropertySource
    static void tenantAwareDatasource(DynamicPropertyRegistry registry) {
        CustomerPostgresContainer.registerDataSourceProperties(registry);
        registry.add("pos.tenancy.tenants", TENANT::toString);
    }
}

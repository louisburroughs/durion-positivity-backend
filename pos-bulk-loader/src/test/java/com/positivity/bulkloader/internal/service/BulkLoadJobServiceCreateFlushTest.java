package com.positivity.bulkloader.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.positivity.bulkloader.internal.config.JpaAuditingConfig;
import com.positivity.bulkloader.internal.dto.BulkLoadJobCreateRequest;
import com.positivity.bulkloader.internal.entity.BulkLoadJob;
import com.positivity.bulkloader.internal.enums.DomainType;
import com.positivity.bulkloader.internal.enums.JobStatus;
import com.positivity.bulkloader.internal.repository.BulkLoadJobRepository;
import com.positivity.tenancy.TenancyProperties;
import com.positivity.tenancy.TenantContext;
import com.positivity.tenancy.TenantResolver;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Copilot review of PR #1955, third round (Finding 4): {@code createJobInTenant} runs inside the
 * {@code TransactionTemplate} transaction {@link BulkLoadJobServiceImpl#createJob} opens, so a
 * plain {@code jobRepository.save(job)} only enqueues the insert — Hibernate defers it to
 * commit-time flush, which happens after {@code createJobInTenant} has already returned control to
 * {@code createJob}, past the local {@code catch (DataIntegrityViolationException)}. A concurrent
 * collision on the one-active-job-per-operator index would then reach the caller as a raw {@code
 * DataIntegrityViolationException} instead of converging on the intended 409 {@code
 * IllegalStateException}, the same defect class {@code
 * RoleManagementServiceImpl.TemplateRoleProvisioner#attempt} was fixed for last round with {@code
 * saveAndFlush}.
 *
 * <p>A Mockito test cannot prove this: a mocked repository throws (or doesn't) exactly when the
 * test tells it to, so it cannot show that the real exception surfaces only at flush, not at
 * {@code save()}. This test uses a real H2 database and a real unique constraint instead.
 *
 * <p>The constraint here is a plain {@code UNIQUE (tenant_id, operator_id)}, not the production
 * partial index — H2 does not support Postgres's filtered/partial unique indexes at all (verified
 * empirically against H2 2.4.240: {@code CREATE UNIQUE INDEX ... WHERE ...} is a syntax error in
 * every form tried), so the exact {@code idx_bulk_load_job_one_active_per_operator} predicate
 * cannot be reproduced here. That the migration's status list matches {@link
 * BulkLoadJobServiceImpl#ACTIVE_STATUSES}' complement is instead covered by {@link
 * BulkLoadJobActiveIndexConformanceTest}, which parses the migration directly. What this test
 * proves is orthogonal and DB-agnostic: that the second, colliding insert of this method's own
 * transaction throws where the code can still catch it, not after.
 */
@DataJpaTest(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:pos_bulk_create_flush;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;"
                    + "DB_CLOSE_ON_EXIT=FALSE",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
            // Schema from the entity mappings: the module's Flyway baseline is PostgreSQL-only, and
            // this test's own constraint (below) stands in for the partial index it cannot express.
            "spring.jpa.hibernate.ddl-auto=create-drop",
            "spring.flyway.enabled=false",
            "spring.jpa.properties.hibernate.timezone.default_storage=NORMALIZE_UTC"
        })
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import({JpaAuditingConfig.class, BulkLoadJobServiceCreateFlushTest.AuditClockConfig.class})
// BulkLoadJobServiceImpl manages its own transactions with TransactionTemplate; suspending
// @DataJpaTest's default per-test transaction lets those commit and flush for real instead of
// nesting inside one the test would roll back.
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class BulkLoadJobServiceCreateFlushTest {

    private static final String OPERATOR_ID = "operator-flush-1";
    private static final UUID TENANT_ID = UUID.fromString("01900000-0000-7000-8000-00000000f105");

    @Autowired
    private BulkLoadJobRepository jobRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private DataSource dataSource;

    private BulkLoadJobServiceImpl service;

    @BeforeEach
    void setUp() throws SQLException {
        addOneJobPerOperatorConstraint();

        BulkLoadTenantBinding tenantBinding = mock(BulkLoadTenantBinding.class);
        when(tenantBinding.resolveTarget(TENANT_ID, DomainType.CATALOG_PRODUCT)).thenReturn(TENANT_ID);

        service = new BulkLoadJobServiceImpl(
                jobRepository,
                mock(BulkLoadBatchLauncher.class),
                Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC),
                transactionManager,
                tenantBinding,
                new TenantResolver(new TenancyProperties()));
    }

    /**
     * A separate auto-commit connection, not the JPA-managed one: H2 DDL commits its own
     * transaction regardless, and running it outside Hibernate's session keeps this setup step
     * independent of whatever the test method does with its own transactions afterwards.
     */
    private void addOneJobPerOperatorConstraint() throws SQLException {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE bulk_load_job ADD CONSTRAINT test_one_job_per_operator"
                    + " UNIQUE (tenant_id, operator_id)");
        }
    }

    @Test
    @DisplayName("a create that collides with the active-job constraint surfaces as 409"
            + " IllegalStateException, not a raw DataIntegrityViolationException")
    void collidingCreateConvergesOnIllegalStateException() {
        BulkLoadJobCreateRequest first = createRequest("first.csv");
        service.createJob(first, OPERATOR_ID);
        // countByOperatorIdAndStatusIn(ACTIVE_STATUSES) must see zero active jobs, or the second
        // create's pre-check throws its own IllegalStateException without ever reaching save —
        // exercising a different, already-correct guard rather than the flush this test is about.
        // Marking the first job COMPLETED (bypassing the service, which has no such transition)
        // clears that pre-check while the row itself stays behind to collide with this test's
        // constraint, deliberately reproducing the "check says clear, insert still collides"
        // shape a genuine concurrent create would hit against the real partial index.
        markCompletedDirectly(OPERATOR_ID);

        BulkLoadJobCreateRequest second = createRequest("second.csv");
        assertThatThrownBy(() -> service.createJob(second, OPERATOR_ID))
                .as("saveAndFlush must force the constraint violation to surface inside"
                        + " createJobInTenant's own try/catch, not after it has already returned")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Operator already has an active bulk load job in progress");

        // findByOperatorId is filtered by Hibernate's @TenantId, so it has to run under the same
        // tenant binding createJob used, not this (unbound) test thread's own.
        assertThat(TenantContext.callAs(TENANT_ID, () -> jobRepository.findByOperatorId(OPERATOR_ID)))
                .as("only the first job was ever persisted; the second's insert was rejected at flush")
                .hasSize(1);
    }

    private void markCompletedDirectly(String operatorId) {
        TenantContext.callAs(TENANT_ID, () -> {
            BulkLoadJob job = jobRepository.findByOperatorId(operatorId).get(0);
            job.setStatus(JobStatus.COMPLETED);
            return jobRepository.saveAndFlush(job);
        });
    }

    private BulkLoadJobCreateRequest createRequest(String fileName) {
        BulkLoadJobCreateRequest request = new BulkLoadJobCreateRequest();
        request.setFileName(fileName);
        request.setDomainType(DomainType.CATALOG_PRODUCT);
        request.setTenantId(TENANT_ID);
        return request;
    }

    /** {@link JpaAuditingConfig} needs a {@code Clock} bean; this test's own is unrelated to it. */
    @TestConfiguration(proxyBeanMethods = false)
    static class AuditClockConfig {

        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
        }
    }
}

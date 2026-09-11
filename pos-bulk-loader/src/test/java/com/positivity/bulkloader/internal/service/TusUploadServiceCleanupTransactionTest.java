package com.positivity.bulkloader.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;

import com.positivity.bulkloader.internal.config.JpaAuditingConfig;
import com.positivity.bulkloader.internal.entity.TusUpload;
import com.positivity.bulkloader.internal.repository.TusUploadRepository;
import com.positivity.tenancy.StaticTenantRegistry;
import com.positivity.tenancy.TenancyProperties;
import com.positivity.tenancy.TenantContext;
import com.positivity.tenancy.TenantIterator;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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
 * Copilot review of PR #1955, fourth round (Finding 3): {@code cleanupExpiredUploadsOfBoundTenant}'s
 * per-row {@code try}/{@code catch} looked like it isolated one bad row's failure from the rest of
 * the sweep, but a mocked {@code PlatformTransactionManager} cannot prove that — its {@code commit}
 * is a no-op, so nothing in that test can ever observe a real rollback. Against a real transaction
 * manager, {@code tusUploadRepository.delete(upload)} does not fail at the call site: Hibernate
 * defers the actual {@code DELETE} to flush, which for a shared transaction happens at that
 * transaction's single commit — after the loop, outside every per-row {@code catch}. One row's
 * constraint violation there fails the whole commit and rolls back every delete the loop thought it
 * had already done, not just the bad one.
 *
 * <p>This test proves the fix instead: {@link TusUploadServiceImpl#cleanupExpiredUploadsOfBoundTenant}
 * now gives each row's deletion its own {@code TransactionTemplate} transaction, so a row whose
 * delete is refused by a real foreign key rolls back alone while an earlier row's delete, already
 * committed in its own transaction, survives. The blocking foreign key stands in for whatever a real
 * deployment's constraint would be; what matters is that it fails at commit, not at the {@code
 * delete()} call, which a Mockito {@code doThrow} cannot reproduce.
 */
@DataJpaTest(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:pos_bulk_tus_cleanup;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;"
                    + "DB_CLOSE_ON_EXIT=FALSE",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
            // Schema from the entity mappings, same reasoning as BulkLoadJobServiceCreateFlushTest:
            // the module's Flyway baseline is PostgreSQL-only, and this test adds its own blocking
            // foreign key afterwards.
            "spring.jpa.hibernate.ddl-auto=create-drop",
            "spring.flyway.enabled=false",
            "spring.jpa.properties.hibernate.timezone.default_storage=NORMALIZE_UTC"
        })
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import({JpaAuditingConfig.class, TusUploadServiceCleanupTransactionTest.AuditClockConfig.class})
// TusUploadServiceImpl manages its own transactions with TransactionTemplate; suspending
// @DataJpaTest's default per-test transaction lets each one commit and flush for real instead of
// nesting inside one the test would roll back.
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class TusUploadServiceCleanupTransactionTest {

    private static final Instant NOW = Instant.parse("2026-01-15T10:00:00Z");
    private static final String OPERATOR = "operator-cleanup";
    private static final UUID TENANT_ID = UUID.fromString("01900000-0000-7000-8000-00000000f106");

    @Autowired
    private TusUploadRepository tusUploadRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private DataSource dataSource;

    @TempDir
    Path storageRoot;

    private TusUploadServiceImpl service;

    @BeforeEach
    void setUp() {
        TenancyProperties tenancyProperties = new TenancyProperties();
        tenancyProperties.setTenants(List.of(TENANT_ID));
        service = new TusUploadServiceImpl(
                tusUploadRepository,
                mock(BulkLoadJobService.class),
                storageRoot.toString(),
                24,
                Clock.fixed(NOW, ZoneOffset.UTC),
                new TenantIterator(new StaticTenantRegistry(tenancyProperties)),
                transactionManager);
    }

    /**
     * A separate auto-commit connection, not the JPA-managed one, added once {@code tus_upload}
     * exists: a child row referencing {@code blocked}'s id with no cascade means deleting that row
     * fails at commit with a real foreign-key violation, exactly the failure shape a mocked
     * repository cannot produce.
     */
    private void blockDeletionOf(UUID uploadId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS test_tus_upload_lock ("
                    + "id UUID PRIMARY KEY, tus_upload_id UUID NOT NULL REFERENCES tus_upload(id))");
            statement.execute("INSERT INTO test_tus_upload_lock (id, tus_upload_id) VALUES ('" + UUID.randomUUID()
                    + "', '" + uploadId + "')");
        }
    }

    private TusUpload persisted(Instant expiresAt) {
        return TenantContext.callAs(TENANT_ID, () -> {
            TusUpload upload = new TusUpload();
            upload.setJobId(UUID.randomUUID());
            upload.setOperatorId(OPERATOR);
            upload.setFileName("parts.csv");
            upload.setTotalSize(16L);
            upload.setUploadOffset(16L);
            upload.setCompleted(false);
            upload.setExpiresAt(expiresAt);
            return tusUploadRepository.saveAndFlush(upload);
        });
    }

    @Test
    @DisplayName("a real transaction manager: one row's delete failing at commit does not roll back"
            + " another row's already-committed delete")
    void cleanupExpiredUploads_underARealTransactionManager_isolatesAFailedRowFromTheRest() throws SQLException {
        TusUpload blocked = persisted(NOW.minusSeconds(120));
        TusUpload removable = persisted(NOW.minusSeconds(60));
        blockDeletionOf(blocked.getId());

        assertThatCode(() -> service.cleanupExpiredUploads()).doesNotThrowAnyException();

        TenantContext.callAs(TENANT_ID, () -> {
            assertThat(tusUploadRepository.findById(removable.getId()))
                    .as("this row's delete ran in its own transaction and committed before the"
                            + " blocked row's delete ever failed")
                    .isEmpty();
            assertThat(tusUploadRepository.findById(blocked.getId()))
                    .as("this row's delete was refused by the foreign key and rolled back, alone,"
                            + " in its own transaction")
                    .isPresent();
            return null;
        });
    }

    /** {@link JpaAuditingConfig} needs a {@code Clock} bean; this test's own is unrelated to it. */
    @TestConfiguration(proxyBeanMethods = false)
    static class AuditClockConfig {

        @Bean
        Clock clock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }
}

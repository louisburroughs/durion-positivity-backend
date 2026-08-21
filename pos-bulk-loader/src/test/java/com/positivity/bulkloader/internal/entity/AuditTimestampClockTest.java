package com.positivity.bulkloader.internal.entity;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.bulkloader.internal.config.JpaAuditingConfig;
import com.positivity.bulkloader.internal.enums.DomainType;
import com.positivity.bulkloader.internal.enums.JobStatus;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * {@code TusUpload.createdAt} must come from the injected application {@link Clock}, not wall time.
 *
 * <p>It previously came from a {@code @PrePersist} callback reading the system clock. The clock here
 * is fixed one year in the past, where an accelerated run starts, so a system-clock value would land
 * on today's date and fail.
 *
 * <p>The production column also carries a {@code DEFAULT NOW()} in the migration. That default is
 * dormant by construction — Hibernate always sends a value for a mapped field — and this test proves
 * the value Hibernate sends is the clock's, which is the half that was actually at risk.
 */
@DataJpaTest(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:pos_bulk_audit;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
            // Schema from the entity mappings: this test is about where the timestamp value comes
            // from, and the module's Flyway baseline is PostgreSQL-only.
            "spring.jpa.hibernate.ddl-auto=create-drop",
            "spring.flyway.enabled=false",
            // The generated column is a plain TIMESTAMP, so without this the instant round-trips
            // through the JVM's local offset. Production stores TIMESTAMPTZ.
            "spring.jpa.properties.hibernate.timezone.default_storage=NORMALIZE_UTC"
        })
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import({JpaAuditingConfig.class, AuditTimestampClockTest.FixedClockConfig.class})
class AuditTimestampClockTest {

    /** One year before the run, where an accelerated deployment anchors virtual time. */
    private static final Instant VIRTUAL_NOW = Instant.parse("2025-08-20T12:00:00Z");

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private MutableClock clock;

    @Test
    @DisplayName("TusUpload stamps createdAt from the injected clock, not the column default")
    void tusUpload_usesInjectedClockOnInsert() {
        clock.setInstant(VIRTUAL_NOW);
        UUID jobId = insertParentJob();

        TusUpload upload = new TusUpload();
        upload.setJobId(jobId);
        upload.setOperatorId("operator-1");
        upload.setFileName("catalog.csv");
        upload.setTotalSize(1_024L);
        upload.setUploadOffset(0L);
        upload.setCompleted(false);
        upload.setExpiresAt(VIRTUAL_NOW.plusSeconds(3_600));

        TusUpload saved = entityManager.merge(upload);
        entityManager.flush();
        UUID id = saved.getId();

        // The value auditing wrote is the whole claim: it came from the injected clock, not the
        // system clock. Asserting it before the round-trip keeps the test off H2's timestamp
        // column-type behaviour, which is not what this test is about.
        assertThat(saved.getCreatedAt()).isEqualTo(VIRTUAL_NOW);

        entityManager.clear();

        TusUpload reloaded = entityManager.find(TusUpload.class, id);
        assertThat(reloaded.getCreatedAt())
                .as("the persisted timestamp is a year in the past, not wall time")
                .isBefore(Instant.parse("2026-01-01T00:00:00Z"));
    }

    /**
     * {@code tus_upload.job_id} is a foreign key, so a parent row has to exist first. Built through
     * the mapped entity rather than raw SQL so the enum columns cannot drift out of their checks.
     */
    private UUID insertParentJob() {
        BulkLoadJob job = new BulkLoadJob();
        job.setOperatorId("operator-1");
        job.setFileName("catalog.csv");
        job.setDomainType(DomainType.CATALOG_PRODUCT);
        job.setStatus(JobStatus.CREATED);
        BulkLoadJob saved = entityManager.merge(job);
        entityManager.flush();
        return saved.getId();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClockConfig {

        @Bean
        MutableClock clock() {
            return new MutableClock(VIRTUAL_NOW);
        }
    }

    /** A clock the test moves explicitly, so no assertion depends on real elapsed time. */
    static final class MutableClock extends Clock {

        private Instant instant;

        MutableClock(Instant instant) {
            this.instant = instant;
        }

        void setInstant(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}

package com.positivity.tax.internal.entity;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.tax.common.enums.ExemptionReasonCode;
import com.positivity.tax.common.enums.TaxProviderTransactionStatus;
import com.positivity.tax.internal.config.JpaAuditingConfig;
import com.positivity.tax.internal.enums.ExemptionCertificateStatus;
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
 * Audit timestamps must come from the injected application {@link Clock}, not wall time.
 *
 * <p>The clock is fixed one year in the past, which is where an accelerated run starts. A timestamp
 * written from the system clock would land on today's date and fail these assertions.
 */
@DataJpaTest(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:pos_tax_audit;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
            "spring.jpa.hibernate.ddl-auto=validate",
            "spring.flyway.enabled=true",
            "spring.flyway.locations=classpath:db/migration"
        })
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import({JpaAuditingConfig.class, AuditTimestampClockTest.FixedClockConfig.class})
class AuditTimestampClockTest {

    /** One year before the run, where an accelerated deployment anchors virtual time. */
    private static final Instant VIRTUAL_NOW = Instant.parse("2025-08-20T12:00:00Z");

    private static final Instant LATER = Instant.parse("2025-11-03T08:30:00Z");

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private MutableClock clock;

    @Test
    @DisplayName("ExemptionCertificate stamps createdAt/updatedAt from the injected clock")
    void exemptionCertificate_usesInjectedClockOnInsertAndUpdate() {
        clock.setInstant(VIRTUAL_NOW);

        ExemptionCertificate saved = entityManager.merge(ExemptionCertificate.builder()
                .customerId("CUST-AUDIT")
                .stateScope("CA")
                .reasonCode(ExemptionReasonCode.RESALE)
                .effectiveFrom(java.time.LocalDate.parse("2025-01-01"))
                .status(ExemptionCertificateStatus.ACTIVE)
                .build());
        entityManager.flush();
        UUID id = saved.getId();
        entityManager.clear();

        ExemptionCertificate reloaded = entityManager.find(ExemptionCertificate.class, id);
        assertThat(reloaded.getCreatedAt()).isEqualTo(VIRTUAL_NOW);
        assertThat(reloaded.getUpdatedAt()).isEqualTo(VIRTUAL_NOW);

        clock.setInstant(LATER);
        reloaded.setStatus(ExemptionCertificateStatus.REVOKED);
        entityManager.flush();
        entityManager.clear();

        ExemptionCertificate updated = entityManager.find(ExemptionCertificate.class, id);
        assertThat(updated.getUpdatedAt()).isEqualTo(LATER);
        assertThat(updated.getCreatedAt()).isEqualTo(VIRTUAL_NOW);
    }

    @Test
    @DisplayName("TaxProviderTransaction stamps createdAt/updatedAt from the injected clock")
    void taxProviderTransaction_usesInjectedClockOnInsertAndUpdate() {
        clock.setInstant(VIRTUAL_NOW);

        TaxProviderTransaction saved = entityManager.merge(TaxProviderTransaction.builder()
                .referenceId(UUID.randomUUID())
                .referenceType("INVOICE")
                .provider("TEST_MODE")
                .status(TaxProviderTransactionStatus.PENDING_COMMIT)
                .attempts(1)
                .build());
        entityManager.flush();
        UUID id = saved.getId();
        entityManager.clear();

        TaxProviderTransaction reloaded = entityManager.find(TaxProviderTransaction.class, id);
        assertThat(reloaded.getCreatedAt()).isEqualTo(VIRTUAL_NOW);
        assertThat(reloaded.getUpdatedAt()).isEqualTo(VIRTUAL_NOW);

        clock.setInstant(LATER);
        reloaded.setAttempts(2);
        entityManager.flush();
        entityManager.clear();

        TaxProviderTransaction updated = entityManager.find(TaxProviderTransaction.class, id);
        assertThat(updated.getUpdatedAt()).isEqualTo(LATER);
        assertThat(updated.getCreatedAt()).isEqualTo(VIRTUAL_NOW);
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

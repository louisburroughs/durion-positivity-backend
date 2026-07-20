package com.positivity.tax.internal.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.tax.common.enums.ExemptionReasonCode;
import com.positivity.tax.internal.entity.ExemptionCertificate;
import com.positivity.tax.internal.enums.ExemptionCertificateStatus;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.data.domain.Limit;
import org.springframework.test.context.ActiveProfiles;

/**
 * Database-level contract for {@link ExemptionCertificateRepository} (story T3), exercised
 * against H2 with the real Flyway {@code V1__exemption_certificate.sql} migration and
 * Hibernate schema validation, so the migration and the entity mapping are proven
 * consistent.
 */
@DataJpaTest(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:pos_tax_repo;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
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
class ExemptionCertificateRepositoryTest {

    private static final LocalDate TXN = LocalDate.parse("2026-06-01");

    @Autowired
    private ExemptionCertificateRepository repository;

    private ExemptionCertificate cert(
            String customerId,
            String stateScope,
            ExemptionCertificateStatus status,
            String effectiveFrom,
            String expiresAt) {
        return ExemptionCertificate.builder()
                .customerId(customerId)
                .stateScope(stateScope)
                .reasonCode(ExemptionReasonCode.RESALE)
                .effectiveFrom(LocalDate.parse(effectiveFrom))
                .expiresAt(expiresAt == null ? null : LocalDate.parse(expiresAt))
                .status(status)
                .build();
    }

    @Test
    @DisplayName("Migration + mapping round-trips: UUID v7 id and audit timestamps are populated on save")
    void savesAndGeneratesIdAndTimestamps() {
        ExemptionCertificate saved =
                repository.save(cert("CUST-1", "CA", ExemptionCertificateStatus.ACTIVE, "2026-01-01", "2026-12-31"));
        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();
    }

    @Test
    @DisplayName("findActiveForCustomer returns only active, effective, non-expired certificates")
    void findActiveFiltersByWindowAndStatus() {
        repository.save(cert("CUST-1", "CA", ExemptionCertificateStatus.ACTIVE, "2026-01-01", "2026-12-31"));
        repository.save(cert("CUST-1", "CA", ExemptionCertificateStatus.ACTIVE, "2026-05-01", null)); // most recent
        repository.save(cert("CUST-1", "CA", ExemptionCertificateStatus.REVOKED, "2026-01-01", null)); // excluded
        repository.save(cert("CUST-1", "CA", ExemptionCertificateStatus.ACTIVE, "2026-05-01", "2026-05-15")); // expired
        repository.save(cert("CUST-2", "CA", ExemptionCertificateStatus.ACTIVE, "2026-01-01", null)); // other customer

        var results = repository.findActiveForCustomer("CUST-1", "CA", ExemptionReasonCode.RESALE, TXN, Limit.of(5));

        // Two qualify (effective, not expired, active); most-recently-effective first.
        assertThat(results).hasSize(2);
        assertThat(results.get(0).getEffectiveFrom()).isEqualTo(LocalDate.parse("2026-05-01"));
    }

    @Test
    @DisplayName("findActiveForCustomer matches a null-scope certificate for any requested state")
    void findActiveMatchesNullScope() {
        repository.save(cert("CUST-9", null, ExemptionCertificateStatus.ACTIVE, "2026-01-01", null));

        var results = repository.findActiveForCustomer("CUST-9", "TX", ExemptionReasonCode.RESALE, TXN, Limit.of(5));

        assertThat(results).hasSize(1);
    }

    @Test
    @DisplayName("findActiveForCustomer excludes a future-dated certificate")
    void findActiveExcludesFuture() {
        repository.save(cert("CUST-3", "CA", ExemptionCertificateStatus.ACTIVE, "2026-07-01", null));

        var results = repository.findActiveForCustomer("CUST-3", "CA", ExemptionReasonCode.RESALE, TXN, Limit.of(5));

        assertThat(results).isEmpty();
    }
}

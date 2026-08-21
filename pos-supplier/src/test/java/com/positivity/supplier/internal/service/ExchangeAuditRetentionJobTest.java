package com.positivity.supplier.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.positivity.supplier.TestClockConfig;
import com.positivity.supplier.internal.config.JpaConfig;
import com.positivity.supplier.internal.domain.model.ProtocolFamily;
import com.positivity.supplier.internal.domain.model.SupplierCapability;
import com.positivity.supplier.internal.entity.ExchangeAuditEntity;
import com.positivity.supplier.internal.enums.PayloadCaptureLevel;
import com.positivity.supplier.internal.repository.ExchangeAuditRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;

/**
 * The retention purge (ADR-0050 §7): payload content expires, the metadata trail does not.
 *
 * <p>The idempotence assertions carry weight beyond tidiness. Every replica runs this schedule and there is
 * deliberately no lease, so on a multi-instance deployment purges overlap. That is only safe because the
 * {@code UPDATE} predicate is self-limiting, and "self-limiting" is a claim about a WHERE clause that should
 * be tested rather than asserted in a comment.
 */
@DataJpaTest(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:pos_supplier_purge;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
            "spring.jpa.hibernate.ddl-auto=validate"
        })
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaConfig.class, TestClockConfig.class})
class ExchangeAuditRetentionJobTest {

    private static final Instant NOW = Instant.parse("2027-10-01T03:30:00Z");
    private static final Clock FIXED = Clock.fixed(NOW, ZoneOffset.UTC);

    @Autowired
    private ExchangeAuditRepository auditRepository;

    @Autowired
    private jakarta.persistence.EntityManager entityManager;

    /**
     * Detaches everything before reading back.
     *
     * <p>The purge is a bulk {@code @Modifying} UPDATE, which by design bypasses the persistence context —
     * that is what keeps it from decrypting every payload just to discard it. The consequence for a test is
     * that {@code findById} would return the still-cached entity with its payload intact and the assertion
     * would fail against a stale copy rather than against the database. Clearing first is what makes these
     * assertions about the row that is actually stored.
     */
    private void readFromDatabase() {
        entityManager.clear();
    }

    private ExchangeAuditRetentionJob job() {
        return new ExchangeAuditRetentionJob(auditRepository, FIXED, Duration.ofDays(400));
    }

    private UUID seed(Instant startedAt) {
        return auditRepository
                .saveAndFlush(ExchangeAuditEntity.builder()
                        .vendorProfileId(UUID.randomUUID())
                        .supplierRef("michelin-eu")
                        .capability(SupplierCapability.STOCK_INQUIRY)
                        .protocolFamily(ProtocolFamily.EDIWHEEL_A25)
                        .protocolVersion("A2_5")
                        .httpMethod("POST")
                        .endpointUri("https://edi.michelin.example/a25")
                        .attempt(1)
                        .correlationId("corr-" + UUID.randomUUID())
                        .outcome("OK")
                        .httpStatus(200)
                        .startedAt(startedAt)
                        .durationMs(120L)
                        .captureLevel(PayloadCaptureLevel.FULL)
                        .requestPayload("<StockInquiry/>")
                        .responsePayload("<StockInquiryResponse/>")
                        .build())
                .getExchangeAuditId();
    }

    @Test
    void nullsPayloadsPastTheWindowAndKeepsTheMetadataRow() {
        UUID old = seed(NOW.minus(Duration.ofDays(401)));

        job().purgeExpiredPayloads();
        readFromDatabase();

        ExchangeAuditEntity row = auditRepository.findById(old).orElseThrow();
        assertThat(row.getRequestPayload()).isNull();
        assertThat(row.getResponsePayload()).isNull();
        assertThat(row.getPayloadsPurgedAt())
                .as("stamped so a later reader can tell 'purged' from 'never captured'")
                .isEqualTo(NOW);
        assertThat(row.getOutcome())
                .as("the trail of WHAT HAPPENED is retained permanently; only the content expires")
                .isEqualTo("OK");
    }

    @Test
    void leavesPayloadsInsideTheWindowAlone() {
        UUID recent = seed(NOW.minus(Duration.ofDays(399)));

        job().purgeExpiredPayloads();
        readFromDatabase();

        assertThat(auditRepository.findById(recent).orElseThrow().getRequestPayload())
                .isEqualTo("<StockInquiry/>");
    }

    /**
     * The property that makes running this on every replica without a lease safe. A second overlapping purge
     * must match nothing, not re-stamp rows or touch anything new.
     */
    @Test
    void aSecondOverlappingPurgeIsANoOp() {
        seed(NOW.minus(Duration.ofDays(401)));

        job().purgeExpiredPayloads();
        readFromDatabase();
        int secondPass = auditRepository.purgePayloadsOlderThan(NOW.minus(Duration.ofDays(400)), NOW.plusSeconds(60));

        assertThat(secondPass)
                .as("payloads_purged_at IS NULL plus a non-null payload column makes the predicate"
                        + " self-limiting -- which is what lets several instances purge at once")
                .isZero();
        assertThat(auditRepository.countPurgeableOlderThan(NOW))
                .as("and nothing is left behind for a third pass either")
                .isZero();
    }

    @Test
    void refusesANonPositiveRetentionWindowAtStartup() {
        assertThatThrownBy(() -> new ExchangeAuditRetentionJob(auditRepository, FIXED, Duration.ZERO))
                .as("a zero window would purge everything on the next tick, including exchanges still"
                        + " under investigation")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must be positive");
        assertThatThrownBy(() -> new ExchangeAuditRetentionJob(auditRepository, FIXED, Duration.ofDays(-1)))
                .isInstanceOf(IllegalStateException.class);
    }
}

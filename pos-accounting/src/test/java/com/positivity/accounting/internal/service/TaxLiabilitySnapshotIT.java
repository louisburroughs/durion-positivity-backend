package com.positivity.accounting.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.positivity.accounting.internal.config.TestSecurityConfig;
import com.positivity.accounting.internal.dto.TaxLiabilitySnapshotResponse;
import com.positivity.accounting.internal.dto.TaxLiabilitySnapshotVerification;
import com.positivity.accounting.internal.entity.AccountingPeriod;
import com.positivity.accounting.internal.entity.ExtInvoice;
import com.positivity.accounting.internal.entity.ExtInvoiceTax;
import com.positivity.accounting.internal.entity.TaxLiabilitySnapshot;
import com.positivity.accounting.internal.enums.AccountingPeriodStatus;
import com.positivity.accounting.internal.enums.TaxLiabilitySnapshotStatus;
import com.positivity.accounting.internal.exception.TaxSnapshotConflictException;
import com.positivity.accounting.internal.exception.TaxSnapshotPeriodNotClosedException;
import com.positivity.accounting.internal.repository.AccountingPeriodRepository;
import com.positivity.accounting.internal.repository.ExtInvoiceRepository;
import com.positivity.accounting.internal.repository.ExtInvoiceTaxRepository;
import com.positivity.accounting.internal.repository.TaxLiabilitySnapshotRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Real-Postgres IT for the tax-liability period-close freeze (issue #998, Phase-2 scope item 2).
 * Runs the full Flyway chain (validating the V23 snapshot tables and the one-ACTIVE-per-period
 * partial unique index) on a Testcontainers Postgres. Exercises: close-gated freeze, frozen
 * figures matching the live report, hash-based verify (consistent, then inconsistent after
 * post-freeze data movement), and the conflict/supersede flow.
 *
 * <p>Skipped cleanly when Docker is unavailable ({@code disabledWithoutDocker}).
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestSecurityConfig.class)
@Transactional
@DisplayName("Tax-liability period-close freeze (issue #998, real Postgres)")
class TaxLiabilitySnapshotIT {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        // Schema comes from the real Flyway chain (incl. V23), not Hibernate DDL.
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("spring.flyway.enabled", () -> "true");
    }

    private static final String PERIOD_CODE = "2026-06";
    private static final LocalDate START = LocalDate.of(2026, 6, 1);
    private static final LocalDate END = LocalDate.of(2026, 6, 30);

    @Autowired
    private TaxLiabilitySnapshotService snapshotService;

    @Autowired
    private AccountingPeriodRepository accountingPeriodRepository;

    @Autowired
    private TaxLiabilitySnapshotRepository snapshotRepository;

    @Autowired
    private ExtInvoiceRepository extInvoiceRepository;

    @Autowired
    private ExtInvoiceTaxRepository extInvoiceTaxRepository;

    @Test
    @DisplayName("Freeze on a CLOSED period persists the report; verify flips once data moves; supersede re-freezes")
    void freezeVerifySupersedeLifecycle() {
        UUID invoice = UUID.randomUUID();
        saveInvoice(invoice);
        saveTax(invoice, "STATE", "WA", "1000.00", "65.00");
        savePeriod(AccountingPeriodStatus.CLOSED);

        // --- Freeze ---
        TaxLiabilitySnapshotResponse frozen = snapshotService.freeze(PERIOD_CODE, false);
        snapshotRepository.flush();

        assertThat(frozen.getStatus()).isEqualTo("ACTIVE");
        assertThat(frozen.getPeriodCode()).isEqualTo(PERIOD_CODE);
        assertThat(frozen.getStartDate()).isEqualTo(START);
        assertThat(frozen.getEndDate()).isEqualTo(END);
        assertThat(frozen.getContentHash()).matches("[0-9a-f]{64}");
        assertThat(frozen.getTotalTaxCollectedGross()).isEqualByComparingTo("65.00");
        assertThat(frozen.getTotalNetTax()).isEqualByComparingTo("65.00");
        assertThat(frozen.getRows()).hasSize(1);
        assertThat(frozen.getRows().get(0).getJurisdictionCode()).isEqualTo("WA");
        assertThat(frozen.getPeriodClosedAt()).isNotNull();
        assertThat(frozen.getFrozenBy()).isNotNull();

        // Round-trip through the repository (JPA mapping against the real V23 schema).
        TaxLiabilitySnapshotResponse reloaded = snapshotService.get(frozen.getSnapshotId());
        assertThat(reloaded.getContentHash()).isEqualTo(frozen.getContentHash());
        assertThat(reloaded.getRows()).hasSize(1);

        // --- Verify: consistent right after the freeze ---
        TaxLiabilitySnapshotVerification ok = snapshotService.verify(frozen.getSnapshotId());
        assertThat(ok.getConsistent()).isTrue();
        assertThat(ok.getNetTaxDelta()).isEqualByComparingTo("0");

        // --- Conflict: second freeze without supersede is rejected ---
        assertThatThrownBy(() -> snapshotService.freeze(PERIOD_CODE, false))
                .isInstanceOf(TaxSnapshotConflictException.class);

        // --- Post-freeze data movement: verify detects it ---
        UUID lateInvoice = UUID.randomUUID();
        saveInvoice(lateInvoice);
        saveTax(lateInvoice, "STATE", "WA", "200.00", "13.00");

        TaxLiabilitySnapshotVerification drifted = snapshotService.verify(frozen.getSnapshotId());
        assertThat(drifted.getConsistent()).isFalse();
        assertThat(drifted.getNetTaxDelta()).isEqualByComparingTo("13.00");
        assertThat(drifted.getSnapshotContentHash()).isNotEqualTo(drifted.getRecomputedContentHash());

        // --- Supersede: re-freeze captures the moved data, demotes the prior snapshot ---
        TaxLiabilitySnapshotResponse refrozen = snapshotService.freeze(PERIOD_CODE, true);
        snapshotRepository.flush();

        assertThat(refrozen.getSupersedesSnapshotId()).isEqualTo(frozen.getSnapshotId());
        assertThat(refrozen.getTotalNetTax()).isEqualByComparingTo("78.00");
        TaxLiabilitySnapshotResponse prior = snapshotService.get(frozen.getSnapshotId());
        assertThat(prior.getStatus()).isEqualTo("SUPERSEDED");
        assertThat(prior.getSupersededAt()).isNotNull();
        assertThat(snapshotService.verify(refrozen.getSnapshotId()).getConsistent())
                .isTrue();
        assertThat(snapshotService.list(PERIOD_CODE)).hasSize(2);
    }

    @Test
    @DisplayName("Freeze on an OPEN period is rejected close-gated")
    void freezeRequiresClosedPeriod() {
        savePeriod(AccountingPeriodStatus.OPEN);

        assertThatThrownBy(() -> snapshotService.freeze(PERIOD_CODE, false))
                .isInstanceOf(TaxSnapshotPeriodNotClosedException.class);
    }

    /**
     * SQL-level guarantee behind the app-level conflict check: the partial unique index
     * {@code uq_tax_liability_snapshot_active} must reject a second ACTIVE row for the same
     * period. Runs non-transactionally (each repository call commits) so the index is actually
     * exercised — inside the class-level rolled-back transaction it never would be.
     */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("Partial unique index rejects a second ACTIVE snapshot per period at the SQL level")
    void activePartialUniqueIndexEnforcedInDatabase() {
        AccountingPeriod period = new AccountingPeriod();
        period.setPeriodCode("2026-05");
        period.setStartDate(LocalDate.of(2026, 5, 1));
        period.setEndDate(LocalDate.of(2026, 5, 31));
        period.setStatus(AccountingPeriodStatus.CLOSED);
        period.setClosedAt(Instant.parse("2026-06-01T05:00:00Z"));
        period.setClosedBy("it-controller");
        AccountingPeriod savedPeriod = accountingPeriodRepository.save(period);

        TaxLiabilitySnapshot first = minimalSnapshot(savedPeriod);
        TaxLiabilitySnapshot second = minimalSnapshot(savedPeriod);
        try {
            snapshotRepository.save(first);
            assertThatThrownBy(() -> snapshotRepository.save(second))
                    .isInstanceOf(DataIntegrityViolationException.class);
        } finally {
            snapshotRepository.deleteAll(snapshotRepository.findByPeriodCodeOrderByFrozenAtDesc("2026-05"));
            accountingPeriodRepository.delete(savedPeriod);
        }
    }

    private static TaxLiabilitySnapshot minimalSnapshot(AccountingPeriod period) {
        TaxLiabilitySnapshot snapshot = new TaxLiabilitySnapshot();
        snapshot.setPeriodId(period.getPeriodId());
        snapshot.setPeriodCode(period.getPeriodCode());
        snapshot.setStartDate(period.getStartDate());
        snapshot.setEndDate(period.getEndDate());
        snapshot.setStatus(TaxLiabilitySnapshotStatus.ACTIVE);
        snapshot.setPeriodClosedAt(period.getClosedAt());
        snapshot.setContentHash("0".repeat(64));
        snapshot.setTotalTaxableBase(BigDecimal.ZERO);
        snapshot.setTotalExemptBase(BigDecimal.ZERO);
        snapshot.setTotalTaxCollectedGross(BigDecimal.ZERO);
        snapshot.setTotalCreditsNetted(BigDecimal.ZERO);
        snapshot.setTotalNetTax(BigDecimal.ZERO);
        snapshot.setTaxPayableAccountCode("2200");
        snapshot.setGlNetActivity(BigDecimal.ZERO);
        snapshot.setUnattributedCredits(BigDecimal.ZERO);
        snapshot.setGlDrift(BigDecimal.ZERO);
        snapshot.setReconciled(true);
        return snapshot;
    }

    // ===== fixtures =====

    private void savePeriod(AccountingPeriodStatus status) {
        AccountingPeriod period = new AccountingPeriod();
        period.setPeriodCode(PERIOD_CODE);
        period.setStartDate(START);
        period.setEndDate(END);
        period.setStatus(status);
        if (status == AccountingPeriodStatus.CLOSED) {
            period.setClosedAt(Instant.parse("2026-07-01T05:00:00Z"));
            period.setClosedBy("it-controller");
        }
        accountingPeriodRepository.save(period);
    }

    private void saveInvoice(UUID invoiceId) {
        ExtInvoice invoice = ExtInvoice.builder()
                .invoiceId(invoiceId)
                .workorderId(UUID.randomUUID())
                .partyId(UUID.randomUUID().toString())
                .status("FINALIZED")
                .finalizedAt(Instant.parse("2026-06-15T00:00:00Z"))
                .invoiceCreatedAt(Instant.parse("2026-06-15T00:00:00Z"))
                .aggregateVersion(1L)
                .updatedAt(Instant.parse("2026-06-30T00:00:00Z"))
                .build();
        extInvoiceRepository.save(invoice);
    }

    private void saveTax(UUID invoiceId, String type, String code, String base, String tax) {
        ExtInvoiceTax row = ExtInvoiceTax.builder()
                .invoiceId(invoiceId)
                .lineItemId(code + "-line")
                .jurisdictionType(type)
                .jurisdictionCode(code)
                .rate(new BigDecimal("0.065000"))
                .taxableBase(new BigDecimal(base))
                .taxAmount(new BigDecimal(tax))
                .exempt(false)
                .exemptionReasonCode(null)
                .aggregateVersion(1L)
                .updatedAt(Instant.parse("2026-06-15T00:00:00Z"))
                .build();
        extInvoiceTaxRepository.save(row);
    }
}

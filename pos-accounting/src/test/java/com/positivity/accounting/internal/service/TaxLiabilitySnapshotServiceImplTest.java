package com.positivity.accounting.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.dto.TaxLiabilityReconciliation;
import com.positivity.accounting.internal.dto.TaxLiabilityReport;
import com.positivity.accounting.internal.dto.TaxLiabilityRow;
import com.positivity.accounting.internal.dto.TaxLiabilitySnapshotResponse;
import com.positivity.accounting.internal.dto.TaxLiabilitySnapshotVerification;
import com.positivity.accounting.internal.entity.AccountingPeriod;
import com.positivity.accounting.internal.entity.TaxLiabilitySnapshot;
import com.positivity.accounting.internal.enums.AccountingPeriodStatus;
import com.positivity.accounting.internal.enums.TaxLiabilitySnapshotStatus;
import com.positivity.accounting.internal.exception.AccountingPeriodNotFoundException;
import com.positivity.accounting.internal.exception.TaxSnapshotConflictException;
import com.positivity.accounting.internal.exception.TaxSnapshotNotFoundException;
import com.positivity.accounting.internal.exception.TaxSnapshotPeriodNotClosedException;
import com.positivity.accounting.internal.repository.AccountingPeriodRepository;
import com.positivity.accounting.internal.repository.TaxLiabilitySnapshotRepository;
import com.positivity.accounting.service.FinancialReportingService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Unit tests for the tax-liability freeze service (issue #998): close-gating, one-ACTIVE-per-period
 * conflict/supersede semantics, and hash-based verify.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TaxLiabilitySnapshotServiceImpl")
class TaxLiabilitySnapshotServiceImplTest {

    private static final String PERIOD_CODE = "2026-06";
    private static final LocalDate START = LocalDate.of(2026, 6, 1);
    private static final LocalDate END = LocalDate.of(2026, 6, 30);

    @Mock
    private AccountingPeriodRepository accountingPeriodRepository;

    @Mock
    private TaxLiabilitySnapshotRepository snapshotRepository;

    @Mock
    private FinancialReportingService financialReportingService;

    private TaxLiabilitySnapshotServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new TaxLiabilitySnapshotServiceImpl(
                accountingPeriodRepository,
                snapshotRepository,
                financialReportingService,
                Clock.fixed(Instant.parse("2026-07-22T12:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    @DisplayName("freeze: persists report figures, rows, and canonical hash for a CLOSED period")
    void freezeHappyPath() {
        AccountingPeriod period = closedPeriod();
        TaxLiabilityReport report = report("230.00");
        when(accountingPeriodRepository.findWithLockByPeriodCode(PERIOD_CODE)).thenReturn(Optional.of(period));
        when(snapshotRepository.findByPeriodIdAndStatus(period.getPeriodId(), TaxLiabilitySnapshotStatus.ACTIVE))
                .thenReturn(Optional.empty());
        when(financialReportingService.generateTaxLiability(START, END)).thenReturn(report);
        when(snapshotRepository.saveAndFlush(any(TaxLiabilitySnapshot.class)))
                .thenAnswer(inv -> persisted(inv.getArgument(0)));

        TaxLiabilitySnapshotResponse response = service.freeze(PERIOD_CODE, false);

        assertThat(response.getPeriodCode()).isEqualTo(PERIOD_CODE);
        assertThat(response.getStatus()).isEqualTo("ACTIVE");
        assertThat(response.getStartDate()).isEqualTo(START);
        assertThat(response.getEndDate()).isEqualTo(END);
        assertThat(response.getTotalNetTax()).isEqualByComparingTo("230.00");
        assertThat(response.getContentHash()).isEqualTo(TaxLiabilityCanonicalizer.contentHash(report));
        assertThat(response.getRows()).hasSize(1);
        assertThat(response.getRows().get(0).getExemptionReasons()).containsExactly("RESALE");
        assertThat(response.getReconciliation().getTaxPayableAccountCode()).isEqualTo("2200");
        assertThat(response.getSupersedesSnapshotId()).isNull();
    }

    @Test
    @DisplayName("freeze: unknown period -> AccountingPeriodNotFoundException")
    void freezeUnknownPeriod() {
        when(accountingPeriodRepository.findWithLockByPeriodCode(PERIOD_CODE)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.freeze(PERIOD_CODE, false))
                .isInstanceOf(AccountingPeriodNotFoundException.class);
        verify(snapshotRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("freeze: OPEN period -> TaxSnapshotPeriodNotClosedException, nothing persisted")
    void freezeOpenPeriodRejected() {
        AccountingPeriod period = closedPeriod();
        period.setStatus(AccountingPeriodStatus.OPEN);
        when(accountingPeriodRepository.findWithLockByPeriodCode(PERIOD_CODE)).thenReturn(Optional.of(period));

        assertThatThrownBy(() -> service.freeze(PERIOD_CODE, false))
                .isInstanceOf(TaxSnapshotPeriodNotClosedException.class)
                .hasMessageContaining("CLOSED");
        verify(financialReportingService, never()).generateTaxLiability(any(), any());
        verify(snapshotRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("freeze: ACTIVE snapshot exists and supersede=false -> conflict carrying the existing id")
    void freezeConflictWithoutSupersede() {
        AccountingPeriod period = closedPeriod();
        TaxLiabilitySnapshot existing = new TaxLiabilitySnapshot();
        UUID existingId = UUID.randomUUID();
        existing.setSnapshotId(existingId);
        when(accountingPeriodRepository.findWithLockByPeriodCode(PERIOD_CODE)).thenReturn(Optional.of(period));
        when(snapshotRepository.findByPeriodIdAndStatus(period.getPeriodId(), TaxLiabilitySnapshotStatus.ACTIVE))
                .thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.freeze(PERIOD_CODE, false))
                .isInstanceOf(TaxSnapshotConflictException.class)
                .satisfies(ex -> assertThat(((TaxSnapshotConflictException) ex).getExistingSnapshotId())
                        .isEqualTo(existingId));
        verify(snapshotRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("freeze: supersede=true demotes the prior ACTIVE snapshot and links the new one to it")
    void freezeWithSupersede() {
        AccountingPeriod period = closedPeriod();
        TaxLiabilitySnapshot existing = new TaxLiabilitySnapshot();
        UUID existingId = UUID.randomUUID();
        existing.setSnapshotId(existingId);
        existing.setStatus(TaxLiabilitySnapshotStatus.ACTIVE);
        TaxLiabilityReport report = report("230.00");
        when(accountingPeriodRepository.findWithLockByPeriodCode(PERIOD_CODE)).thenReturn(Optional.of(period));
        when(snapshotRepository.findByPeriodIdAndStatus(period.getPeriodId(), TaxLiabilitySnapshotStatus.ACTIVE))
                .thenReturn(Optional.of(existing));
        when(financialReportingService.generateTaxLiability(START, END)).thenReturn(report);
        when(snapshotRepository.saveAndFlush(any(TaxLiabilitySnapshot.class)))
                .thenAnswer(inv -> inv.getArgument(0) == existing ? existing : persisted(inv.getArgument(0)));

        TaxLiabilitySnapshotResponse response = service.freeze(PERIOD_CODE, true);

        assertThat(existing.getStatus()).isEqualTo(TaxLiabilitySnapshotStatus.SUPERSEDED);
        assertThat(existing.getSupersededAt()).isNotNull();
        assertThat(existing.getSupersededBy()).isNotNull();
        verify(snapshotRepository).saveAndFlush(existing);
        assertThat(response.getSupersedesSnapshotId()).isEqualTo(existingId);
        assertThat(response.getStatus()).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("freeze: lost insert race (partial unique index violation) -> 409 conflict, not 500")
    void freezeLostRaceMapsToConflict() {
        AccountingPeriod period = closedPeriod();
        TaxLiabilityReport report = report("230.00");
        when(accountingPeriodRepository.findWithLockByPeriodCode(PERIOD_CODE)).thenReturn(Optional.of(period));
        when(snapshotRepository.findByPeriodIdAndStatus(period.getPeriodId(), TaxLiabilitySnapshotStatus.ACTIVE))
                .thenReturn(Optional.empty());
        when(financialReportingService.generateTaxLiability(START, END)).thenReturn(report);
        when(snapshotRepository.saveAndFlush(any(TaxLiabilitySnapshot.class)))
                .thenThrow(new DataIntegrityViolationException("uq_tax_liability_snapshot_active"));

        assertThatThrownBy(() -> service.freeze(PERIOD_CODE, false))
                .isInstanceOf(TaxSnapshotConflictException.class)
                .hasMessageContaining("concurrently");
    }

    @Test
    @DisplayName("verify: unchanged data -> consistent with zero net-tax delta")
    void verifyConsistent() {
        TaxLiabilityReport report = report("230.00");
        TaxLiabilitySnapshot snapshot = frozenSnapshot(report);
        when(snapshotRepository.findById(snapshot.getSnapshotId())).thenReturn(Optional.of(snapshot));
        when(financialReportingService.generateTaxLiability(START, END)).thenReturn(report);

        TaxLiabilitySnapshotVerification verification = service.verify(snapshot.getSnapshotId());

        assertThat(verification.getConsistent()).isTrue();
        assertThat(verification.getSnapshotContentHash()).isEqualTo(verification.getRecomputedContentHash());
        assertThat(verification.getNetTaxDelta()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("verify: post-freeze data movement -> inconsistent with the net-tax delta surfaced")
    void verifyInconsistent() {
        TaxLiabilityReport frozen = report("230.00");
        TaxLiabilitySnapshot snapshot = frozenSnapshot(frozen);
        TaxLiabilityReport drifted = report("245.00");
        when(snapshotRepository.findById(snapshot.getSnapshotId())).thenReturn(Optional.of(snapshot));
        when(financialReportingService.generateTaxLiability(START, END)).thenReturn(drifted);

        TaxLiabilitySnapshotVerification verification = service.verify(snapshot.getSnapshotId());

        assertThat(verification.getConsistent()).isFalse();
        assertThat(verification.getSnapshotContentHash()).isNotEqualTo(verification.getRecomputedContentHash());
        assertThat(verification.getNetTaxDelta()).isEqualByComparingTo("15.00");
    }

    @Test
    @DisplayName("get: unknown id -> TaxSnapshotNotFoundException")
    void getUnknownSnapshot() {
        UUID id = UUID.randomUUID();
        when(snapshotRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(id)).isInstanceOf(TaxSnapshotNotFoundException.class);
    }

    // ===== fixtures =====

    /** Simulates what JPA persistence assigns (generated id, audit fields) on a real save. */
    private static TaxLiabilitySnapshot persisted(TaxLiabilitySnapshot snapshot) {
        snapshot.setSnapshotId(UUID.randomUUID());
        snapshot.setFrozenAt(Instant.parse("2026-07-01T06:00:00Z"));
        snapshot.onPrePersist();
        return snapshot;
    }

    private static AccountingPeriod closedPeriod() {
        AccountingPeriod period = new AccountingPeriod();
        period.setPeriodId(UUID.randomUUID());
        period.setPeriodCode(PERIOD_CODE);
        period.setStartDate(START);
        period.setEndDate(END);
        period.setStatus(AccountingPeriodStatus.CLOSED);
        period.setClosedAt(Instant.parse("2026-07-01T05:00:00Z"));
        period.setClosedBy("controller");
        return period;
    }

    private static TaxLiabilityReport report(String netTax) {
        BigDecimal amount = new BigDecimal(netTax);
        TaxLiabilityRow row = TaxLiabilityRow.builder()
                .jurisdictionType("STATE")
                .jurisdictionCode("WA")
                .taxableBase(new BigDecimal("3000.00"))
                .exemptBase(new BigDecimal("500.00"))
                .exemptionReasons(List.of("RESALE"))
                .taxCollectedGross(amount)
                .creditsNetted(BigDecimal.ZERO)
                .netTax(amount)
                .build();
        return TaxLiabilityReport.builder()
                .startDate(START)
                .endDate(END)
                .generatedAt(Instant.parse("2026-07-01T06:00:00Z"))
                .rows(List.of(row))
                .totalTaxableBase(new BigDecimal("3000.00"))
                .totalExemptBase(new BigDecimal("500.00"))
                .totalTaxCollectedGross(amount)
                .totalCreditsNetted(BigDecimal.ZERO)
                .totalNetTax(amount)
                .reconciliation(TaxLiabilityReconciliation.builder()
                        .taxPayableAccountCode("2200")
                        .glNetActivity(amount)
                        .reportNetTax(amount)
                        .drift(new BigDecimal("0.00"))
                        .reconciled(true)
                        .build())
                .build();
    }

    private static TaxLiabilitySnapshot frozenSnapshot(TaxLiabilityReport report) {
        TaxLiabilitySnapshot snapshot = new TaxLiabilitySnapshot();
        snapshot.setSnapshotId(UUID.randomUUID());
        snapshot.setPeriodId(UUID.randomUUID());
        snapshot.setPeriodCode(PERIOD_CODE);
        snapshot.setStartDate(START);
        snapshot.setEndDate(END);
        snapshot.setStatus(TaxLiabilitySnapshotStatus.ACTIVE);
        snapshot.setPeriodClosedAt(Instant.parse("2026-07-01T05:00:00Z"));
        snapshot.setContentHash(TaxLiabilityCanonicalizer.contentHash(report));
        snapshot.setTotalNetTax(report.getTotalNetTax());
        return snapshot;
    }
}

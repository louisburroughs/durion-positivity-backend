package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.dto.TaxLiabilityReconciliation;
import com.positivity.accounting.internal.dto.TaxLiabilityReport;
import com.positivity.accounting.internal.dto.TaxLiabilityRow;
import com.positivity.accounting.internal.dto.TaxLiabilitySnapshotResponse;
import com.positivity.accounting.internal.dto.TaxLiabilitySnapshotSummary;
import com.positivity.accounting.internal.dto.TaxLiabilitySnapshotVerification;
import com.positivity.accounting.internal.entity.AccountingPeriod;
import com.positivity.accounting.internal.entity.TaxLiabilitySnapshot;
import com.positivity.accounting.internal.entity.TaxLiabilitySnapshotRow;
import com.positivity.accounting.internal.enums.AccountingPeriodStatus;
import com.positivity.accounting.internal.enums.TaxLiabilitySnapshotStatus;
import com.positivity.accounting.internal.exception.AccountingPeriodNotFoundException;
import com.positivity.accounting.internal.exception.TaxSnapshotConflictException;
import com.positivity.accounting.internal.exception.TaxSnapshotNotFoundException;
import com.positivity.accounting.internal.exception.TaxSnapshotPeriodNotClosedException;
import com.positivity.accounting.internal.repository.AccountingPeriodRepository;
import com.positivity.accounting.internal.repository.TaxLiabilitySnapshotRepository;
import com.positivity.accounting.service.FinancialReportingService;
import com.positivity.accounting.service.TaxLiabilitySnapshotService;
import com.positivity.security.common.SecurityContextHelper;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implementation of the Sales-Tax Liability freeze (issue #998, Phase-2 scope item 2).
 *
 * <p>Freeze generates the T8 report over the CLOSED period's exact date range via
 * {@link FinancialReportingService} — the same code path as the on-demand report, so frozen and
 * ad-hoc figures can never diverge by construction — then persists it with a canonical SHA-256
 * content hash ({@link TaxLiabilityCanonicalizer}). Verify re-runs that generation against live
 * data and compares hashes. Snapshot content is never updated; supersede flips lifecycle metadata
 * only.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaxLiabilitySnapshotServiceImpl implements TaxLiabilitySnapshotService {

    private static final String SYSTEM = "SYSTEM";

    private final AccountingPeriodRepository accountingPeriodRepository;
    private final TaxLiabilitySnapshotRepository snapshotRepository;
    private final FinancialReportingService financialReportingService;
    private final Clock clock;

    @Override
    @Transactional
    public @NonNull TaxLiabilitySnapshotResponse freeze(@NonNull String periodCode, boolean supersede) {
        // Locked read (SELECT ... FOR UPDATE, same pattern as AccountingPeriodGate): a concurrent
        // reopenPeriod updates this row and therefore serializes against the freeze, so the
        // CLOSED status checked here still holds when the snapshot commits.
        AccountingPeriod period = accountingPeriodRepository
                .findWithLockByPeriodCode(periodCode)
                .orElseThrow(() -> new AccountingPeriodNotFoundException(
                        periodCode, "Accounting period " + periodCode + " does not exist"));

        if (period.getStatus() != AccountingPeriodStatus.CLOSED) {
            throw new TaxSnapshotPeriodNotClosedException(
                    periodCode,
                    "Accounting period " + periodCode + " must be CLOSED before its tax liability can be frozen"
                            + " (current status: " + period.getStatus() + ")");
        }

        UUID supersededId = null;
        var existingActive =
                snapshotRepository.findByPeriodIdAndStatus(period.getPeriodId(), TaxLiabilitySnapshotStatus.ACTIVE);
        if (existingActive.isPresent()) {
            TaxLiabilitySnapshot prior = existingActive.get();
            if (!supersede) {
                throw new TaxSnapshotConflictException(
                        prior.getSnapshotId(),
                        "Period " + periodCode + " already has an ACTIVE tax liability snapshot "
                                + prior.getSnapshotId() + "; re-freeze with supersede=true to replace it");
            }
            prior.setStatus(TaxLiabilitySnapshotStatus.SUPERSEDED);
            prior.setSupersededAt(Instant.now(clock));
            prior.setSupersededBy(resolveActor());
            // Flush before inserting the new ACTIVE row so the one-ACTIVE-per-period partial
            // unique index sees the demotion first.
            snapshotRepository.saveAndFlush(prior);
            supersededId = prior.getSnapshotId();
            log.info("Superseding tax liability snapshot {} for period {}", supersededId, periodCode);
        }

        TaxLiabilityReport report =
                financialReportingService.generateTaxLiability(period.getStartDate(), period.getEndDate());
        String contentHash = TaxLiabilityCanonicalizer.contentHash(report);

        TaxLiabilitySnapshot snapshot = new TaxLiabilitySnapshot();
        snapshot.setPeriodId(period.getPeriodId());
        snapshot.setPeriodCode(period.getPeriodCode());
        snapshot.setStartDate(period.getStartDate());
        snapshot.setEndDate(period.getEndDate());
        snapshot.setStatus(TaxLiabilitySnapshotStatus.ACTIVE);
        snapshot.setPeriodClosedAt(period.getClosedAt());
        snapshot.setContentHash(contentHash);
        snapshot.setTotalTaxableBase(report.getTotalTaxableBase());
        snapshot.setTotalExemptBase(report.getTotalExemptBase());
        snapshot.setTotalTaxCollectedGross(report.getTotalTaxCollectedGross());
        snapshot.setTotalCreditsNetted(report.getTotalCreditsNetted());
        snapshot.setTotalNetTax(report.getTotalNetTax());
        snapshot.setTaxPayableAccountCode(report.getReconciliation().getTaxPayableAccountCode());
        snapshot.setGlNetActivity(report.getReconciliation().getGlNetActivity());
        snapshot.setUnattributedCredits(report.getReconciliation().getUnattributedCredits());
        snapshot.setGlDrift(report.getReconciliation().getDrift());
        snapshot.setReconciled(Boolean.TRUE.equals(report.getReconciliation().getReconciled()));
        snapshot.setSupersedesSnapshotId(supersededId);

        int order = 0;
        for (TaxLiabilityRow reportRow : report.getRows()) {
            TaxLiabilitySnapshotRow row = new TaxLiabilitySnapshotRow();
            row.setRowOrder(order++);
            row.setJurisdictionType(reportRow.getJurisdictionType());
            row.setJurisdictionCode(reportRow.getJurisdictionCode());
            row.setJurisdictionName(reportRow.getJurisdictionName());
            row.setTaxableBase(reportRow.getTaxableBase());
            row.setExemptBase(reportRow.getExemptBase());
            row.setExemptionReasons(TaxLiabilityCanonicalizer.joinReasons(reportRow.getExemptionReasons()));
            row.setTaxCollectedGross(reportRow.getTaxCollectedGross());
            row.setCreditsNetted(reportRow.getCreditsNetted());
            row.setNetTax(reportRow.getNetTax());
            snapshot.addRow(row);
        }

        TaxLiabilitySnapshot saved;
        try {
            // Flush in-method so a concurrent freeze losing the one-ACTIVE-per-period partial
            // unique index race surfaces here as a 409 conflict, not a 500 at commit.
            saved = snapshotRepository.saveAndFlush(snapshot);
        } catch (DataIntegrityViolationException e) {
            throw new TaxSnapshotConflictException(
                    null,
                    "Period " + periodCode + " was frozen concurrently by another request;"
                            + " re-freeze with supersede=true to replace the winning snapshot",
                    e);
        }
        log.info(
                "Froze tax liability snapshot {} for period {} ({} rows, netTax={}, hash={})",
                saved.getSnapshotId(),
                periodCode,
                saved.getRows().size(),
                saved.getTotalNetTax(),
                contentHash);
        return toResponse(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public @NonNull TaxLiabilitySnapshotResponse get(@NonNull UUID snapshotId) {
        return toResponse(load(snapshotId));
    }

    @Override
    @Transactional(readOnly = true)
    public @NonNull List<TaxLiabilitySnapshotSummary> list(@Nullable String periodCode) {
        List<TaxLiabilitySnapshot> snapshots = periodCode == null
                ? snapshotRepository.findAllByOrderByFrozenAtDesc()
                : snapshotRepository.findByPeriodCodeOrderByFrozenAtDesc(periodCode);
        return snapshots.stream()
                .map(TaxLiabilitySnapshotServiceImpl::toSummary)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public @NonNull TaxLiabilitySnapshotVerification verify(@NonNull UUID snapshotId) {
        TaxLiabilitySnapshot snapshot = load(snapshotId);

        TaxLiabilityReport recomputed =
                financialReportingService.generateTaxLiability(snapshot.getStartDate(), snapshot.getEndDate());
        String recomputedHash = TaxLiabilityCanonicalizer.contentHash(recomputed);
        boolean consistent = recomputedHash.equals(snapshot.getContentHash());

        if (!consistent) {
            log.warn(
                    "Tax liability snapshot {} (period {}) no longer matches live data: frozen hash {}, recomputed {}",
                    snapshotId,
                    snapshot.getPeriodCode(),
                    snapshot.getContentHash(),
                    recomputedHash);
        }

        return TaxLiabilitySnapshotVerification.builder()
                .snapshotId(snapshot.getSnapshotId())
                .periodCode(snapshot.getPeriodCode())
                .verifiedAt(Instant.now(clock))
                .snapshotContentHash(snapshot.getContentHash())
                .recomputedContentHash(recomputedHash)
                .consistent(consistent)
                .frozenTotalNetTax(snapshot.getTotalNetTax())
                .recomputedTotalNetTax(recomputed.getTotalNetTax())
                .netTaxDelta(recomputed.getTotalNetTax().subtract(snapshot.getTotalNetTax()))
                .build();
    }

    @NonNull
    private TaxLiabilitySnapshot load(@NonNull UUID snapshotId) {
        return snapshotRepository.findById(snapshotId).orElseThrow(() -> new TaxSnapshotNotFoundException(snapshotId));
    }

    private static TaxLiabilitySnapshotResponse toResponse(TaxLiabilitySnapshot snapshot) {
        List<TaxLiabilityRow> rows = snapshot.getRows().stream()
                .map(TaxLiabilitySnapshotServiceImpl::toReportRow)
                .toList();
        return TaxLiabilitySnapshotResponse.builder()
                .snapshotId(snapshot.getSnapshotId())
                .periodId(snapshot.getPeriodId())
                .periodCode(snapshot.getPeriodCode())
                .startDate(snapshot.getStartDate())
                .endDate(snapshot.getEndDate())
                .status(snapshot.getStatus().name())
                .periodClosedAt(snapshot.getPeriodClosedAt())
                .frozenAt(snapshot.getFrozenAt())
                .frozenBy(snapshot.getFrozenBy())
                .contentHash(snapshot.getContentHash())
                .rows(rows)
                .totalTaxableBase(snapshot.getTotalTaxableBase())
                .totalExemptBase(snapshot.getTotalExemptBase())
                .totalTaxCollectedGross(snapshot.getTotalTaxCollectedGross())
                .totalCreditsNetted(snapshot.getTotalCreditsNetted())
                .totalNetTax(snapshot.getTotalNetTax())
                .reconciliation(TaxLiabilityReconciliation.builder()
                        .taxPayableAccountCode(snapshot.getTaxPayableAccountCode())
                        .glNetActivity(snapshot.getGlNetActivity())
                        .reportNetTax(snapshot.getTotalNetTax())
                        .unattributedCredits(snapshot.getUnattributedCredits())
                        .drift(snapshot.getGlDrift())
                        .reconciled(snapshot.isReconciled())
                        .build())
                .supersedesSnapshotId(snapshot.getSupersedesSnapshotId())
                .supersededAt(snapshot.getSupersededAt())
                .supersededBy(snapshot.getSupersededBy())
                .build();
    }

    private static TaxLiabilityRow toReportRow(TaxLiabilitySnapshotRow row) {
        return TaxLiabilityRow.builder()
                .jurisdictionType(row.getJurisdictionType())
                .jurisdictionCode(row.getJurisdictionCode())
                .jurisdictionName(row.getJurisdictionName())
                .taxableBase(row.getTaxableBase())
                .exemptBase(row.getExemptBase())
                .exemptionReasons(TaxLiabilityCanonicalizer.splitReasons(row.getExemptionReasons()))
                .taxCollectedGross(row.getTaxCollectedGross())
                .creditsNetted(row.getCreditsNetted())
                .netTax(row.getNetTax())
                .build();
    }

    private static TaxLiabilitySnapshotSummary toSummary(TaxLiabilitySnapshot snapshot) {
        return TaxLiabilitySnapshotSummary.builder()
                .snapshotId(snapshot.getSnapshotId())
                .periodCode(snapshot.getPeriodCode())
                .status(snapshot.getStatus().name())
                .frozenAt(snapshot.getFrozenAt())
                .frozenBy(snapshot.getFrozenBy())
                .totalNetTax(snapshot.getTotalNetTax())
                .reconciled(snapshot.isReconciled())
                .contentHash(snapshot.getContentHash())
                .build();
    }

    private static String resolveActor() {
        return SecurityContextHelper.isAuthenticated()
                ? SecurityContextHelper.getCurrentUsernameOrDefault(SYSTEM)
                : SYSTEM;
    }
}

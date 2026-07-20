package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.dto.BankReconciliationAdjustmentResponse;
import com.positivity.accounting.internal.dto.BankReconciliationImportRequest;
import com.positivity.accounting.internal.dto.BankReconciliationLineResponse;
import com.positivity.accounting.internal.dto.BankReconciliationListResponse;
import com.positivity.accounting.internal.dto.BankReconciliationResponse;
import com.positivity.accounting.internal.dto.ReconciliationAdjustmentRequest;
import com.positivity.accounting.internal.dto.ReconciliationAuditResponse;
import com.positivity.accounting.internal.dto.ReconciliationMatchRequest;
import com.positivity.accounting.internal.dto.ReconciliationReportResponse;
import com.positivity.accounting.internal.dto.ReconciliationUnmatchRequest;
import com.positivity.accounting.internal.entity.BankReconciliation;
import com.positivity.accounting.internal.entity.BankReconciliationAdjustment;
import com.positivity.accounting.internal.entity.BankReconciliationGlMatch;
import com.positivity.accounting.internal.entity.BankReconciliationLine;
import com.positivity.accounting.internal.entity.GLAccount;
import com.positivity.accounting.internal.entity.JournalEntry;
import com.positivity.accounting.internal.entity.JournalEntryLine;
import com.positivity.accounting.internal.enums.BankAdjustmentType;
import com.positivity.accounting.internal.enums.BankReconciliationLineStatus;
import com.positivity.accounting.internal.enums.JournalEntryStatus;
import com.positivity.accounting.internal.enums.ReconciliationStatus;
import com.positivity.accounting.internal.exception.AccountNotReconcilableException;
import com.positivity.accounting.internal.exception.AdjustmentSignInvalidException;
import com.positivity.accounting.internal.exception.MatchAmountMismatchException;
import com.positivity.accounting.internal.exception.ReconciliationAlreadyFinalizedException;
import com.positivity.accounting.internal.exception.ReconciliationLineIneligibleException;
import com.positivity.accounting.internal.exception.ReconciliationNotBalancedException;
import com.positivity.accounting.internal.exception.ReconciliationNotFoundException;
import com.positivity.accounting.internal.repository.BankReconciliationAdjustmentRepository;
import com.positivity.accounting.internal.repository.BankReconciliationGlMatchRepository;
import com.positivity.accounting.internal.repository.BankReconciliationLineRepository;
import com.positivity.accounting.internal.repository.BankReconciliationRepository;
import com.positivity.accounting.internal.repository.GLAccountRepository;
import com.positivity.accounting.internal.repository.JournalEntryLineRepository;
import com.positivity.accounting.internal.service.BankStatementCsvParser.ParsedLine;
import com.positivity.accounting.service.BankReconciliationService;
import com.positivity.accounting.service.GLMappingResolver;
import com.positivity.accounting.service.JournalEntryService;
import com.positivity.security.common.SecurityContextHelper;
import com.positivity.shared.id.UUIDv7Generator;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Manual CSV bank reconciliation (Story F2, issue #965, decisions D-5/D-6).
 *
 * <p>Repository access lives here in the service layer (ADR-0011). Adjustments post
 * real balanced journal entries through {@link JournalEntryService#postJournalEntry}
 * with a null override justification, so a locked accounting period (story B2) yields
 * 422 exactly like the settlement write-off path.
 *
 * <p>Balance gate (finalize): {@code statementEndingBalance} must equal
 * {@code glEndingBalance + Σ adjustments} within ±0.01, where {@code glEndingBalance}
 * is snapshotted at import from posted GL lines as-of the statement date (so it
 * already reflects matched GL lines — matching is documentation, not arithmetic; see
 * {@link #computeDifference}) and {@code Σ adjustments} sums the signed amounts of the
 * adjustments, whose real JEs post after the frozen snapshot.
 */
@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class BankReconciliationServiceImpl implements BankReconciliationService {

    static final String POSTING_CATEGORY = "BANK_RECONCILIATION";
    private static final BigDecimal TOLERANCE = new BigDecimal("0.01");
    private static final String SYSTEM = "SYSTEM";

    private final Clock clock;
    private final BankReconciliationRepository reconciliationRepository;
    private final BankReconciliationLineRepository lineRepository;
    private final BankReconciliationAdjustmentRepository adjustmentRepository;
    private final BankReconciliationGlMatchRepository glMatchRepository;
    private final GLAccountRepository glAccountRepository;
    private final JournalEntryLineRepository journalEntryLineRepository;
    private final GLMappingResolver glMappingResolver;
    private final JournalEntryService journalEntryService;

    @Override
    public BankReconciliationResponse importStatement(@NonNull BankReconciliationImportRequest request) {
        GLAccount account = glAccountRepository
                .findById(request.getGlAccountId())
                .orElseThrow(() -> new IllegalArgumentException("GL account not found: " + request.getGlAccountId()));
        if (!account.isReconcilable()) {
            throw new AccountNotReconcilableException(
                    "GL account " + account.getAccountCode() + " is not reconcilable");
        }

        List<ParsedLine> parsed = BankStatementCsvParser.parse(request.getCsv());

        BigDecimal glEndingBalance = journalEntryLineRepository.getAccountBalanceAsOf(
                request.getGlAccountId(), request.getStatementDate().atTime(LocalTime.MAX));
        if (glEndingBalance == null) {
            glEndingBalance = BigDecimal.ZERO;
        }

        BankReconciliation recon = new BankReconciliation();
        recon.setGlAccountId(request.getGlAccountId());
        recon.setAccountCode(account.getAccountCode());
        recon.setAccountName(account.getAccountName());
        recon.setPeriodStartDate(request.getPeriodStartDate());
        recon.setPeriodEndDate(request.getPeriodEndDate());
        recon.setStatementDate(request.getStatementDate());
        recon.setCurrency(request.getCurrency().toUpperCase());
        recon.setStatementEndingBalance(request.getStatementEndingBalance());
        recon.setGlEndingBalance(glEndingBalance);
        recon.setStatus(ReconciliationStatus.IN_PROGRESS);

        int lineNumber = 1;
        for (ParsedLine p : parsed) {
            BankReconciliationLine line = new BankReconciliationLine();
            line.setLineNumber(lineNumber++);
            line.setLineDate(p.date());
            line.setDescription(p.description());
            line.setAmount(p.amount());
            line.setReference(p.reference());
            line.setStatus(BankReconciliationLineStatus.UNMATCHED);
            recon.addStatementLine(line);
        }

        // difference at import: no matches, no adjustments yet.
        recon.setDifference(request.getStatementEndingBalance().subtract(glEndingBalance));

        BankReconciliation saved = reconciliationRepository.save(recon);
        log.info(
                "Imported bank reconciliation {} for account {} ({} lines, glEndingBalance={})",
                saved.getReconciliationId(),
                account.getAccountCode(),
                parsed.size(),
                glEndingBalance);
        return toResponse(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public BankReconciliationResponse get(@NonNull UUID reconciliationId) {
        return toResponse(requireReconciliation(reconciliationId));
    }

    @Override
    @Transactional(readOnly = true)
    public BankReconciliationListResponse list(
            @Nullable UUID glAccountId, @Nullable ReconciliationStatus status, @NonNull Pageable pageable) {
        Page<BankReconciliation> page;
        if (glAccountId != null && status != null) {
            page = reconciliationRepository.findByGlAccount_GlAccountIdAndStatus(glAccountId, status, pageable);
        } else if (glAccountId != null) {
            page = reconciliationRepository.findByGlAccount_GlAccountId(glAccountId, pageable);
        } else if (status != null) {
            page = reconciliationRepository.findByStatus(status, pageable);
        } else {
            page = reconciliationRepository.findAll(pageable);
        }
        List<BankReconciliationResponse> items =
                page.getContent().stream().map(this::toResponse).toList();
        BankReconciliationListResponse response = new BankReconciliationListResponse();
        response.setReconciliations(items);
        response.setTotalElements(page.getTotalElements());
        response.setPageNumber(page.getNumber());
        response.setPageSize(page.getSize());
        response.setTotalPages(page.getTotalPages());
        return response;
    }

    @Override
    public BankReconciliationResponse match(
            @NonNull UUID reconciliationId, @NonNull ReconciliationMatchRequest request) {
        BankReconciliation recon = requireOpenReconciliation(reconciliationId);

        List<BankReconciliationLine> statementLines = lineRepository.findAllById(request.getStatementLineIds());
        if (statementLines.size() != new HashSet<>(request.getStatementLineIds()).size()) {
            throw new ReconciliationNotFoundException("One or more statement lines were not found");
        }
        for (BankReconciliationLine line : statementLines) {
            if (!reconciliationId.equals(line.getReconciliationId())) {
                throw new ReconciliationNotFoundException("Statement line " + line.getLineId()
                        + " does not belong to reconciliation " + reconciliationId);
            }
            if (line.getStatus() != BankReconciliationLineStatus.UNMATCHED) {
                throw new ReconciliationLineIneligibleException(
                        "Statement line " + line.getLineId() + " is not UNMATCHED");
            }
        }

        List<JournalEntryLine> glLines = journalEntryLineRepository.findAllById(request.getGlLineIds());
        if (glLines.size() != new HashSet<>(request.getGlLineIds()).size()) {
            throw new ReconciliationNotFoundException("One or more GL journal-entry lines were not found");
        }
        for (JournalEntryLine glLine : glLines) {
            if (!recon.getGlAccountId().equals(glLine.getGlAccountId())) {
                throw new IllegalArgumentException("GL line " + glLine.getLineId()
                        + " does not post to the reconciled account " + recon.getGlAccountId());
            }
            if (glLine.getJournalEntry() == null || glLine.getJournalEntry().getStatus() != JournalEntryStatus.POSTED) {
                throw new ReconciliationLineIneligibleException(
                        "GL line " + glLine.getLineId() + " is not on a POSTED entry");
            }
            // Global dedup: a posted GL line represents one cash movement and may be reconciled in at
            // most one reconciliation (across all reconciliations, not just this one). The DB unique
            // index on bank_reconciliation_gl_match(gl_line_id) is the backstop.
            if (glMatchRepository.existsByGlLineId(glLine.getLineId())) {
                throw new ReconciliationLineIneligibleException(
                        "GL line " + glLine.getLineId() + " is already matched in a reconciliation");
            }
        }

        BigDecimal statementSum =
                statementLines.stream().map(BankReconciliationLine::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal glSum =
                glLines.stream().map(BankReconciliationServiceImpl::signedGl).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (statementSum.subtract(glSum).abs().compareTo(TOLERANCE) > 0) {
            throw new MatchAmountMismatchException("Statement lines net " + statementSum + " but GL lines net " + glSum
                    + " (must agree within ±0.01)");
        }

        UUID matchId = UUIDv7Generator.generate();
        for (BankReconciliationLine line : statementLines) {
            line.setStatus(BankReconciliationLineStatus.MATCHED);
            line.setMatchId(matchId);
        }
        lineRepository.saveAll(statementLines);

        List<BankReconciliationGlMatch> matches = new ArrayList<>();
        for (JournalEntryLine glLine : glLines) {
            BankReconciliationGlMatch m = new BankReconciliationGlMatch();
            m.setReconciliationId(reconciliationId);
            m.setMatchId(matchId);
            m.setGlLineId(glLine.getLineId());
            m.setSignedAmount(signedGl(glLine));
            matches.add(m);
        }
        glMatchRepository.saveAll(matches);

        recomputeDifference(recon);
        reconciliationRepository.save(recon);
        log.info(
                "Matched {} statement line(s) to {} GL line(s) in reconciliation {} (matchId={})",
                statementLines.size(),
                glLines.size(),
                reconciliationId,
                matchId);
        return toResponse(recon);
    }

    @Override
    public BankReconciliationResponse unmatch(
            @NonNull UUID reconciliationId, @NonNull ReconciliationUnmatchRequest request) {
        BankReconciliation recon = requireOpenReconciliation(reconciliationId);

        UUID matchId = request.getMatchId();
        if (matchId == null) {
            if (request.getStatementLineIds() == null
                    || request.getStatementLineIds().isEmpty()) {
                throw new IllegalArgumentException("Provide either matchId or statementLineIds to unmatch");
            }
            Set<UUID> matchIds = new HashSet<>();
            for (BankReconciliationLine line : lineRepository.findAllById(request.getStatementLineIds())) {
                if (line.getMatchId() != null) {
                    matchIds.add(line.getMatchId());
                }
            }
            if (matchIds.size() != 1) {
                throw new IllegalArgumentException(
                        "statementLineIds must resolve to exactly one match group; found " + matchIds.size());
            }
            matchId = matchIds.iterator().next();
        }

        List<BankReconciliationLine> lines =
                lineRepository.findByReconciliation_ReconciliationIdAndMatchId(reconciliationId, matchId);
        if (lines.isEmpty()) {
            throw new ReconciliationNotFoundException(
                    "No matched statement lines for match " + matchId + " in reconciliation " + reconciliationId);
        }
        for (BankReconciliationLine line : lines) {
            line.setStatus(BankReconciliationLineStatus.UNMATCHED);
            line.setMatchId(null);
        }
        lineRepository.saveAll(lines);
        glMatchRepository.deleteByReconciliationIdAndMatchId(reconciliationId, matchId);

        recomputeDifference(recon);
        reconciliationRepository.save(recon);
        log.info("Unmatched match {} in reconciliation {} ({} line(s))", matchId, reconciliationId, lines.size());
        return toResponse(recon);
    }

    @Override
    public BankReconciliationResponse addAdjustment(
            @NonNull UUID reconciliationId, @NonNull ReconciliationAdjustmentRequest request) {
        BankReconciliation recon = requireOpenReconciliation(reconciliationId);
        BigDecimal amount = request.getAmount();
        if (amount.signum() == 0) {
            throw new IllegalArgumentException("Adjustment amount must be non-zero");
        }
        BankAdjustmentType type = request.getType();
        if (!type.permits(amount.signum())) {
            throw new AdjustmentSignInvalidException(type);
        }

        LocalDateTime txDate = recon.getStatementDate().atStartOfDay();
        UUID cashAccountId = recon.getGlAccountId();
        UUID counterAccountId = glMappingResolver.resolveGLAccount(POSTING_CATEGORY, type.name(), txDate);

        JournalEntry entry = new JournalEntry();
        entry.setTransactionDate(txDate);
        entry.setSourceEventId(UUIDv7Generator.generate());
        String desc = "Bank reconciliation adjustment (" + type + ")"
                + (request.getDescription() != null && !request.getDescription().isBlank()
                        ? ": " + request.getDescription()
                        : "");
        entry.setDescription(desc);

        BigDecimal abs = amount.abs();
        List<JournalEntryLine> lines = new ArrayList<>();
        // Positive amount increases the reconciled cash (Dr cash / Cr counter); negative decreases it
        // (Cr cash / Dr counter). The cash account is the reconciled account; the counter account is
        // the type's mapped expense/income/clearing account.
        if (amount.signum() > 0) {
            lines.add(debit(cashAccountId, abs, desc));
            lines.add(credit(counterAccountId, abs, desc));
        } else {
            lines.add(debit(counterAccountId, abs, desc));
            lines.add(credit(cashAccountId, abs, desc));
        }
        entry.setLines(lines);

        JournalEntry created = journalEntryService.createJournalEntry(entry);
        JournalEntry posted = journalEntryService.postJournalEntry(created.getJournalEntryId(), null);

        BankReconciliationAdjustment adjustment = new BankReconciliationAdjustment();
        adjustment.setReconciliation(recon);
        adjustment.setAdjustmentType(type);
        adjustment.setAmount(amount);
        adjustment.setDescription(request.getDescription());
        adjustment.setJournalEntryId(posted.getJournalEntryId());
        adjustmentRepository.save(adjustment);

        recomputeDifference(recon);
        reconciliationRepository.save(recon);
        log.info(
                "Recorded {} adjustment {} on reconciliation {} (JE {})",
                type,
                amount,
                reconciliationId,
                posted.getJournalEntryId());
        return toResponse(recon);
    }

    @Override
    public BankReconciliationResponse finalizeReconciliation(@NonNull UUID reconciliationId) {
        BankReconciliation recon = requireReconciliation(reconciliationId);
        if (recon.getStatus() == ReconciliationStatus.FINALIZED) {
            throw new ReconciliationAlreadyFinalizedException(
                    "Reconciliation " + reconciliationId + " is already FINALIZED");
        }
        BigDecimal difference = computeDifference(recon);
        if (difference.abs().compareTo(TOLERANCE) > 0) {
            throw new ReconciliationNotBalancedException(
                    "Reconciliation " + reconciliationId + " does not balance; difference " + difference, difference);
        }
        recon.setDifference(difference);
        recon.setStatus(ReconciliationStatus.FINALIZED);
        recon.setFinalizedAt(Instant.now(clock));
        recon.setFinalizedBy(currentUser());
        reconciliationRepository.save(recon);
        log.info("Finalized reconciliation {} (difference={})", reconciliationId, difference);
        return toResponse(recon);
    }

    @Override
    @Transactional(readOnly = true)
    public ReconciliationReportResponse report(@NonNull UUID reconciliationId) {
        BankReconciliation recon = requireReconciliation(reconciliationId);
        List<BankReconciliationLine> lines = lineRepository.findByReconciliation_ReconciliationId(reconciliationId);
        List<BankReconciliationAdjustment> adjustments =
                adjustmentRepository.findByReconciliation_ReconciliationId(reconciliationId);

        BigDecimal totalMatched = sumMatched(lines);
        BigDecimal totalAdjustments = sumAdjustments(adjustments);
        BigDecimal totalOutstanding = lines.stream()
                .filter(l -> l.getStatus() == BankReconciliationLineStatus.UNMATCHED)
                .map(BankReconciliationLine::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        int matchedCount = (int) lines.stream()
                .filter(l -> l.getStatus() == BankReconciliationLineStatus.MATCHED)
                .count();
        int outstandingCount = lines.size() - matchedCount;
        BigDecimal difference = recon.getStatementEndingBalance()
                .subtract(recon.getGlEndingBalance().add(totalAdjustments));

        return ReconciliationReportResponse.builder()
                .reconciliationId(reconciliationId)
                .accountCode(recon.getAccountCode())
                .accountName(recon.getAccountName())
                .currency(recon.getCurrency())
                .statementDate(recon.getStatementDate())
                .glEndingBalance(recon.getGlEndingBalance())
                .statementEndingBalance(recon.getStatementEndingBalance())
                .totalMatched(totalMatched)
                .totalAdjustments(totalAdjustments)
                .totalOutstanding(totalOutstanding)
                .matchedLineCount(matchedCount)
                .outstandingLineCount(outstandingCount)
                .difference(difference)
                .adjustments(adjustments.stream()
                        .map(BankReconciliationAdjustmentResponse::from)
                        .toList())
                .outstandingLines(lines.stream()
                        .filter(l -> l.getStatus() == BankReconciliationLineStatus.UNMATCHED)
                        .map(BankReconciliationLineResponse::from)
                        .toList())
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public ReconciliationAuditResponse audit(@NonNull UUID reconciliationId) {
        BankReconciliation recon = requireReconciliation(reconciliationId);
        List<BankReconciliationAdjustment> adjustments =
                adjustmentRepository.findByReconciliation_ReconciliationId(reconciliationId);
        List<BankReconciliationGlMatch> matches = glMatchRepository.findByReconciliationId(reconciliationId);

        List<ReconciliationAuditResponse.Entry> entries = new ArrayList<>();
        entries.add(ReconciliationAuditResponse.Entry.builder()
                .action("IMPORT")
                .at(recon.getCreatedAt())
                .by(recon.getCreatedBy())
                .detail("Imported statement for account " + recon.getAccountCode())
                .build());
        // One MATCH entry per distinct match group (earliest gl-match row time).
        matches.stream()
                .collect(java.util.stream.Collectors.groupingBy(BankReconciliationGlMatch::getMatchId))
                .forEach((matchId, group) -> {
                    Instant at = group.stream()
                            .map(BankReconciliationGlMatch::getCreatedAt)
                            .filter(java.util.Objects::nonNull)
                            .min(Comparator.naturalOrder())
                            .orElse(null);
                    entries.add(ReconciliationAuditResponse.Entry.builder()
                            .action("MATCH")
                            .at(at)
                            .by(null)
                            .detail("Match " + matchId + " (" + group.size() + " GL line(s))")
                            .build());
                });
        for (BankReconciliationAdjustment a : adjustments) {
            entries.add(ReconciliationAuditResponse.Entry.builder()
                    .action("ADJUSTMENT")
                    .at(a.getCreatedAt())
                    .by(a.getCreatedBy())
                    .detail(a.getAdjustmentType() + " " + a.getAmount() + " (JE " + a.getJournalEntryId() + ")")
                    .build());
        }
        if (recon.getStatus() == ReconciliationStatus.FINALIZED) {
            entries.add(ReconciliationAuditResponse.Entry.builder()
                    .action("FINALIZE")
                    .at(recon.getFinalizedAt())
                    .by(recon.getFinalizedBy())
                    .detail("Finalized with difference " + recon.getDifference())
                    .build());
        }
        entries.sort(Comparator.comparing(
                ReconciliationAuditResponse.Entry::getAt, Comparator.nullsLast(Comparator.naturalOrder())));

        return ReconciliationAuditResponse.builder()
                .reconciliationId(reconciliationId)
                .entries(entries)
                .build();
    }

    // ---- helpers -----------------------------------------------------------

    private BankReconciliation requireReconciliation(@NonNull UUID reconciliationId) {
        return reconciliationRepository
                .findById(reconciliationId)
                .orElseThrow(
                        () -> new ReconciliationNotFoundException("Reconciliation not found: " + reconciliationId));
    }

    private BankReconciliation requireOpenReconciliation(@NonNull UUID reconciliationId) {
        BankReconciliation recon = requireReconciliation(reconciliationId);
        if (recon.getStatus() == ReconciliationStatus.FINALIZED) {
            throw new ReconciliationAlreadyFinalizedException(
                    "Reconciliation " + reconciliationId + " is already FINALIZED");
        }
        return recon;
    }

    /** Recompute and store the reconciliation difference from its current lines and adjustments. */
    private void recomputeDifference(@NonNull BankReconciliation recon) {
        recon.setDifference(computeDifference(recon));
    }

    /**
     * Reconciliation difference = statementEndingBalance − (glEndingBalance + Σ adjustments).
     *
     * <p>{@code glEndingBalance} is the GL balance snapshotted at import as-of the statement date, so it
     * ALREADY reflects every posted GL line that a match links to (a matched line is POSTED on the
     * reconciled account and dated on/before the statement date). Adding Σ matched would double-count
     * those lines, so matched statement lines do not enter the arithmetic — matching records which
     * statement lines are explained (see the report's matched-vs-outstanding split), while the balance
     * is reconciled by real adjustment JEs. Adjustments, in contrast, post AFTER the frozen snapshot, so
     * Σ adjustments is added exactly once.
     */
    private BigDecimal computeDifference(@NonNull BankReconciliation recon) {
        List<BankReconciliationAdjustment> adjustments =
                adjustmentRepository.findByReconciliation_ReconciliationId(recon.getReconciliationId());
        BigDecimal expected = recon.getGlEndingBalance().add(sumAdjustments(adjustments));
        return recon.getStatementEndingBalance().subtract(expected);
    }

    private static BigDecimal sumMatched(List<BankReconciliationLine> lines) {
        return lines.stream()
                .filter(l -> l.getStatus() == BankReconciliationLineStatus.MATCHED)
                .map(BankReconciliationLine::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal sumAdjustments(List<BankReconciliationAdjustment> adjustments) {
        return adjustments.stream()
                .map(BankReconciliationAdjustment::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal signedGl(JournalEntryLine line) {
        return line.getDebitAmount().subtract(line.getCreditAmount());
    }

    private static JournalEntryLine debit(UUID accountId, BigDecimal amount, String description) {
        JournalEntryLine line = new JournalEntryLine();
        line.setGlAccountId(accountId);
        line.setDebitAmount(amount);
        line.setCreditAmount(BigDecimal.ZERO);
        line.setDescription(description);
        return line;
    }

    private static JournalEntryLine credit(UUID accountId, BigDecimal amount, String description) {
        JournalEntryLine line = new JournalEntryLine();
        line.setGlAccountId(accountId);
        line.setDebitAmount(BigDecimal.ZERO);
        line.setCreditAmount(amount);
        line.setDescription(description);
        return line;
    }

    private String currentUser() {
        return SecurityContextHelper.isAuthenticated()
                ? SecurityContextHelper.getCurrentUsernameOrDefault(SYSTEM)
                : SYSTEM;
    }

    private BankReconciliationResponse toResponse(@NonNull BankReconciliation recon) {
        List<BankReconciliationLine> lines =
                lineRepository.findByReconciliation_ReconciliationId(recon.getReconciliationId()).stream()
                        .sorted(Comparator.comparing(
                                BankReconciliationLine::getLineNumber, Comparator.nullsLast(Comparator.naturalOrder())))
                        .toList();
        List<BankReconciliationAdjustment> adjustments =
                adjustmentRepository.findByReconciliation_ReconciliationId(recon.getReconciliationId());
        return BankReconciliationResponse.from(recon, lines, adjustments);
    }
}

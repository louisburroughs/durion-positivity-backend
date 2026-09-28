package com.positivity.accounting.internal.bankrec.service;

import com.positivity.accounting.internal.bankrec.dto.AutoMatchResponse;
import com.positivity.accounting.internal.bankrec.dto.ReconciliationCandidatesResponse;
import com.positivity.accounting.internal.bankrec.dto.ReconciliationCandidatesResponse.Candidate;
import com.positivity.accounting.internal.bankrec.dto.ReconciliationMatchCreateRequest;
import com.positivity.accounting.internal.bankrec.dto.ReconciliationMatchDecisionRequest;
import com.positivity.accounting.internal.bankrec.dto.ReconciliationMatchResponse;
import com.positivity.accounting.internal.bankrec.dto.ReconciliationUnmatchRequest;
import com.positivity.accounting.internal.bankrec.entity.BankReconciliation;
import com.positivity.accounting.internal.bankrec.entity.BankReconciliationBankMatch;
import com.positivity.accounting.internal.bankrec.entity.BankReconciliationGlMatch;
import com.positivity.accounting.internal.bankrec.entity.BankReconciliationMatch;
import com.positivity.accounting.internal.bankrec.entity.BankTransaction;
import com.positivity.accounting.internal.bankrec.enums.BankTransactionStatus;
import com.positivity.accounting.internal.bankrec.enums.CandidateReason;
import com.positivity.accounting.internal.bankrec.enums.MatchKind;
import com.positivity.accounting.internal.bankrec.enums.MatchOrigin;
import com.positivity.accounting.internal.bankrec.enums.MatchReviewReason;
import com.positivity.accounting.internal.bankrec.enums.MatchState;
import com.positivity.accounting.internal.bankrec.intake.BankRecErrorCode;
import com.positivity.accounting.internal.bankrec.intake.BankRecException;
import com.positivity.accounting.internal.bankrec.intake.Justification;
import com.positivity.accounting.internal.bankrec.repository.BankReconciliationBankMatchRepository;
import com.positivity.accounting.internal.bankrec.repository.BankReconciliationGlMatchRepository;
import com.positivity.accounting.internal.bankrec.repository.BankReconciliationMatchRepository;
import com.positivity.accounting.internal.bankrec.repository.BankTransactionRepository;
import com.positivity.accounting.internal.bankrec.service.CandidateFinder.ScoredLine;
import com.positivity.accounting.internal.bankrec.service.CandidateScorer.Score;
import com.positivity.accounting.internal.bankrec.service.MatchWriter.Header;
import com.positivity.accounting.internal.bankrec.service.MatchWriter.LedgerMember;
import com.positivity.accounting.internal.entity.JournalEntryLine;
import com.positivity.accounting.internal.exception.ReconciliationNotFoundException;
import com.positivity.accounting.internal.repository.JournalEntryLineRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Matching (SPEC-manual-bank-reconciliation §3.4, §4.6; story S4, #2303).
 *
 * <ul>
 *   <li>A human match is created {@code ACCEPTED} when M1–M4 hold and no M5 reason applies; an M5 reason
 *       without a justification answers 422 {@code MATCH_REQUIRES_REVIEW} listing the reasons.
 *   <li>Auto-match proposes {@code ONE_TO_ONE} {@code RULE} matches only (M6, D12): top candidate ≥ 90 and
 *       ahead of the second by ≥ 20; anything closer is counted ambiguous and proposes nothing.
 *   <li>An unmatch keeps the header (M7) with its actor and reason and re-opens the items it had cleared.
 * </ul>
 */
@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class ReconciliationMatchingServiceImpl implements ReconciliationMatchingService {

    static final int AUTO_MATCH_MIN_SCORE = 90;
    static final int AUTO_MATCH_MIN_MARGIN = 20;
    static final int CANDIDATE_LIMIT = 50;

    private final ReconciliationSupport support;
    private final ReconciliationEligibility eligibility;
    private final ReconciliationCalculator calculator;
    private final CandidateFinder finder;
    private final MatchWriter writer;
    private final MatchResponses responses;
    private final BankReconciliationMatchRepository matches;
    private final BankReconciliationGlMatchRepository glMatches;
    private final BankReconciliationBankMatchRepository bankMatches;
    private final BankTransactionRepository transactions;
    private final JournalEntryLineRepository lines;
    private final BankRecAuditRecorder audit;
    private final BankRecSettings settings;
    private final FunctionalCurrency currency;

    @Override
    public @NonNull ReconciliationMatchResponse createMatch(
            @NonNull UUID reconciliationId, @NonNull ReconciliationMatchCreateRequest request) {
        BankReconciliation recon = support.requireOpen(reconciliationId);
        Set<UUID> bankIds = new HashSet<>(request.getBankTransactionIds());
        Set<UUID> glIds = new HashSet<>(request.getGlLineIds());
        BankReconciliationMatch replay =
                matches.findByRequestId(request.getRequestId()).orElse(null);
        if (replay != null) {
            return replay(replay, reconciliationId, bankIds, glIds);
        }

        MatchKind kind = MatchRules.kind(bankIds.size(), glIds.size());
        List<BankTransaction> bankRows = eligibility.lockBankForMatch(recon, bankIds, null);
        List<LedgerLine> ledgerLines = eligibility.lockLedgerForMatch(recon, glIds, null).stream()
                .map(LedgerLine::of)
                .toList();
        BigDecimal bankTotal = sumBank(bankRows);
        BigDecimal ledgerTotal = sumLedger(ledgerLines);
        MatchRules.requireAmountsAgree(bankTotal, ledgerTotal, currency.tolerance());
        String justification = Justification.optional(request.getJustification(), "justification");
        requireReview(recon, kind, bankTotal.subtract(ledgerTotal).abs(), bankRows, ledgerLines, justification);

        String actor = support.currentUser();
        BankReconciliationMatch match = writer.create(
                recon,
                new Header(
                        kind,
                        MatchState.ACCEPTED,
                        MatchOrigin.USER,
                        null,
                        null,
                        justification,
                        request.getRequestId(),
                        null),
                bankRows,
                ledgerLines.stream().map(LedgerMember::of).toList(),
                actor);
        audit.record(
                BankRecAuditRecorder.RECONCILIATION_MATCH,
                match.getMatchId(),
                BankRecAuditRecorder.RECONCILIATION_MATCH_CREATE,
                actor,
                justification,
                null,
                MatchState.ACCEPTED + ";kind=" + kind + ";reconciliationId=" + reconciliationId);
        support.refresh(recon);
        log.info(
                "Matched {} bank transaction(s) to {} GL line(s) in reconciliation {} (match {}, {})",
                bankRows.size(),
                ledgerLines.size(),
                reconciliationId,
                match.getMatchId(),
                kind);
        return responses.of(match);
    }

    @Override
    public @NonNull ReconciliationMatchResponse accept(
            @NonNull UUID reconciliationId,
            @NonNull UUID matchId,
            @NonNull ReconciliationMatchDecisionRequest request) {
        BankReconciliation recon = support.requireOpen(reconciliationId);
        BankReconciliationMatch match = requireMatch(reconciliationId, matchId, MatchState.PROPOSED);
        List<UUID> bankIds = bankMatches.findByMatchIdAndActiveTrue(matchId).stream()
                .map(BankReconciliationBankMatch::getBankTransactionId)
                .toList();
        List<UUID> glIds = glMatches.findByMatchIdAndActiveTrue(matchId).stream()
                .map(BankReconciliationGlMatch::getGlLineId)
                .toList();
        List<BankTransaction> bankRows = eligibility.lockBankForMatch(recon, bankIds, matchId);
        List<LedgerLine> ledgerLines = eligibility.lockLedgerForMatch(recon, glIds, matchId).stream()
                .map(LedgerLine::of)
                .toList();
        String justification = Justification.optional(request.getJustification(), "justification");
        requireReview(recon, match.getMatchKind(), match.getToleranceUsed(), bankRows, ledgerLines, justification);

        String actor = support.currentUser();
        if (justification != null) {
            match.setJustification(justification);
        }
        match.setState(MatchState.ACCEPTED);
        match.setAcceptedBy(actor);
        match.setAcceptedAt(support.now());
        matches.save(match);
        writer.accept(recon, match, bankRows, glIds, actor);
        audit.record(
                BankRecAuditRecorder.RECONCILIATION_MATCH,
                matchId,
                BankRecAuditRecorder.RECONCILIATION_MATCH_ACCEPT,
                actor,
                justification,
                MatchState.PROPOSED.name(),
                MatchState.ACCEPTED.name());
        support.refresh(recon);
        return responses.of(match);
    }

    @Override
    public @NonNull ReconciliationMatchResponse reject(
            @NonNull UUID reconciliationId,
            @NonNull UUID matchId,
            @NonNull ReconciliationMatchDecisionRequest request) {
        BankReconciliation recon = support.requireOpen(reconciliationId);
        BankReconciliationMatch match = requireMatch(reconciliationId, matchId, MatchState.PROPOSED);
        String justification = Justification.optional(request.getJustification(), "justification");
        String actor = support.currentUser();
        writer.end(match, MatchState.REJECTED, null, actor);
        audit.record(
                BankRecAuditRecorder.RECONCILIATION_MATCH,
                matchId,
                BankRecAuditRecorder.RECONCILIATION_MATCH_REJECT,
                actor,
                justification,
                MatchState.PROPOSED.name(),
                MatchState.REJECTED.name());
        support.refresh(recon);
        return responses.of(match);
    }

    @Override
    public @NonNull ReconciliationMatchResponse unmatch(
            @NonNull UUID reconciliationId, @NonNull UUID matchId, @NonNull ReconciliationUnmatchRequest request) {
        BankReconciliation recon = support.requireOpen(reconciliationId);
        String reason = Justification.required(request.getReason(), "reason");
        BankReconciliationMatch match = requireMatch(reconciliationId, matchId, MatchState.ACCEPTED);
        String actor = support.currentUser();
        writer.end(match, MatchState.UNMATCHED, reason, actor);
        audit.record(
                BankRecAuditRecorder.RECONCILIATION_MATCH,
                matchId,
                BankRecAuditRecorder.RECONCILIATION_UNMATCH,
                actor,
                reason,
                MatchState.ACCEPTED.name(),
                MatchState.UNMATCHED.name());
        support.refresh(recon);
        log.info("Unmatched match {} in reconciliation {}", matchId, reconciliationId);
        return responses.of(match);
    }

    @Override
    @Transactional(readOnly = true)
    public @NonNull ReconciliationCandidatesResponse candidates(
            @NonNull UUID reconciliationId,
            @Nullable UUID bankTransactionId,
            @Nullable UUID glLineId,
            @Nullable Integer windowDays) {
        BankReconciliation recon = support.require(reconciliationId);
        if ((bankTransactionId == null) == (glLineId == null)) {
            throw new BankRecException(
                    BankRecErrorCode.VALIDATION_ERROR, "Name exactly one of bankTransactionId and glLineId");
        }
        if (windowDays != null && windowDays < 1) {
            throw BankRecException.field(
                    BankRecErrorCode.VALIDATION_ERROR, "windowDays must be at least 1", "windowDays", "at least 1");
        }
        int window = settings.matchDateWindowDays();
        int effective = windowDays != null ? Math.max(window, windowDays) : window;
        if (bankTransactionId != null) {
            BankTransaction bank = transactions
                    .findById(bankTransactionId)
                    .filter(t -> recon.getGlAccountId().equals(t.getGlAccountId()))
                    .orElseThrow(() -> new BankRecException(
                            BankRecErrorCode.BANK_TRANSACTION_NOT_FOUND,
                            "Bank transaction not found on the account: " + bankTransactionId));
            List<Candidate> ranked = finder.ledgerCandidates(recon, bank, effective).stream()
                    .map(c -> toCandidate(c.line(), c.score()))
                    .limit(CANDIDATE_LIMIT)
                    .toList();
            return ReconciliationCandidatesResponse.builder()
                    .bankTransactionId(bankTransactionId)
                    .windowDays(effective)
                    .candidates(ranked)
                    .build();
        }
        JournalEntryLine line = lines.findById(glLineId)
                .filter(l -> recon.getGlAccountId().equals(l.getGlAccountId()))
                .orElseThrow(
                        () -> new ReconciliationNotFoundException("GL line not found on the account: " + glLineId));
        LedgerLine subject = LedgerLine.of(line);
        List<Candidate> ranked = finder.bankCandidates(recon, subject, effective).stream()
                .map(c -> toCandidate(c.bank(), c.score()))
                .limit(CANDIDATE_LIMIT)
                .toList();
        return ReconciliationCandidatesResponse.builder()
                .glLineId(glLineId)
                .windowDays(effective)
                .candidates(ranked)
                .build();
    }

    @Override
    public @NonNull AutoMatchResponse autoMatch(@NonNull UUID reconciliationId) {
        BankReconciliation recon = support.requireOpen(reconciliationId);
        List<BankTransaction> subjects = calculator.compute(recon).unexplainedBank().stream()
                .filter(t -> t.getStatus() == BankTransactionStatus.UNMATCHED)
                .toList();
        Set<UUID> reserved = bankMatches
                .findByBankTransactionIdInAndActiveTrue(subjects.stream()
                        .map(BankTransaction::getBankTransactionId)
                        .toList())
                .stream()
                .map(BankReconciliationBankMatch::getBankTransactionId)
                .collect(Collectors.toSet());
        subjects = subjects.stream()
                .filter(t -> !reserved.contains(t.getBankTransactionId()))
                .toList();
        if (subjects.isEmpty()) {
            return record(recon, 0, 0);
        }
        Map<UUID, List<ScoredLine>> rankedByBank = finder.rankedForEach(recon, subjects);

        String actor = support.currentUser();
        Set<UUID> used = new HashSet<>();
        int proposed = 0;
        int ambiguous = 0;
        for (BankTransaction bank : subjects) {
            List<ScoredLine> ranked = rankedByBank.get(bank.getBankTransactionId());
            if (ranked.isEmpty() || ranked.get(0).score().points() < AUTO_MATCH_MIN_SCORE) {
                continue;
            }
            ScoredLine top = ranked.get(0);
            boolean clear = ranked.size() == 1
                    || top.score().points() - ranked.get(1).score().points() >= AUTO_MATCH_MIN_MARGIN;
            if (!clear || used.contains(top.line().lineId())) {
                ambiguous++;
                continue;
            }
            BigDecimal residual =
                    bank.getSignedAmount().subtract(top.line().signedAmount()).abs();
            if (residual.compareTo(currency.tolerance()) > 0) {
                continue; // M1: a proposal never exceeds one minor unit
            }
            used.add(top.line().lineId());
            writer.create(
                    recon,
                    new Header(
                            MatchKind.ONE_TO_ONE,
                            MatchState.PROPOSED,
                            MatchOrigin.RULE,
                            Math.min(100, top.score().points()),
                            top.score().reasons().stream()
                                    .map(CandidateReason::name)
                                    .toList(),
                            null,
                            null,
                            null),
                    List.of(bank),
                    List.of(LedgerMember.of(top.line())),
                    actor);
            proposed++;
        }
        return record(recon, proposed, ambiguous);
    }

    private static Candidate toCandidate(LedgerLine line, Score score) {
        return Candidate.builder()
                .glLineId(line.lineId())
                .journalEntryId(line.journalEntryId())
                .entryNumber(line.entryNumber())
                .date(line.date())
                .signedAmount(line.signedAmount())
                .description(line.description() != null ? line.description() : line.entryDescription())
                .score(score.points())
                .reasons(score.reasons())
                .dateDistance(score.dateDistance())
                .build();
    }

    private static Candidate toCandidate(BankTransaction bank, Score score) {
        return Candidate.builder()
                .bankTransactionId(bank.getBankTransactionId())
                .date(bank.getTransactionDate())
                .signedAmount(bank.getSignedAmount())
                .description(bank.getDescription())
                .score(score.points())
                .reasons(score.reasons())
                .dateDistance(score.dateDistance())
                .build();
    }

    // ---- helpers ---------------------------------------------------------------------------------

    private void requireReview(
            BankReconciliation recon,
            MatchKind kind,
            BigDecimal toleranceUsed,
            List<BankTransaction> bankRows,
            List<LedgerLine> ledgerLines,
            @Nullable String justification) {
        Set<MatchReviewReason> reasons = MatchRules.reviewReasons(
                kind,
                toleranceUsed,
                bankRows.stream().map(BankTransaction::getTransactionDate).toList(),
                ledgerLines.stream().map(LedgerLine::date).toList(),
                recon.getStatementStartDate(),
                settings.matchDateWindowDays(),
                eligibility.anyFormerPossibleDuplicate(bankRows));
        if (!reasons.isEmpty() && justification == null) {
            String listed = reasons.stream().map(Enum::name).collect(Collectors.joining(","));
            throw BankRecException.field(
                    BankRecErrorCode.MATCH_REQUIRES_REVIEW,
                    "This match needs a justification (at least 10 characters): " + listed,
                    "justification",
                    listed);
        }
    }

    private ReconciliationMatchResponse replay(
            BankReconciliationMatch replay, UUID reconciliationId, Set<UUID> bankIds, Set<UUID> glIds) {
        ReconciliationMatchResponse response = responses.of(replay);
        boolean same = replay.getReconciliationId().equals(reconciliationId)
                && new HashSet<>(response.getBankTransactionIds()).equals(bankIds)
                && new HashSet<>(response.getGlLineIds()).equals(glIds);
        if (!same) {
            throw new BankRecException(
                    BankRecErrorCode.IDEMPOTENCY_CONFLICT,
                    "requestId " + replay.getRequestId() + " was already used with a different payload");
        }
        response.setReplayed(true);
        return response;
    }

    private BankReconciliationMatch requireMatch(UUID reconciliationId, UUID matchId, MatchState expected) {
        BankReconciliationMatch match = matches.findByMatchIdAndReconciliationId(matchId, reconciliationId)
                .orElseThrow(() -> new ReconciliationNotFoundException(
                        "Match " + matchId + " not found in reconciliation " + reconciliationId));
        if (match.getState() != expected) {
            throw new BankRecException(
                    BankRecErrorCode.MATCH_STATE_INVALID,
                    "Match " + matchId + " is " + match.getState() + "; the action needs " + expected);
        }
        return match;
    }

    private AutoMatchResponse record(BankReconciliation recon, int proposed, int ambiguous) {
        audit.record(
                BankRecAuditRecorder.BANK_RECONCILIATION,
                recon.getReconciliationId(),
                BankRecAuditRecorder.RECONCILIATION_AUTO_MATCH,
                support.currentUser(),
                null,
                null,
                "proposed=" + proposed + ";ambiguous=" + ambiguous);
        support.refresh(recon);
        log.info(
                "Auto-match on reconciliation {}: {} proposed, {} ambiguous",
                recon.getReconciliationId(),
                proposed,
                ambiguous);
        return AutoMatchResponse.builder()
                .proposedCount(proposed)
                .ambiguousCount(ambiguous)
                .build();
    }

    private static LocalDate min(LocalDate a, LocalDate b) {
        return a.isBefore(b) ? a : b;
    }

    private static BigDecimal sumBank(Collection<BankTransaction> rows) {
        return rows.stream().map(BankTransaction::getSignedAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal sumLedger(Collection<LedgerLine> ledgerLines) {
        return ledgerLines.stream().map(LedgerLine::signedAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}

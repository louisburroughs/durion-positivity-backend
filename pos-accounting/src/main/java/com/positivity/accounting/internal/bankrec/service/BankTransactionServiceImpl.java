package com.positivity.accounting.internal.bankrec.service;

import com.positivity.accounting.internal.bankrec.dto.BankTransactionBatchResponse;
import com.positivity.accounting.internal.bankrec.dto.BankTransactionJustificationRequest;
import com.positivity.accounting.internal.bankrec.dto.BankTransactionListResponse;
import com.positivity.accounting.internal.bankrec.dto.BankTransactionResponse;
import com.positivity.accounting.internal.bankrec.dto.DuplicateReviewDecision;
import com.positivity.accounting.internal.bankrec.dto.DuplicateReviewRequest;
import com.positivity.accounting.internal.bankrec.entity.BankTransaction;
import com.positivity.accounting.internal.bankrec.enums.BankStatementStatus;
import com.positivity.accounting.internal.bankrec.enums.BankTransactionStatus;
import com.positivity.accounting.internal.bankrec.enums.ReconciliationStatus;
import com.positivity.accounting.internal.bankrec.enums.SourceKind;
import com.positivity.accounting.internal.bankrec.intake.BankRecErrorCode;
import com.positivity.accounting.internal.bankrec.intake.BankRecException;
import com.positivity.accounting.internal.bankrec.intake.Justification;
import com.positivity.accounting.internal.bankrec.repository.BankReconciliationRepository;
import com.positivity.accounting.internal.bankrec.repository.BankStatementRepository;
import com.positivity.accounting.internal.bankrec.repository.BankTransactionRepository;
import com.positivity.accounting.internal.bankrec.service.BankCashAccounts.BankCashAccount;
import com.positivity.security.common.SecurityContextHelper;
import jakarta.persistence.criteria.Predicate;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Bank transaction reads and the human transitions of story S2 (SPEC-manual-bank-reconciliation
 * §3.8, §4.5, §6.1; #2301): {@code POSSIBLE_DUPLICATE → UNMATCHED | EXCLUDED} by duplicate review,
 * {@code UNMATCHED → EXCLUDED} by exclude and {@code EXCLUDED → UNMATCHED} by restore, each justified
 * (≥ 10 characters, D15) and audited. Excluded rows leave every sum (R2).
 */
@Service
@Transactional
@RequiredArgsConstructor
public class BankTransactionServiceImpl implements BankTransactionService {

    private static final String SYSTEM = "SYSTEM";

    /** Rows that still need an explanation (§4.1, §4.5: an unreviewed possible duplicate counts). */
    static final Set<BankTransactionStatus> UNEXPLAINED =
            EnumSet.of(BankTransactionStatus.UNMATCHED, BankTransactionStatus.POSSIBLE_DUPLICATE);

    private final BankTransactionRepository transactions;
    private final BankReconciliationRepository reconciliations;
    private final BankCashAccounts bankCashAccounts;
    private final BankRecAuditRecorder audit;
    private final BankStatementRepository statements;
    private final Clock clock;

    @Override
    @Transactional(readOnly = true)
    public @NonNull BankTransactionListResponse listTransactions(
            @Nullable UUID glAccountId,
            @Nullable BankTransactionStatus status,
            @Nullable LocalDate from,
            @Nullable LocalDate to,
            @Nullable SourceKind sourceKind,
            boolean unexplainedOnly,
            int page,
            int size) {
        if (glAccountId == null) {
            throw BankRecException.field(
                    BankRecErrorCode.VALIDATION_ERROR, "glAccountId is required", "glAccountId", "is required");
        }
        if (from != null && to != null && from.isAfter(to)) {
            throw BankRecException.field(
                    BankRecErrorCode.VALIDATION_ERROR, "from must not be after to", "from", "must not be after to");
        }
        Specification<BankTransaction> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("glAccountId"), glAccountId));
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (unexplainedOnly) {
                predicates.add(root.get("status").in(UNEXPLAINED));
            }
            if (from != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("transactionDate"), from));
            }
            if (to != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("transactionDate"), to));
            }
            if (sourceKind != null) {
                predicates.add(cb.equal(root.get("sourceKind"), sourceKind));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
        Page<BankTransaction> result = transactions.findAll(
                spec, BankRecPaging.page(page, size, Sort.by("transactionDate", "bankTransactionId")));
        BankCashAccount account =
                bankCashAccounts.displayValues(List.of(glAccountId)).get(glAccountId);
        List<BankTransactionResponse> rows = result.getContent().stream()
                .map(row -> BankRecViews.transaction(row, account))
                .toList();
        return new BankTransactionListResponse(
                rows, result.getTotalElements(), result.getNumber(), result.getSize(), result.getTotalPages());
    }

    @Override
    @Transactional(readOnly = true)
    public @NonNull BankTransactionResponse getTransaction(@NonNull UUID bankTransactionId) {
        return view(load(bankTransactionId));
    }

    @Override
    public @NonNull BankTransactionResponse reviewDuplicate(
            @NonNull UUID bankTransactionId, @NonNull DuplicateReviewRequest request) {
        DuplicateReviewDecision decision = requireDecision(request);
        String justification = Justification.required(request.getJustification(), "justification");
        BankTransaction row = load(bankTransactionId);
        requireVersion(row, request.getVersion());
        return view(review(row, decision, request.getDuplicateOfBankTransactionId(), justification));
    }

    @Override
    public @NonNull BankTransactionBatchResponse reviewDuplicates(@NonNull DuplicateReviewRequest request) {
        if (request.getIds() == null || request.getIds().isEmpty()) {
            throw BankRecException.field(
                    BankRecErrorCode.VALIDATION_ERROR, "ids is required", "ids", "at least one id is required");
        }
        DuplicateReviewDecision decision = requireDecision(request);
        String justification = Justification.required(request.getJustification(), "justification");
        List<BankTransactionResponse> reviewed = new ArrayList<>();
        for (UUID id : new LinkedHashSet<>(request.getIds())) {
            reviewed.add(view(review(load(id), decision, request.getDuplicateOfBankTransactionId(), justification)));
        }
        return new BankTransactionBatchResponse(reviewed);
    }

    @Override
    public @NonNull BankTransactionResponse exclude(
            @NonNull UUID bankTransactionId, @NonNull BankTransactionJustificationRequest request) {
        String justification = Justification.required(request.getJustification(), "justification");
        BankTransaction row = load(bankTransactionId);
        requireVersion(row, request.getVersion());
        if (row.getStatus() != BankTransactionStatus.UNMATCHED) {
            throw ineligible(row, "only an UNMATCHED row can be excluded");
        }
        String actor = currentActor();
        markExcluded(row, justification, actor);
        audit.record(
                BankRecAuditRecorder.BANK_TRANSACTION,
                row.getBankTransactionId(),
                BankRecAuditRecorder.BANK_TRANSACTION_EXCLUDE,
                actor,
                justification,
                BankTransactionStatus.UNMATCHED.name(),
                BankTransactionStatus.EXCLUDED.name());
        return view(transactions.saveAndFlush(row));
    }

    @Override
    public @NonNull BankTransactionResponse restore(
            @NonNull UUID bankTransactionId, @NonNull BankTransactionJustificationRequest request) {
        String justification = Justification.required(request.getJustification(), "justification");
        BankTransaction row = load(bankTransactionId);
        requireVersion(row, request.getVersion());
        if (row.getStatus() != BankTransactionStatus.EXCLUDED) {
            throw ineligible(row, "only an EXCLUDED row can be restored");
        }
        if (StatementSupersession.STATEMENT_SUPERSEDED.equals(row.getExclusionReason())
                || (row.getStatementId() != null
                        && statements
                                .findById(row.getStatementId())
                                .filter(s -> s.getStatus() == BankStatementStatus.SUPERSEDED)
                                .isPresent())) {
            // §3.8: a row of a superseded statement was replaced by the corrected statement's row (S5, #2304).
            throw ineligible(row, "it belongs to a superseded statement; its corrected statement replaced it");
        }
        if (reconciliations
                .existsByGlAccount_GlAccountIdAndStatusAndStatementStartDateLessThanEqualAndStatementEndDateGreaterThanEqual(
                        row.getGlAccountId(),
                        ReconciliationStatus.FINALIZED,
                        row.getTransactionDate(),
                        row.getTransactionDate())) {
            throw ineligible(row, "a FINALIZED reconciliation covers " + row.getTransactionDate());
        }
        String actor = currentActor();
        row.setStatus(BankTransactionStatus.UNMATCHED);
        row.setExclusionReason(null);
        row.setExcludedBy(null);
        row.setExcludedAt(null);
        row.setDuplicateOfBankTransactionId(null);
        audit.record(
                BankRecAuditRecorder.BANK_TRANSACTION,
                row.getBankTransactionId(),
                BankRecAuditRecorder.BANK_TRANSACTION_RESTORE,
                actor,
                justification,
                BankTransactionStatus.EXCLUDED.name(),
                BankTransactionStatus.UNMATCHED.name());
        return view(transactions.saveAndFlush(row));
    }

    // ---- helpers ------------------------------------------------------------------------------

    private BankTransaction review(
            BankTransaction row,
            DuplicateReviewDecision decision,
            @Nullable UUID requestedOriginal,
            String justification) {
        if (row.getStatus() != BankTransactionStatus.POSSIBLE_DUPLICATE) {
            throw ineligible(row, "only a POSSIBLE_DUPLICATE row can be reviewed");
        }
        String actor = currentActor();
        if (decision == DuplicateReviewDecision.DISTINCT) {
            row.setStatus(BankTransactionStatus.UNMATCHED);
            row.setDuplicateOfBankTransactionId(null);
        } else {
            UUID original = requestedOriginal != null ? requestedOriginal : row.getDuplicateOfBankTransactionId();
            requireOriginal(row, original);
            row.setDuplicateOfBankTransactionId(original);
            markExcluded(row, justification, actor);
        }
        audit.record(
                BankRecAuditRecorder.BANK_TRANSACTION,
                row.getBankTransactionId(),
                BankRecAuditRecorder.BANK_TRANSACTION_DUPLICATE_REVIEW,
                actor,
                justification,
                BankTransactionStatus.POSSIBLE_DUPLICATE.name(),
                row.getStatus().name()
                        + (row.getDuplicateOfBankTransactionId() == null
                                ? ""
                                : " duplicateOf=" + row.getDuplicateOfBankTransactionId()));
        return transactions.saveAndFlush(row);
    }

    private void requireOriginal(BankTransaction row, @Nullable UUID original) {
        if (original == null) {
            throw BankRecException.field(
                    BankRecErrorCode.VALIDATION_ERROR,
                    "duplicateOfBankTransactionId is required: the row names no original",
                    "duplicateOfBankTransactionId",
                    "is required");
        }
        boolean valid = !original.equals(row.getBankTransactionId())
                && transactions
                        .findById(original)
                        .filter(candidate -> Objects.equals(candidate.getGlAccountId(), row.getGlAccountId()))
                        .isPresent();
        if (!valid) {
            throw BankRecException.field(
                    BankRecErrorCode.VALIDATION_ERROR,
                    "duplicateOfBankTransactionId must name another bank transaction on the same account",
                    "duplicateOfBankTransactionId",
                    "another bank transaction on the same account");
        }
    }

    private void markExcluded(BankTransaction row, String justification, String actor) {
        row.setStatus(BankTransactionStatus.EXCLUDED);
        row.setExclusionReason(justification);
        row.setExcludedBy(actor);
        row.setExcludedAt(Instant.now(clock));
    }

    private static DuplicateReviewDecision requireDecision(DuplicateReviewRequest request) {
        if (request.getDecision() == null) {
            throw BankRecException.field(
                    BankRecErrorCode.VALIDATION_ERROR, "decision is required", "decision", "DISTINCT or DUPLICATE");
        }
        return request.getDecision();
    }

    private static void requireVersion(BankTransaction row, @Nullable Long expected) {
        if (expected != null && !expected.equals(row.getVersion())) {
            throw new BankRecException(
                    BankRecErrorCode.OPTIMISTIC_LOCK, "The record was changed by another request; reload it and retry");
        }
    }

    private static BankRecException ineligible(BankTransaction row, String why) {
        return new BankRecException(
                BankRecErrorCode.RECONCILIATION_LINE_INELIGIBLE,
                "Bank transaction " + row.getBankTransactionId() + " is " + row.getStatus() + ": " + why);
    }

    private BankTransaction load(UUID bankTransactionId) {
        return transactions
                .findById(bankTransactionId)
                .orElseThrow(() -> new BankRecException(
                        BankRecErrorCode.BANK_TRANSACTION_NOT_FOUND,
                        "Bank transaction not found: " + bankTransactionId));
    }

    private BankTransactionResponse view(BankTransaction row) {
        Map<UUID, BankCashAccount> accounts = bankCashAccounts.displayValues(List.of(row.getGlAccountId()));
        return BankRecViews.transaction(row, accounts.get(row.getGlAccountId()));
    }

    private static String currentActor() {
        return SecurityContextHelper.isAuthenticated()
                ? SecurityContextHelper.getCurrentUsernameOrDefault(SYSTEM)
                : SYSTEM;
    }
}

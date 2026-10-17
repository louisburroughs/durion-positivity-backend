package com.positivity.accounting.internal.bankrec.readmodel;

import com.positivity.accounting.internal.bankrec.entity.BankAccountProfile;
import com.positivity.accounting.internal.bankrec.entity.BankReconciliation;
import com.positivity.accounting.internal.bankrec.entity.BankStatement;
import com.positivity.accounting.internal.bankrec.enums.BankStatementStatus;
import com.positivity.accounting.internal.bankrec.enums.BankTransactionStatus;
import com.positivity.accounting.internal.bankrec.enums.OutstandingItemKind;
import com.positivity.accounting.internal.bankrec.enums.OutstandingItemStatus;
import com.positivity.accounting.internal.bankrec.enums.ReconciliationStatus;
import com.positivity.accounting.internal.bankrec.repository.AccountCount;
import com.positivity.accounting.internal.bankrec.repository.AccountKindTotals;
import com.positivity.accounting.internal.bankrec.repository.BankAccountProfileRepository;
import com.positivity.accounting.internal.bankrec.repository.BankReconciliationOutstandingItemRepository;
import com.positivity.accounting.internal.bankrec.repository.BankReconciliationRepository;
import com.positivity.accounting.internal.bankrec.repository.BankStatementRepository;
import com.positivity.accounting.internal.bankrec.repository.BankTransactionRepository;
import com.positivity.accounting.internal.bankrec.service.ReconciliationChain;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What the bank side of reconciliation knows about each bank account, for the cash position (CAP:550 S19, #2515;
 * SPEC-accounting-workspace §4.1 "Gates"), for callers outside the bank reconciliation core that may reach it only
 * through its read model. The figures are the ones {@code BankAccountServiceImpl} lists (baseline, reconciled-through
 * date, unexplained lines, open outstanding items), plus the latest committed statement's closing balance and the open
 * outstanding items by kind with their amounts. A fixed number of grouped queries, whatever the number of accounts.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BankPositionFacts {

    /** Bank lines that still need an explanation: unmatched ones and unreviewed possible duplicates (§4.1, §4.5). */
    private static final Set<BankTransactionStatus> UNEXPLAINED =
            EnumSet.of(BankTransactionStatus.UNMATCHED, BankTransactionStatus.POSSIBLE_DUPLICATE);

    private static final Set<OutstandingItemKind> TIMING_KINDS =
            EnumSet.of(OutstandingItemKind.DEPOSIT_IN_TRANSIT, OutstandingItemKind.OUTSTANDING_CHECK);

    private final BankAccountProfileRepository profiles;
    private final BankStatementRepository statements;
    private final BankTransactionRepository transactions;
    private final BankReconciliationRepository reconciliations;
    private final BankReconciliationOutstandingItemRepository outstandingItems;

    /** Open outstanding items of one kind: how many and their signed total (debit minus credit). */
    public record ItemTotal(long count, @NonNull BigDecimal amount) {

        static final ItemTotal NONE = new ItemTotal(0, BigDecimal.ZERO);
    }

    /** One bank account's reconciliation facts. */
    public record Facts(
            @Nullable BigDecimal statementClosingBalance,
            @Nullable LocalDate statementEndDate,
            @Nullable LocalDate reconciledThrough,
            @NonNull ItemTotal depositsInTransit,
            @NonNull ItemTotal outstandingChecks,
            long unmatchedBankLineCount) {}

    /** The ISO 4217 codes the accounts' profiles record; an account without a profile is absent. */
    public @NonNull Map<UUID, String> currenciesOf(@NonNull Collection<UUID> glAccountIds) {
        if (glAccountIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, String> currencies = new HashMap<>();
        for (BankAccountProfile profile : profiles.findAllById(glAccountIds)) {
            if (profile.getCurrency() != null) {
                currencies.put(profile.getGlAccountId(), profile.getCurrency());
            }
        }
        return currencies;
    }

    /** The facts of every named account; each account is in the result. */
    public @NonNull Map<UUID, Facts> factsOf(@NonNull Collection<UUID> glAccountIds) {
        if (glAccountIds.isEmpty()) {
            return Map.of();
        }
        List<UUID> ids = List.copyOf(glAccountIds);
        Map<UUID, BankAccountProfile> profileById = profiles.findAllById(ids).stream()
                .collect(Collectors.toMap(BankAccountProfile::getGlAccountId, profile -> profile));
        Map<UUID, BankStatement> latest = new HashMap<>();
        for (BankStatement statement : statements.findLatestByGlAccountIdIn(ids, BankStatementStatus.COMMITTED)) {
            latest.putIfAbsent(statement.getGlAccountId(), statement);
        }
        Map<UUID, Long> unmatched = new HashMap<>();
        for (AccountCount row : transactions.countSinceBaselineByGlAccountIdIn(ids, UNEXPLAINED)) {
            unmatched.put(row.glAccountId(), row.count());
        }
        Map<UUID, Map<OutstandingItemKind, ItemTotal>> timing = new HashMap<>();
        for (AccountKindTotals row :
                outstandingItems.totalsSinceBaselineByGlAccountIdIn(ids, OutstandingItemStatus.OPEN, TIMING_KINDS)) {
            timing.computeIfAbsent(row.glAccountId(), id -> new HashMap<>())
                    .put(row.kind(), new ItemTotal(row.count(), row.signedAmount()));
        }
        Map<UUID, List<BankReconciliation>> finalized = reconciliations
                .findByGlAccount_GlAccountIdInAndStatusOrderByStatementStartDateAsc(ids, ReconciliationStatus.FINALIZED)
                .stream()
                .collect(Collectors.groupingBy(r -> r.getGlAccount().getGlAccountId()));

        Map<UUID, Facts> facts = new HashMap<>();
        for (UUID id : ids) {
            BankStatement statement = latest.get(id);
            BankAccountProfile profile = profileById.get(id);
            LocalDate baseline = profile == null ? null : profile.getReconciliationBaselineDate();
            Map<OutstandingItemKind, ItemTotal> items = timing.getOrDefault(id, Map.of());
            facts.put(
                    id,
                    new Facts(
                            statement == null ? null : statement.getClosingBalance(),
                            statement == null ? null : statement.getEndDate(),
                            ReconciliationChain.frontier(finalized.getOrDefault(id, List.of()), baseline),
                            items.getOrDefault(OutstandingItemKind.DEPOSIT_IN_TRANSIT, ItemTotal.NONE),
                            items.getOrDefault(OutstandingItemKind.OUTSTANDING_CHECK, ItemTotal.NONE),
                            unmatched.getOrDefault(id, 0L)));
        }
        return facts;
    }
}

package com.positivity.accounting.internal.bankrec.service;

import com.positivity.accounting.internal.bankrec.dto.ReconciliationMatchResponse;
import com.positivity.accounting.internal.bankrec.entity.BankReconciliationBankMatch;
import com.positivity.accounting.internal.bankrec.entity.BankReconciliationGlMatch;
import com.positivity.accounting.internal.bankrec.entity.BankReconciliationMatch;
import com.positivity.accounting.internal.bankrec.repository.BankReconciliationBankMatchRepository;
import com.positivity.accounting.internal.bankrec.repository.BankReconciliationGlMatchRepository;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;

/**
 * Serves matches with their members (SPEC §3.4, §4.8; story S4, #2303). A live match lists its active
 * members; an unmatched, rejected or replaced one lists the members it had (M7: history is kept).
 */
@Component
@RequiredArgsConstructor
public class MatchResponses {

    private final BankReconciliationBankMatchRepository bankMatches;
    private final BankReconciliationGlMatchRepository glMatches;

    public @NonNull ReconciliationMatchResponse of(@NonNull BankReconciliationMatch match) {
        return of(List.of(match)).get(0);
    }

    /** The matches with their members, in the given order, loading members in two queries. */
    public @NonNull List<ReconciliationMatchResponse> of(@NonNull Collection<BankReconciliationMatch> matches) {
        if (matches.isEmpty()) {
            return List.of();
        }
        List<UUID> ids =
                matches.stream().map(BankReconciliationMatch::getMatchId).toList();
        Map<UUID, List<UUID>> bankByMatch = bankMatches.findByMatchIdIn(ids).stream()
                .collect(Collectors.groupingBy(
                        BankReconciliationBankMatch::getMatchId,
                        Collectors.mapping(BankReconciliationBankMatch::getBankTransactionId, Collectors.toList())));
        Map<UUID, List<UUID>> glByMatch = glMatches.findByMatchIdIn(ids).stream()
                .collect(Collectors.groupingBy(
                        BankReconciliationGlMatch::getMatchId,
                        Collectors.mapping(BankReconciliationGlMatch::getGlLineId, Collectors.toList())));
        return matches.stream()
                .map(m -> ReconciliationMatchResponse.from(
                        m,
                        sorted(bankByMatch.getOrDefault(m.getMatchId(), List.of())),
                        sorted(glByMatch.getOrDefault(m.getMatchId(), List.of()))))
                .toList();
    }

    private static List<UUID> sorted(List<UUID> ids) {
        return ids.stream().sorted().toList();
    }
}

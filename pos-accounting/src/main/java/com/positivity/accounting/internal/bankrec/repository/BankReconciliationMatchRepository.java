package com.positivity.accounting.internal.bankrec.repository;

import com.positivity.accounting.internal.bankrec.entity.BankReconciliationMatch;
import com.positivity.accounting.internal.bankrec.enums.MatchState;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;

/** Repository for {@link BankReconciliationMatch} headers (SPEC §3.4; stories S1 #2300, S4 #2303). */
public interface BankReconciliationMatchRepository extends JpaRepository<BankReconciliationMatch, UUID> {

    Optional<BankReconciliationMatch> findByMatchIdAndReconciliationId(
            @NonNull UUID matchId, @NonNull UUID reconciliationId);

    /** Every match of a reconciliation, history included (M7), oldest first. */
    @NonNull
    List<BankReconciliationMatch> findByReconciliationIdOrderByCreatedAtAsc(@NonNull UUID reconciliationId);

    @NonNull
    List<BankReconciliationMatch> findByReconciliationIdAndState(
            @NonNull UUID reconciliationId, @NonNull MatchState state);

    /** The match a human match command created (§6.3). */
    Optional<BankReconciliationMatch> findByRequestId(@NonNull UUID requestId);
}

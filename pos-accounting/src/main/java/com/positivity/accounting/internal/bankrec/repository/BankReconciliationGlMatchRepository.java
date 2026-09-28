package com.positivity.accounting.internal.bankrec.repository;

import com.positivity.accounting.internal.bankrec.entity.BankReconciliationGlMatch;
import com.positivity.accounting.internal.bankrec.enums.MatchState;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Repository for {@link BankReconciliationGlMatch} match linkage rows (Story F2, issue #965; S4, #2303).
 */
public interface BankReconciliationGlMatchRepository extends JpaRepository<BankReconciliationGlMatch, UUID> {

    /** The live ledger members of a reconciliation (unmatched history excluded). */
    @NonNull
    List<BankReconciliationGlMatch> findByReconciliationIdAndActiveTrue(@NonNull UUID reconciliationId);

    /** Global check: is this posted GL line already in a live match in any reconciliation? */
    boolean existsByGlLineIdAndActiveTrue(@NonNull UUID glLineId);

    @NonNull
    List<BankReconciliationGlMatch> findByMatchIdAndActiveTrue(@NonNull UUID matchId);

    /** Every ledger member a match ever had (M7: history of an unmatched match included). */
    @NonNull
    List<BankReconciliationGlMatch> findByMatchIdIn(@NonNull Collection<UUID> matchIds);

    /** The live memberships of the given lines, in any reconciliation. */
    @NonNull
    List<BankReconciliationGlMatch> findByGlLineIdInAndActiveTrue(@NonNull Collection<UUID> glLineIds);

    /** Of the given lines, those in a live match whose header is in {@code state} (§3.7: ACCEPTED explains). */
    @Query("SELECT g.glLineId FROM BankReconciliationGlMatch g, BankReconciliationMatch m "
            + "WHERE m.matchId = g.matchId AND g.active = true AND m.state = :state AND g.glLineId IN :glLineIds")
    @NonNull
    List<UUID> findActiveLineIdsInState(
            @Param("glLineIds") @NonNull Collection<UUID> glLineIds, @Param("state") @NonNull MatchState state);
}

package com.positivity.accounting.internal.bankrec.repository;

import com.positivity.accounting.internal.bankrec.entity.BankReconciliationOutstandingItem;
import com.positivity.accounting.internal.bankrec.enums.OutstandingItemStatus;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Repository for {@link BankReconciliationOutstandingItem} rows (SPEC §3.6; stories S1 #2300, S2 #2301, S4 #2303). */
public interface BankReconciliationOutstandingItemRepository
        extends JpaRepository<BankReconciliationOutstandingItem, UUID> {

    /** Open items dated on or after the baseline (§4.1). */
    long countByGlAccountIdAndStatusAndItemDateGreaterThanEqual(
            @NonNull UUID glAccountId, @NonNull OutstandingItemStatus status, @NonNull LocalDate from);

    long countByGlAccountIdAndStatus(@NonNull UUID glAccountId, @NonNull OutstandingItemStatus status);

    /** Every item on the account dated on or before {@code day}, any status: the open-at-date test reads these (§3.6). */
    @NonNull
    List<BankReconciliationOutstandingItem> findByGlAccountIdAndItemDateLessThanEqual(
            @NonNull UUID glAccountId, @NonNull LocalDate day);

    /** Items in {@code status} on the given ledger lines (O1). */
    @NonNull
    List<BankReconciliationOutstandingItem> findByGlLineIdInAndStatus(
            @NonNull Collection<UUID> glLineIds, @NonNull OutstandingItemStatus status);

    /** Items in {@code status} on the given bank transactions (O1). */
    @NonNull
    List<BankReconciliationOutstandingItem> findByBankTransactionIdInAndStatus(
            @NonNull Collection<UUID> bankTransactionIds, @NonNull OutstandingItemStatus status);

    /** Items a match cleared (unmatch re-opens them). */
    @NonNull
    List<BankReconciliationOutstandingItem> findByClearedByMatchIdAndStatus(
            @NonNull UUID clearedByMatchId, @NonNull OutstandingItemStatus status);

    /** Items a reconciliation registered, in {@code status} (cancel releases its OPEN ones; S5, #2304). */
    @NonNull
    List<BankReconciliationOutstandingItem> findByRegisteredInReconciliationIdAndStatus(
            @NonNull UUID registeredInReconciliationId, @NonNull OutstandingItemStatus status);

    /** Ids of the items a reconciliation registered, cleared or reaffirmed (its audit trail; S5, #2304). */
    @Query(
            "SELECT i.outstandingItemId FROM BankReconciliationOutstandingItem i WHERE i.registeredInReconciliationId = :id"
                    + " OR i.clearedInReconciliationId = :id OR i.lastReaffirmedInReconciliationId = :id")
    @NonNull
    List<UUID> findIdsTouchedBy(@Param("id") @NonNull UUID reconciliationId);

    Optional<BankReconciliationOutstandingItem> findByOutstandingItemIdAndGlAccountId(
            @NonNull UUID outstandingItemId, @NonNull UUID glAccountId);

    /**
     * Items in {@code status} per account, counting only items dated on or after the account's
     * baseline when it has one (§4.1) — one grouped query for a page of the bank-account list.
     */
    @Query(
            "SELECT new com.positivity.accounting.internal.bankrec.repository.AccountCount(i.glAccountId, COUNT(i)) FROM BankReconciliationOutstandingItem i"
                    + " LEFT JOIN BankAccountProfile p ON p.glAccountId = i.glAccountId"
                    + " WHERE i.glAccountId IN :ids AND i.status = :status"
                    + " AND (p.reconciliationBaselineDate IS NULL OR i.itemDate >= p.reconciliationBaselineDate)"
                    + " GROUP BY i.glAccountId")
    @NonNull
    List<AccountCount> countSinceBaselineByGlAccountIdIn(
            @Param("ids") @NonNull Collection<UUID> glAccountIds,
            @Param("status") @NonNull OutstandingItemStatus status);
}

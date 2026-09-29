package com.positivity.accounting.internal.bankrec.repository;

import com.positivity.accounting.internal.bankrec.entity.BankReconciliationOutstandingItem;
import com.positivity.accounting.internal.bankrec.enums.OutstandingItemStatus;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Repository for {@link BankReconciliationOutstandingItem} rows (SPEC §3.6; stories S1 #2300, S2 #2301). */
public interface BankReconciliationOutstandingItemRepository
        extends JpaRepository<BankReconciliationOutstandingItem, UUID> {

    /** Open items dated on or after the baseline (§4.1). */
    long countByGlAccountIdAndStatusAndItemDateGreaterThanEqual(
            @NonNull UUID glAccountId, @NonNull OutstandingItemStatus status, @NonNull LocalDate from);

    long countByGlAccountIdAndStatus(@NonNull UUID glAccountId, @NonNull OutstandingItemStatus status);

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

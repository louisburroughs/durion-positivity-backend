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

    Optional<BankReconciliationOutstandingItem> findByOutstandingItemIdAndGlAccountId(
            @NonNull UUID outstandingItemId, @NonNull UUID glAccountId);
}

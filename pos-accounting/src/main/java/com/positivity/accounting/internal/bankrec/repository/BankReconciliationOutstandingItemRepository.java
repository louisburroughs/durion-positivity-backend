package com.positivity.accounting.internal.bankrec.repository;

import com.positivity.accounting.internal.bankrec.entity.BankReconciliationOutstandingItem;
import com.positivity.accounting.internal.bankrec.enums.OutstandingItemStatus;
import java.time.LocalDate;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;

/** Repository for {@link BankReconciliationOutstandingItem} rows (SPEC §3.6; stories S1 #2300, S2 #2301). */
public interface BankReconciliationOutstandingItemRepository
        extends JpaRepository<BankReconciliationOutstandingItem, UUID> {

    /** Open items dated on or after the baseline (§4.1). */
    long countByGlAccountIdAndStatusAndItemDateGreaterThanEqual(
            @NonNull UUID glAccountId, @NonNull OutstandingItemStatus status, @NonNull LocalDate from);

    long countByGlAccountIdAndStatus(@NonNull UUID glAccountId, @NonNull OutstandingItemStatus status);
}

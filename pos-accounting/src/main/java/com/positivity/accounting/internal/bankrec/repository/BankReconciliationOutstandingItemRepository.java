package com.positivity.accounting.internal.bankrec.repository;

import com.positivity.accounting.internal.bankrec.entity.BankReconciliationOutstandingItem;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Repository for {@link BankReconciliationOutstandingItem} rows (SPEC §3.6; story S1, #2300). */
public interface BankReconciliationOutstandingItemRepository
        extends JpaRepository<BankReconciliationOutstandingItem, UUID> {}

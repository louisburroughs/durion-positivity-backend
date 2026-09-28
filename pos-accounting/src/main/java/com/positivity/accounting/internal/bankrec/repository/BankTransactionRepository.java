package com.positivity.accounting.internal.bankrec.repository;

import com.positivity.accounting.internal.bankrec.entity.BankTransaction;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Repository for {@link BankTransaction} rows (SPEC §3.2; story S1, #2300). */
public interface BankTransactionRepository extends JpaRepository<BankTransaction, UUID> {

    /** The transactions a statement carried, in source-file order (F2's statement lines). */
    List<BankTransaction> findByStatementIdOrderBySourceRowNumberAsc(UUID statementId);
}

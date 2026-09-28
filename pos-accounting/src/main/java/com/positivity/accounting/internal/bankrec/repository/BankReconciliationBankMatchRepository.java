package com.positivity.accounting.internal.bankrec.repository;

import com.positivity.accounting.internal.bankrec.entity.BankReconciliationBankMatch;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Repository for {@link BankReconciliationBankMatch} bank-side match members (SPEC §3.4; story S1, #2300). */
public interface BankReconciliationBankMatchRepository
        extends JpaRepository<BankReconciliationBankMatch, BankReconciliationBankMatch.Key> {

    /** The active (live) memberships of the given bank transactions. */
    List<BankReconciliationBankMatch> findByBankTransactionIdInAndActiveTrue(Collection<UUID> bankTransactionIds);

    List<BankReconciliationBankMatch> findByMatchIdAndActiveTrue(UUID matchId);
}

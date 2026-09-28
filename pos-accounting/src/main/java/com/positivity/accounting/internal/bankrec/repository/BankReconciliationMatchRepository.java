package com.positivity.accounting.internal.bankrec.repository;

import com.positivity.accounting.internal.bankrec.entity.BankReconciliationMatch;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Repository for {@link BankReconciliationMatch} headers (SPEC §3.4; story S1, #2300). */
public interface BankReconciliationMatchRepository extends JpaRepository<BankReconciliationMatch, UUID> {

    Optional<BankReconciliationMatch> findByMatchIdAndReconciliationId(UUID matchId, UUID reconciliationId);
}

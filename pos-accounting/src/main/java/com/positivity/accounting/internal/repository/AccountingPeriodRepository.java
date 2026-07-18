package com.positivity.accounting.internal.repository;

import com.positivity.accounting.internal.entity.AccountingPeriod;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repository for AccountingPeriod entity.
 */
public interface AccountingPeriodRepository extends JpaRepository<AccountingPeriod, UUID> {

    /**
     * Find a period by its unique {@code YYYY-MM} code.
     */
    Optional<AccountingPeriod> findByPeriodCode(String periodCode);

    /**
     * List all periods, most recent first (period codes sort lexicographically
     * in chronological order).
     */
    List<AccountingPeriod> findAllByOrderByPeriodCodeDesc();
}

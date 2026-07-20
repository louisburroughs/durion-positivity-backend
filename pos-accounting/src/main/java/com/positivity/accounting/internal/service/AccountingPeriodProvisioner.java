package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.entity.AccountingPeriod;
import com.positivity.accounting.internal.enums.AccountingPeriodStatus;
import com.positivity.accounting.internal.repository.AccountingPeriodRepository;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Inserts auto-provisioned {@link AccountingPeriod} rows in their own
 * transaction (story B1, issue #937).
 *
 * <p>The insert runs with {@code REQUIRES_NEW} so a duplicate-key collision
 * between concurrent auto-provisioners rolls back only this inner transaction.
 * On PostgreSQL a unique-constraint violation aborts the transaction it runs
 * in (and Hibernate marks it rollback-only); if the insert shared the caller's
 * transaction, the race loser could no longer re-read the winner's row and its
 * commit would fail with {@code UnexpectedRollbackException}. Isolating the
 * insert here keeps the caller's transaction committable, so
 * {@code AccountingPeriodServiceImpl.findOrProvision} can catch
 * {@code DataIntegrityViolationException} and return the winner's row.
 *
 * <p>This is a separate bean (not a private method on the service) because
 * {@code REQUIRES_NEW} only applies when the call crosses a Spring proxy
 * boundary; self-invocation would silently join the caller's transaction.
 */
@Component
@RequiredArgsConstructor
public class AccountingPeriodProvisioner {

    private final AccountingPeriodRepository periodRepository;

    /**
     * Insert a new OPEN period row in a new, independent transaction.
     *
     * @param periodCode canonical {@code YYYY-MM} period code
     * @param startDate first day of the month
     * @param endDate last day of the month (inclusive)
     * @return the persisted period row
     * @throws org.springframework.dao.DataIntegrityViolationException if a row
     *         with the same period code already exists (unique
     *         {@code uq_accounting_period_code}); only this inner transaction
     *         is rolled back
     */
    @NonNull
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AccountingPeriod provision(
            @NonNull String periodCode, @NonNull LocalDate startDate, @NonNull LocalDate endDate) {
        AccountingPeriod period = new AccountingPeriod();
        period.setPeriodCode(periodCode);
        period.setStartDate(startDate);
        period.setEndDate(endDate);
        period.setStatus(AccountingPeriodStatus.OPEN);
        return periodRepository.saveAndFlush(period);
    }
}

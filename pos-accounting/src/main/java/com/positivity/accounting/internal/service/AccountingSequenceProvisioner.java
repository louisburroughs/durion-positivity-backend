package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.entity.AccountingSequence;
import com.positivity.accounting.internal.repository.AccountingSequenceRepository;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Bootstraps {@link AccountingSequence} counter rows on first use of a scope
 * (story A2, issue #942).
 *
 * <p>Only the zero-consumption bootstrap insert runs in its own
 * {@code REQUIRES_NEW} transaction — the row is created at
 * {@code next_value = 1} and hands out no number here, so committing it
 * independently of the caller cannot create a numbering gap. The number
 * <em>assignment</em> (locked read + increment in
 * {@code JournalEntryServiceImpl.assignEntryNumber}) deliberately stays inside
 * the posting transaction, in contrast to the fully isolated
 * {@code AccountingPeriodProvisioner} pattern: a posting rollback must roll
 * the increment back with it, or the skipped number would be a permanent gap.
 *
 * <p>The isolation exists for the same reason as in
 * {@code AccountingPeriodProvisioner}: when two transactions race to
 * first-use the same scope, the loser's INSERT hits the unique
 * {@code uq_accounting_sequence_scope_key} constraint, which on PostgreSQL
 * aborts the transaction it runs in (and Hibernate marks it rollback-only).
 * Confined to this inner transaction, the failure leaves the caller
 * committable so it can catch {@code DataIntegrityViolationException} and
 * re-read the winner's row under its {@code FOR UPDATE} lock. Separate bean,
 * not a private method, because {@code REQUIRES_NEW} only applies across a
 * Spring proxy boundary.
 */
@Component
@RequiredArgsConstructor
public class AccountingSequenceProvisioner {

    private final AccountingSequenceRepository sequenceRepository;

    /**
     * Insert a new sequence row at {@code next_value = 1} in a new,
     * independent transaction.
     *
     * @param scopeKey sequence scope, e.g. {@code JE-202607}
     * @return the persisted counter row
     * @throws org.springframework.dao.DataIntegrityViolationException if a
     *         row with the same scope key already exists (unique
     *         {@code uq_accounting_sequence_scope_key}); only this inner
     *         transaction is rolled back
     */
    @NonNull
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AccountingSequence provision(@NonNull String scopeKey) {
        AccountingSequence sequence = new AccountingSequence();
        sequence.setScopeKey(scopeKey);
        sequence.setNextValue(1L);
        return sequenceRepository.saveAndFlush(sequence);
    }
}

package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.entity.AccountingSequence;
import com.positivity.accounting.internal.repository.AccountingSequenceRepository;
import com.positivity.shared.id.UUIDv7Generator;
import com.positivity.tenancy.TenantResolver;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Locks an {@link AccountingSequence} counter row, creating it on the first use of a scope
 * (story A2, issue #942; #2342).
 *
 * <p>The row is read {@code FOR UPDATE} first. Only when the scope has never been used is it
 * inserted at {@code next_value = 1} with {@code ON CONFLICT DO NOTHING} <em>in the caller's own
 * transaction</em>, then re-read under the lock. That keeps a first-use request on the one
 * connection it already holds: the former {@code REQUIRES_NEW} bootstrap needed a second pooled
 * connection while the caller kept the first, so a burst of first-use requests at least as large as
 * the pool deadlocked on it. The conflict-tolerant insert never raises a constraint violation, so
 * the caller's transaction is not marked rollback-only either. On PostgreSQL a concurrent insert of
 * the same scope waits for the in-flight inserter to commit or roll back, then does nothing, so the
 * re-read finds the winner's row. The insert hands out no number; the assignment (increment of the
 * returned locked row) stays in the caller's transaction, so a rollback returns the number.
 */
@Component
@RequiredArgsConstructor
public class AccountingSequenceLocker {

    private final AccountingSequenceRepository sequenceRepository;
    private final TenantResolver tenantResolver;

    /**
     * Return the scope's counter row locked in the current transaction, bootstrapping it first if absent.
     *
     * @param scopeKey sequence scope, e.g. {@code JE-202607}
     * @return the locked counter row
     */
    @NonNull
    @Transactional(propagation = Propagation.MANDATORY)
    public AccountingSequence lockOrProvision(@NonNull String scopeKey) {
        return sequenceRepository.findByScopeKey(scopeKey).orElseGet(() -> {
            sequenceRepository.insertIfAbsent(tenantResolver.require(), UUIDv7Generator.generate(), scopeKey);
            return sequenceRepository
                    .findByScopeKey(scopeKey)
                    .orElseThrow(() ->
                            new IllegalStateException("accounting_sequence row missing after bootstrap: " + scopeKey));
        });
    }
}

package com.positivity.workorder.internal.service;

import com.positivity.shared.id.UUIDv7Generator;
import com.positivity.tenancy.TenantResolver;
import com.positivity.workorder.internal.entity.DocumentNumberSequence;
import com.positivity.workorder.internal.repository.DocumentNumberSequenceRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.function.Predicate;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Hands out estimate and workorder numbers without two creates ever picking the same one (#2150).
 *
 * <p>Each scope has one {@link DocumentNumberSequence} row. Allocation reads it
 * {@code FOR UPDATE} and advances it in the caller's transaction ({@code MANDATORY}), so the lock
 * is held until the transaction that inserts the numbered row commits: a concurrent create in the
 * same scope waits, then reads the advanced value and sees the committed row. The number is only
 * consumed if the caller commits; a rollback returns it with the increment.
 *
 * <p>The {@code taken} check still runs for each candidate, under the lock. It skips numbers
 * issued before the counter existed (one pass, on a scope's first allocation) and numbers
 * claimed outside the counter, such as a workorder that inherits its estimate's number.
 */
@Component
@RequiredArgsConstructor
public class DocumentNumberAllocator {

    private final DocumentNumberSequenceRepository sequenceRepository;
    private final TenantResolver tenantResolver;
    private final Clock clock;

    /**
     * Allocate the next free number in a scope.
     *
     * @param scopeKey counter row to draw from, e.g. {@code EST-2026-<locationId>}
     * @param prefix text before the number, e.g. {@code EST-2026-}
     * @param firstValue the scope's first number when it has never issued one
     * @param taken whether a candidate number is already in use
     * @return {@code prefix} followed by the allocated number
     */
    @NonNull
    @Transactional(propagation = Propagation.MANDATORY)
    public String allocate(
            @NonNull String scopeKey, @NonNull String prefix, long firstValue, @NonNull Predicate<String> taken) {
        DocumentNumberSequence sequence = lockScope(scopeKey, firstValue);
        long value = sequence.getNextValue();
        String candidate = prefix + value;
        while (taken.test(candidate)) {
            value++;
            candidate = prefix + value;
        }
        sequence.setNextValue(value + 1);
        return candidate;
    }

    /**
     * Take a scope's lock without drawing a number, for a caller that assigns a number of its own
     * choosing in that scope and must not race another allocator while it checks it is free.
     */
    @NonNull
    @Transactional(propagation = Propagation.MANDATORY)
    public DocumentNumberSequence lockScope(@NonNull String scopeKey, long firstValue) {
        return sequenceRepository.findByScopeKey(scopeKey).orElseGet(() -> provisionAndRelock(scopeKey, firstValue));
    }

    /**
     * First use of a scope: insert the counter row in the caller's own transaction, then re-read it
     * under the lock. The insert is {@code ON CONFLICT DO NOTHING}, so a concurrent first use never
     * raises a constraint violation: the caller's transaction is not marked rollback-only and no
     * second ({@code REQUIRES_NEW}) connection is needed, which matters because a caller already
     * holds one and a pool of a few connections would otherwise deadlock on first-use bursts (#2342).
     * On PostgreSQL a concurrent insert of the same key waits for the in-flight inserter to
     * commit or roll back, then does nothing, so the re-read below finds the winner's row.
     */
    private DocumentNumberSequence provisionAndRelock(String scopeKey, long firstValue) {
        sequenceRepository.insertIfAbsent(
                tenantResolver.require(), UUIDv7Generator.generate(), scopeKey, firstValue, Instant.now(clock));
        return sequenceRepository
                .findByScopeKey(scopeKey)
                .orElseThrow(() -> new IllegalStateException("Number sequence " + scopeKey + " was not provisioned"));
    }
}

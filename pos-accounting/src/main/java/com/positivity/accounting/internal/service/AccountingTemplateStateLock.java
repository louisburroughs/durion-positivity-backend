package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.entity.AccountingTemplateState;
import com.positivity.accounting.internal.repository.AccountingTemplateStateRepository;
import com.positivity.shared.id.UUIDv7Generator;
import com.positivity.tenancy.TenantResolver;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Takes the bound tenant's {@code accounting_template_state} row under {@code SELECT ... FOR
 * UPDATE} (#2526), creating it on first use.
 *
 * <p>The row is what serialises template runs for a tenant: the {@code tenant.created} listener,
 * the startup sweep and a second instance all come through here, and the second to arrive waits
 * until the first has committed, then finds everything already recorded.
 *
 * <p>The first run for a tenant has no row to lock. It is inserted with {@code ON CONFLICT DO
 * NOTHING} in the caller's own transaction, on the connection it already holds, then re-read under
 * the lock: the same shape as {@link AccountingSequenceLocker}. A lost race to insert costs nothing
 * (no constraint violation, so the transaction is not marked rollback-only), and no second pooled
 * connection is taken while the first is held.
 */
@Component
@RequiredArgsConstructor
public class AccountingTemplateStateLock {

    private final AccountingTemplateStateRepository states;
    private final TenantResolver tenantResolver;
    private final Clock clock;

    /** The bound tenant's state row, locked until the caller's transaction ends. */
    @Transactional(propagation = Propagation.MANDATORY)
    public @NonNull AccountingTemplateState acquire() {
        return states.findCurrentForUpdate().orElseGet(() -> {
            states.insertIfAbsent(
                    tenantResolver.require(),
                    UUIDv7Generator.generate(),
                    Instant.now(clock).truncatedTo(ChronoUnit.MICROS));
            return states.findCurrentForUpdate()
                    .orElseThrow(() -> new IllegalStateException(
                            "accounting_template_state row is missing straight after it was created"));
        });
    }
}

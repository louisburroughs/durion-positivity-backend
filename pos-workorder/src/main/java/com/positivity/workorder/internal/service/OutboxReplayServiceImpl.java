package com.positivity.workorder.internal.service;

import com.positivity.tenancy.PlatformTenant;
import com.positivity.tenancy.TenantContext;
import com.positivity.tenancy.TenantIterator;
import com.positivity.workorder.internal.repository.OutboxEventRepository;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Replays only the rows of the tenant bound to the thread (ADR-0062 §3): the replay command arrives
 * with the requesting manifest's tenant header, so one tenant's drift never re-sends another
 * tenant's events. An unbound call fails closed with {@code TenantContextMissingException}. The
 * administrative entry point ({@link #replaySinceForCaller}) is the one place that fans out: a
 * platform-tenant operator replays every active tenant's rows, each under that tenant and each in
 * its own transaction.
 */
@Slf4j
@Service
public class OutboxReplayServiceImpl implements OutboxReplayService {

    private final OutboxEventRepository outboxEventRepository;
    private final TenantIterator tenantIterator;
    private final TransactionTemplate perTenantTransaction;

    public OutboxReplayServiceImpl(
            OutboxEventRepository outboxEventRepository,
            TenantIterator tenantIterator,
            PlatformTransactionManager transactionManager) {
        this.outboxEventRepository = outboxEventRepository;
        this.tenantIterator = tenantIterator;
        this.perTenantTransaction = new TransactionTemplate(transactionManager);
        // Never join a transaction begun before the tenant was bound: its connection was checked
        // out unbound (docs/TENANCY_SCHEMA.md, "Per-tenant schedulers and transactions").
        this.perTenantTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    @Transactional
    public int replaySince(@NonNull Instant since) {
        UUID tenantId = TenantContext.require();
        int count = outboxEventRepository.markForReplaySince(tenantId, since);
        log.info("Outbox replay requested tenant={} since={} eventsQueued={}", tenantId, since, count);
        return count;
    }

    /**
     * Deliberately not {@code @Transactional}: the fan-out binds each tenant through {@link
     * TenantIterator} and opens that tenant's transaction inside the binding ({@link
     * #markForReplayInOwnTransaction}). One transaction around the whole loop would be checked out
     * before any tenant was bound, and a tenant whose update fails would mark it rollback-only, so
     * the tenants that succeeded would be rolled back too and the call would end in {@code
     * UnexpectedRollbackException}; with one transaction per tenant a failure rolls back that
     * tenant only, the iterator logs it and moves on, and the count covers the tenants that
     * committed.
     */
    @Override
    public int replaySinceForCaller(@NonNull Instant since) {
        UUID caller = TenantContext.require();
        if (!PlatformTenant.isPlatform(caller)) {
            int count = markForReplayInOwnTransaction(caller, since);
            log.info("Outbox replay requested tenant={} since={} eventsQueued={}", caller, since, count);
            return count;
        }
        // The platform tenant owns no workorder rows; an operator bound to it asks for the fleet.
        AtomicInteger queued = new AtomicInteger();
        int tenants = tenantIterator.forEachActiveTenant(
                tenantId -> queued.addAndGet(markForReplayInOwnTransaction(tenantId, since)));
        log.info(
                "Outbox replay requested by a platform operator since={} tenants={} eventsQueued={}",
                since,
                tenants,
                queued.get());
        return queued.get();
    }

    private int markForReplayInOwnTransaction(@NonNull UUID tenantId, @NonNull Instant since) {
        Integer count = perTenantTransaction.execute(_ -> outboxEventRepository.markForReplaySince(tenantId, since));
        return count == null ? 0 : count;
    }

    @Override
    @Transactional
    public int replayBetween(@NonNull Instant since, @NonNull Instant until) {
        UUID tenantId = TenantContext.require();
        int count = outboxEventRepository.markForReplayBetween(tenantId, since, until);
        log.info("Outbox replay requested tenant={} window=[{}, {}) eventsQueued={}", tenantId, since, until, count);
        return count;
    }
}

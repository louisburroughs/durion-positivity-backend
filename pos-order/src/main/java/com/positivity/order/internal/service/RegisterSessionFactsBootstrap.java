package com.positivity.order.internal.service;

import com.positivity.order.internal.config.OrderDomainEventPublisher;
import com.positivity.order.internal.entity.RegisterSession;
import com.positivity.order.internal.entity.RegisterSessionStatus;
import com.positivity.order.internal.repository.RegisterSessionRepository;
import com.positivity.tenancy.TenantIterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Re-emits {@code order.session.opened} for every active (OPEN or CLOSING) register session at each start, per
 * tenant, at the session's current version (CAP:550 S40, #2578; ADR-0044 §4 "re-emit of current state", ADR-0062
 * {@link TenantIterator}). pos-accounting keeps a replica of the sessions per terminal and applies equal versions,
 * so a fact lost before this start (or a session opened before pos-order published the fact) is healed without a
 * replay request. A CLOSED session is never re-emitted: its {@code order.session.closed} fact is the last word.
 *
 * <p>Off with {@code pos.order.session.bootstrap-republish.enabled=false}. Without the Kafka rails the publisher
 * queues nothing, so the sweep is then a read-only no-op.
 */
@Slf4j
@Component
@ConditionalOnProperty(
        name = "pos.order.session.bootstrap-republish.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class RegisterSessionFactsBootstrap implements ApplicationRunner {

    /** The statuses of an active session: it holds its terminal from open until it is CLOSED. */
    private static final List<RegisterSessionStatus> ACTIVE =
            List.of(RegisterSessionStatus.OPEN, RegisterSessionStatus.CLOSING);

    private final TenantIterator tenantIterator;
    private final RegisterSessionRepository sessions;
    private final OrderDomainEventPublisher publisher;
    private final TransactionTemplate transaction;

    public RegisterSessionFactsBootstrap(
            TenantIterator tenantIterator,
            RegisterSessionRepository sessions,
            OrderDomainEventPublisher publisher,
            PlatformTransactionManager transactionManager) {
        this.tenantIterator = tenantIterator;
        this.sessions = sessions;
        this.publisher = publisher;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            republishAll();
        } catch (RuntimeException e) {
            log.error("Register session opened-fact republish failed; continuing startup", e);
        }
    }

    /**
     * Republishes every tenant's active sessions, each tenant in its own transaction.
     *
     * @return the number of active sessions republished (nothing is queued without the Kafka rails)
     */
    public int republishAll() {
        AtomicInteger queued = new AtomicInteger();
        int tenants = tenantIterator.forEachActiveTenant(tenantId -> {
            Integer count = transaction.execute(status -> republishBoundTenant());
            queued.addAndGet(count == null ? 0 : count);
        });
        log.info("Republished order.session.opened for {} active session(s) of {} tenant(s)", queued, tenants);
        return queued.get();
    }

    /** Queues {@code order.session.opened} for each active session of the bound tenant; joins the caller's TX. */
    public int republishBoundTenant() {
        int queued = 0;
        for (RegisterSession session : sessions.findByStatusInOrderByOpenedAtAscSessionIdAsc(ACTIVE)) {
            publisher.publishRegisterSessionOpened(session);
            queued++;
        }
        return queued;
    }
}

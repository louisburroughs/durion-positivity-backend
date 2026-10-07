package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.entity.UndepositedSession;
import com.positivity.accounting.internal.enums.UndepositedSessionStatus;
import com.positivity.accounting.internal.repository.UndepositedSessionRepository;
import com.positivity.tenancy.TenantIterator;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Drawer cash not yet at the bank, as gauges (CAP:550 S18, #2514 "Audit and observability"; feeds the S19 1090-age
 * warning): the bank drops of every undeposited session across all tenants, and the age in days of the oldest. Both are
 * refreshed on a poll, one tenant at a time; a tenant whose read fails is skipped for that poll.
 */
@Slf4j
@Component
@ConditionalOnProperty(
        prefix = "pos.accounting.deposit-gauge",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public class UndepositedCashGauge {

    static final String AMOUNT_GAUGE = "accounting.deposit.undeposited.amount";
    static final String OLDEST_AGE_GAUGE = "accounting.deposit.undeposited.oldest_age_days";

    private final UndepositedSessionRepository sessions;
    private final TenantIterator tenantIterator;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final AtomicReference<BigDecimal> amount = new AtomicReference<>(BigDecimal.ZERO);
    private final AtomicLong oldestAgeDays = new AtomicLong();

    public UndepositedCashGauge(
            UndepositedSessionRepository sessions,
            TenantIterator tenantIterator,
            PlatformTransactionManager transactionManager,
            Clock clock,
            ObjectProvider<MeterRegistry> meterRegistry) {
        this.sessions = sessions;
        this.tenantIterator = tenantIterator;
        this.transaction = new TransactionTemplate(transactionManager);
        this.transaction.setReadOnly(true);
        this.clock = clock;
        MeterRegistry registry = meterRegistry.getIfAvailable();
        if (registry != null) {
            Gauge.builder(AMOUNT_GAUGE, amount, value -> value.get().doubleValue())
                    .description("Bank drops of closed register sessions not yet deposited, across all tenants,"
                            + " as of the last poll")
                    .register(registry);
            Gauge.builder(OLDEST_AGE_GAUGE, oldestAgeDays, AtomicLong::get)
                    .description("Days since the oldest undeposited register session closed, across all tenants,"
                            + " as of the last poll")
                    .register(registry);
        }
    }

    /** One poll over every active tenant. */
    @Scheduled(
            initialDelayString = "${pos.accounting.deposit-gauge.initial-delay-ms:60000}",
            fixedDelayString = "${pos.accounting.deposit-gauge.poll-interval-ms:300000}")
    public void refresh() {
        Instant now = Instant.now(clock);
        AtomicReference<BigDecimal> total = new AtomicReference<>(BigDecimal.ZERO);
        AtomicReference<Instant> oldest = new AtomicReference<>();
        tenantIterator.forEachActiveTenant(tenantId -> transaction.executeWithoutResult(status -> {
            total.accumulateAndGet(
                    sessions.sumDepositAmountByStatus(UndepositedSessionStatus.UNDEPOSITED), BigDecimal::add);
            sessions.findFirstByStatusOrderByClosedAtAsc(UndepositedSessionStatus.UNDEPOSITED)
                    .map(UndepositedSession::getClosedAt)
                    .ifPresent(closedAt -> oldest.accumulateAndGet(
                            closedAt, (current, next) -> current == null || next.isBefore(current) ? next : current));
        }));
        amount.set(total.get());
        oldestAgeDays.set(
                oldest.get() == null ? 0 : Math.max(0, Duration.between(oldest.get(), now).toDays()));
        log.debug("Undeposited drawer cash {} across tenants, oldest {} day(s)", total.get(), oldestAgeDays.get());
    }

    /** The last poll's total, for tests. */
    BigDecimal amount() {
        return amount.get();
    }

    /** The last poll's oldest age in days, for tests. */
    long oldestAgeDays() {
        return oldestAgeDays.get();
    }
}

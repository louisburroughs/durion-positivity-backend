package com.positivity.accounting.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.entity.UndepositedSession;
import com.positivity.accounting.internal.enums.UndepositedSessionStatus;
import com.positivity.accounting.internal.repository.UndepositedSessionRepository;
import com.positivity.tenancy.TenantIterator;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.PlatformTransactionManager;

/** The undeposited drawer cash gauges (CAP:550 S18, #2514): the drops waiting across tenants and the oldest's age. */
@DisplayName("Undeposited drawer cash gauges (#2514)")
class UndepositedCashGaugeTest {

    private static final Instant NOW = Instant.parse("2026-10-09T12:00:00Z");

    @Test
    @DisplayName("sums every tenant's undeposited drops and ages the oldest session across them")
    @SuppressWarnings("unchecked")
    void sumsAcrossTenants() {
        UndepositedSessionRepository sessions = mock(UndepositedSessionRepository.class);
        TenantIterator tenants = mock(TenantIterator.class);
        when(tenants.sweep(any())).thenAnswer(invocation -> {
            Consumer<UUID> work = invocation.getArgument(0);
            work.accept(UUID.randomUUID());
            work.accept(UUID.randomUUID());
            return new TenantIterator.Sweep(2, true);
        });
        when(sessions.sumDepositAmountByStatus(UndepositedSessionStatus.UNDEPOSITED))
                .thenReturn(new BigDecimal("1197.00"), new BigDecimal("300.00"));
        when(sessions.findFirstByStatusOrderByClosedAtAsc(UndepositedSessionStatus.UNDEPOSITED))
                .thenReturn(
                        Optional.of(closedAt("2026-10-06T20:00:00Z")), Optional.of(closedAt("2026-10-02T20:00:00Z")));
        MeterRegistry registry = new SimpleMeterRegistry();
        ObjectProvider<MeterRegistry> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(registry);
        UndepositedCashGauge gauge = new UndepositedCashGauge(
                sessions, tenants, mock(PlatformTransactionManager.class), Clock.fixed(NOW, ZoneOffset.UTC), provider);

        gauge.refresh();

        assertThat(gauge.amount()).isEqualByComparingTo("1497.00");
        assertThat(gauge.oldestAgeDays()).isEqualTo(6);
        assertThat(registry.get(UndepositedCashGauge.AMOUNT_GAUGE).gauge().value())
                .isEqualTo(1497.0);
        assertThat(registry.get(UndepositedCashGauge.OLDEST_AGE_GAUGE).gauge().value())
                .isEqualTo(6.0);
    }

    @Test
    @DisplayName("a tenant whose second read fails adds nothing, and a poll over an incomplete tenant list keeps the"
            + " previous values")
    @SuppressWarnings("unchecked")
    void partialReadsNeverPublishPartialTotals() {
        UndepositedSessionRepository sessions = mock(UndepositedSessionRepository.class);
        TenantIterator tenants = mock(TenantIterator.class);
        when(sessions.sumDepositAmountByStatus(UndepositedSessionStatus.UNDEPOSITED))
                .thenReturn(new BigDecimal("1197.00"), new BigDecimal("300.00"), new BigDecimal("50.00"));
        when(sessions.findFirstByStatusOrderByClosedAtAsc(UndepositedSessionStatus.UNDEPOSITED))
                .thenReturn(Optional.of(closedAt("2026-10-06T20:00:00Z")))
                .thenThrow(new IllegalStateException("simulated read failure"))
                .thenReturn(Optional.of(closedAt("2026-09-01T20:00:00Z")));
        // As TenantIterator.sweep does: a tenant whose work throws is logged and skipped.
        when(tenants.sweep(any()))
                .thenAnswer(invocation -> {
                    Consumer<UUID> work = invocation.getArgument(0);
                    work.accept(UUID.randomUUID());
                    try {
                        work.accept(UUID.randomUUID());
                    } catch (IllegalStateException skipped) {
                        // the second tenant's oldest-row read failed
                    }
                    return new TenantIterator.Sweep(1, true);
                })
                .thenAnswer(invocation -> {
                    Consumer<UUID> work = invocation.getArgument(0);
                    work.accept(UUID.randomUUID());
                    return new TenantIterator.Sweep(1, false);
                });
        ObjectProvider<MeterRegistry> provider = mock(ObjectProvider.class);
        UndepositedCashGauge gauge = new UndepositedCashGauge(
                sessions, tenants, mock(PlatformTransactionManager.class), Clock.fixed(NOW, ZoneOffset.UTC), provider);

        gauge.refresh();
        assertThat(gauge.amount())
                .as("only the tenant whose reads both succeeded")
                .isEqualByComparingTo("1197.00");
        assertThat(gauge.oldestAgeDays()).isEqualTo(2);

        gauge.refresh();
        assertThat(gauge.amount())
                .as("an incomplete tenant list keeps the last values")
                .isEqualByComparingTo("1197.00");
        assertThat(gauge.oldestAgeDays()).isEqualTo(2);
    }

    private static UndepositedSession closedAt(String instant) {
        UndepositedSession session = new UndepositedSession();
        session.setClosedAt(Instant.parse(instant));
        return session;
    }
}

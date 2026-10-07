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
        when(tenants.forEachActiveTenant(any())).thenAnswer(invocation -> {
            Consumer<UUID> work = invocation.getArgument(0);
            work.accept(UUID.randomUUID());
            work.accept(UUID.randomUUID());
            return 2;
        });
        when(sessions.sumDepositAmountByStatus(UndepositedSessionStatus.UNDEPOSITED))
                .thenReturn(new BigDecimal("1197.00"), new BigDecimal("300.00"));
        when(sessions.findFirstByStatusOrderByClosedAtAsc(UndepositedSessionStatus.UNDEPOSITED))
                .thenReturn(Optional.of(closedAt("2026-10-06T20:00:00Z")), Optional.of(closedAt("2026-10-02T20:00:00Z")));
        MeterRegistry registry = new SimpleMeterRegistry();
        ObjectProvider<MeterRegistry> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(registry);
        UndepositedCashGauge gauge = new UndepositedCashGauge(
                sessions,
                tenants,
                mock(PlatformTransactionManager.class),
                Clock.fixed(NOW, ZoneOffset.UTC),
                provider);

        gauge.refresh();

        assertThat(gauge.amount()).isEqualByComparingTo("1497.00");
        assertThat(gauge.oldestAgeDays()).isEqualTo(6);
        assertThat(registry.get(UndepositedCashGauge.AMOUNT_GAUGE).gauge().value()).isEqualTo(1497.0);
        assertThat(registry.get(UndepositedCashGauge.OLDEST_AGE_GAUGE).gauge().value())
                .isEqualTo(6.0);
    }

    private static UndepositedSession closedAt(String instant) {
        UndepositedSession session = new UndepositedSession();
        session.setClosedAt(Instant.parse(instant));
        return session;
    }
}

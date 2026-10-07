package com.positivity.accounting.internal.bankrec.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.positivity.accounting.internal.bankrec.repository.BankReconciliationRepository;
import com.positivity.accounting.internal.exception.AccountingTimeZoneUnsetException;
import com.positivity.accounting.internal.service.TestZoneResolvers;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Outstanding items age from {@link ReconciliationSupport#today()} (ReconciliationOutstandingItemServiceImpl), which is
 * the tenant's accounting-calendar today (#2558 ruling), not the UTC clock's.
 */
@DisplayName("ReconciliationSupport.today(): the tenant's accounting calendar (#2558)")
class ReconciliationSupportTodayTest {

    private static final Clock UTC = Clock.fixed(TestZoneResolvers.JAN_31_2330_CHICAGO, ZoneOffset.UTC);

    private ReconciliationSupport support(com.positivity.accounting.internal.service.AccountingCalendarZoneResolver r) {
        return new ReconciliationSupport(
                mock(BankReconciliationRepository.class), mock(ReconciliationCalculator.class), UTC, r);
    }

    @Test
    @DisplayName("at 2026-01-31T23:30-06:00 today is Jan 31 in a Chicago calendar, not the UTC clock's Feb 1")
    void todayIsTheTenantCalendarDay() {
        assertThat(support(TestZoneResolvers.fixed(TestZoneResolvers.CHICAGO, UTC))
                        .today())
                .isEqualTo(LocalDate.of(2026, 1, 31));
        assertThat(new BankRecCalendar(TestZoneResolvers.fixed(TestZoneResolvers.CHICAGO, UTC)).today())
                .isEqualTo(LocalDate.of(2026, 1, 31));
    }

    @Test
    @DisplayName("without an accounting time zone it refuses with ACCOUNTING_TIME_ZONE_UNSET (422), not a 500")
    void unsetZoneRefuses() {
        assertThatThrownBy(() -> support(TestZoneResolvers.unset(UTC)).today())
                .isInstanceOf(AccountingTimeZoneUnsetException.class);
    }
}

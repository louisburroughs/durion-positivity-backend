package com.positivity.accounting.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.entity.AccountingConfiguration;
import com.positivity.accounting.internal.exception.AccountingTimeZoneUnsetException;
import com.positivity.accounting.internal.repository.AccountingConfigurationRepository;
import com.positivity.tenancy.TenantResolver;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("AccountingCalendarZoneResolver: the tenant's accounting-calendar zone (#2558)")
class AccountingCalendarZoneResolverTest {

    private static final UUID TENANT = UUID.fromString("01900000-0000-7000-8000-0000000000b1");
    private static final ZoneId CHICAGO = ZoneId.of("America/Chicago");

    /** 2026-01-31T23:30-06:00: still January in Chicago, already February in UTC. */
    private static final Instant JAN_31_2330_CHICAGO = Instant.parse("2026-02-01T05:30:00Z");

    private final AccountingConfigurationRepository repository = mock(AccountingConfigurationRepository.class);
    private final TenantResolver tenantResolver = mock(TenantResolver.class);
    private AccountingCalendarZoneResolver resolver;

    @BeforeEach
    void setUp() {
        when(tenantResolver.require()).thenReturn(TENANT);
        resolver = new AccountingCalendarZoneResolver(
                repository, tenantResolver, Clock.fixed(JAN_31_2330_CHICAGO, ZoneOffset.UTC));
    }

    private void stored(String value) {
        AccountingConfiguration row = new AccountingConfiguration();
        row.setConfigKey(AccountingCalendarZoneResolver.CONFIG_KEY);
        row.setConfigValue(value);
        when(repository.findByConfigKey(AccountingCalendarZoneResolver.CONFIG_KEY))
                .thenReturn(Optional.of(row));
    }

    @Test
    @DisplayName("dates an instant in the tenant's zone, not the UTC clock's")
    void postingDateUsesTheTenantZone() {
        stored("America/Chicago");

        assertThat(resolver.zoneFor(TENANT)).isEqualTo(CHICAGO);
        assertThat(resolver.postingDate(JAN_31_2330_CHICAGO)).isEqualTo(LocalDate.of(2026, 1, 31));
        assertThat(resolver.postingDate(JAN_31_2330_CHICAGO, TENANT)).isEqualTo(LocalDate.of(2026, 1, 31));
        assertThat(resolver.postingDateTime(JAN_31_2330_CHICAGO)).isEqualTo(LocalDateTime.of(2026, 1, 31, 23, 30));
        assertThat(resolver.today()).isEqualTo(LocalDate.of(2026, 1, 31));
        assertThat(resolver.currentMonth()).isEqualTo(YearMonth.of(2026, 1));
    }

    @Test
    @DisplayName("a missing row fails closed: no UTC or system-default fallback")
    void missingRowFailsClosed() {
        when(repository.findByConfigKey(AccountingCalendarZoneResolver.CONFIG_KEY))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> resolver.postingDate(JAN_31_2330_CHICAGO))
                .isInstanceOf(AccountingTimeZoneUnsetException.class);
        assertThatThrownBy(resolver::currentMonth).isInstanceOf(AccountingTimeZoneUnsetException.class);
        assertThat(resolver.find()).isEmpty();
    }

    @Test
    @DisplayName("an unreadable stored value counts as unset, never as a guessed zone")
    void unreadableValueIsUnset() {
        stored("Not/AZone");

        assertThatThrownBy(resolver::zone).isInstanceOf(AccountingTimeZoneUnsetException.class);
    }

    @Test
    @DisplayName("a held (unposted) row is dated in UTC, stated explicitly, when the zone is unset")
    void heldRecordFallsBackToExplicitUtc() {
        when(repository.findByConfigKey(AccountingCalendarZoneResolver.CONFIG_KEY))
                .thenReturn(Optional.empty());

        assertThat(resolver.heldRecordDateTime(JAN_31_2330_CHICAGO)).isEqualTo(LocalDateTime.of(2026, 2, 1, 5, 30));
    }

    @Test
    @DisplayName("caches the zone per tenant until evicted; a missing row is never cached")
    void cachesUntilEvicted() {
        when(repository.findByConfigKey(AccountingCalendarZoneResolver.CONFIG_KEY))
                .thenReturn(Optional.empty());
        assertThatThrownBy(resolver::zone).isInstanceOf(AccountingTimeZoneUnsetException.class);

        stored("America/Chicago");
        assertThat(resolver.zone()).isEqualTo(CHICAGO);
        stored("UTC");
        assertThat(resolver.zone()).as("cached").isEqualTo(CHICAGO);

        resolver.evict(TENANT);
        assertThat(resolver.zone()).isEqualTo(ZoneId.of("UTC"));
        verify(repository, times(3)).findByConfigKey(AccountingCalendarZoneResolver.CONFIG_KEY);
    }

    @Test
    @DisplayName("refuses a tenant other than the bound one")
    void refusesAnotherTenant() {
        stored("America/Chicago");

        assertThatThrownBy(() -> resolver.zoneFor(UUID.randomUUID())).isInstanceOf(IllegalStateException.class);
    }
}

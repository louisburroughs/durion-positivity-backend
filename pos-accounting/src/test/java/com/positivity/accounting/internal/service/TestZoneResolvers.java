package com.positivity.accounting.internal.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import com.positivity.accounting.internal.entity.AccountingConfiguration;
import com.positivity.accounting.internal.repository.AccountingConfigurationRepository;
import com.positivity.tenancy.TenantResolver;
import java.time.Clock;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;
import org.mockito.MockSettings;
import org.mockito.quality.Strictness;

/** Real {@link AccountingCalendarZoneResolver}s over a stubbed configuration row, for unit tests (#2558). */
public final class TestZoneResolvers {

    /** The tenant the stubbed resolvers report as bound. */
    public static final UUID TENANT = UUID.fromString("01900000-0000-7000-8000-0000000000aa");

    /** The stubs are used only by the tests that reach a date; the others must not fail on them. */
    private static final MockSettings LENIENT = withSettings().strictness(Strictness.LENIENT);

    private TestZoneResolvers() {}

    /** A resolver whose tenant's accounting time zone is {@code zone}. */
    public static AccountingCalendarZoneResolver fixed(ZoneId zone, Clock clock) {
        AccountingConfiguration row = new AccountingConfiguration();
        row.setConfigKey(AccountingCalendarZoneResolver.CONFIG_KEY);
        row.setConfigValue(zone.getId());
        return resolver(Optional.of(row), clock);
    }

    /** A UTC resolver, the zone every tenant is seeded with. */
    public static AccountingCalendarZoneResolver utc(Clock clock) {
        return fixed(ZoneId.of("UTC"), clock);
    }

    /** A resolver whose tenant has no accounting time zone. */
    public static AccountingCalendarZoneResolver unset(Clock clock) {
        return resolver(Optional.empty(), clock);
    }

    private static AccountingCalendarZoneResolver resolver(Optional<AccountingConfiguration> row, Clock clock) {
        AccountingConfigurationRepository repository = mock(AccountingConfigurationRepository.class, LENIENT);
        when(repository.findByConfigKey(any())).thenReturn(row);
        TenantResolver tenantResolver = mock(TenantResolver.class, LENIENT);
        when(tenantResolver.require()).thenReturn(TENANT);
        return new AccountingCalendarZoneResolver(repository, tenantResolver, clock);
    }
}

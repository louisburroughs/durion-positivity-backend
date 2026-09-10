package com.positivity.people.internal.service;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.people.internal.dto.TimePeriodRolloverResult;
import com.positivity.tenancy.StaticTenantRegistry;
import com.positivity.tenancy.TenancyProperties;
import com.positivity.tenancy.TenantIterator;
import com.positivity.tenancy.testing.TenantTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Scheduled pay-period rollover driver (#1527). */
@ExtendWith(MockitoExtension.class)
@DisplayName("TimePeriodRolloverScheduler")
class TimePeriodRolloverSchedulerTest {

    @Mock
    private TimePeriodManagementService timePeriodManagementService;

    private TimePeriodRolloverScheduler scheduler;

    @BeforeEach
    void createScheduler() {
        // One active tenant, the alpha default, for the per-tenant sweep (ADR-0062).
        TenancyProperties tenancy = new TenancyProperties();
        tenancy.setDefaultTenantId(TenantTestSupport.TENANT_A);
        scheduler = new TimePeriodRolloverScheduler(
                timePeriodManagementService, new TenantIterator(new StaticTenantRegistry(tenancy)));
    }

    @Test
    @DisplayName("delegates one pass to the management service")
    void delegatesToService() {
        when(timePeriodManagementService.runRollover())
                .thenReturn(TimePeriodRolloverResult.builder().build());

        scheduler.runScheduledRollover();

        verify(timePeriodManagementService).runRollover();
    }

    @Test
    @DisplayName("swallows a failing pass so the next scheduled run still happens")
    void swallowsFailures() {
        when(timePeriodManagementService.runRollover()).thenThrow(new IllegalStateException("boom"));

        scheduler.runScheduledRollover();

        verify(timePeriodManagementService).runRollover();
    }
}

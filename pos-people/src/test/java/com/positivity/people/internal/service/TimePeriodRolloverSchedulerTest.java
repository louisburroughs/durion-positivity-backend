package com.positivity.people.internal.service;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.people.internal.dto.TimePeriodRolloverResult;
import com.positivity.people.service.TimePeriodManagementService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Scheduled pay-period rollover driver (#1527). */
@ExtendWith(MockitoExtension.class)
@DisplayName("TimePeriodRolloverScheduler")
class TimePeriodRolloverSchedulerTest {

    @Mock
    private TimePeriodManagementService timePeriodManagementService;

    @InjectMocks
    private TimePeriodRolloverScheduler scheduler;

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

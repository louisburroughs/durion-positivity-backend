package com.positivity.workorder.internal.scheduled;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.workorder.internal.service.EstimateService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.annotation.Scheduled;

@ExtendWith(MockitoExtension.class)
class ApprovalExpirationJobTest {

    @Mock
    private EstimateService estimateService;

    @Test
    void expirePendingApprovals_delegatesToTheEstimateService() {
        when(estimateService.expirePendingApprovals()).thenReturn(3);

        new ApprovalExpirationJob(estimateService).expirePendingApprovals();

        verify(estimateService).expirePendingApprovals();
    }

    /**
     * The job must poll on an interval, not on a calendar cron.
     *
     * <p>A cron trigger fires against the scheduler's real clock. Under the accelerated clock a
     * single real hour spans weeks of virtual time, so an hourly cron would leave estimates sitting
     * in PENDING_APPROVAL for virtual weeks past their own expiry. An interval poller re-reads the
     * injected clock every pass and expires whatever has come due since the last one, however many
     * virtual boundaries that spans.
     */
    @Test
    void expirePendingApprovals_pollsOnAnIntervalRatherThanACalendarCron() throws NoSuchMethodException {
        Scheduled scheduled =
                ApprovalExpirationJob.class.getMethod("expirePendingApprovals").getAnnotation(Scheduled.class);

        assertThat(scheduled).isNotNull();
        assertThat(scheduled.cron()).isEmpty();
        assertThat(scheduled.fixedDelayString()).isNotEmpty();
    }
}

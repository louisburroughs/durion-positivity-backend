package com.positivity.supplier.internal.invoice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.supplier.internal.domain.model.SupplierCapability;
import com.positivity.supplier.internal.domain.model.SupplierRef;
import com.positivity.supplier.internal.entity.SupplierEndpointBindingEntity;
import com.positivity.supplier.internal.entity.SupplierProfileEntity;
import com.positivity.supplier.internal.entity.SupplierScheduleLeaseEntity;
import com.positivity.supplier.internal.exception.InvoiceFetchException;
import com.positivity.supplier.internal.repository.SupplierEndpointBindingRepository;
import com.positivity.supplier.internal.repository.SupplierProfileRepository;
import com.positivity.supplier.internal.repository.SupplierScheduleLeaseRepository;
import com.positivity.supplier.internal.service.SupplierScheduleCoordinator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Scheduling the vendor invoice fetch (CAP-321 #1343).
 *
 * <p>Two properties carry this class, and both are about invoices that would otherwise disappear
 * silently. The window overlaps its predecessor, so an invoice dated just before the last
 * checkpoint is still caught. And the checkpoint moves only when a window completes — advance it
 * over a window that failed halfway and no later run will ever ask for those dates again.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("InvoiceFetchScheduler — windows that do not lose invoices (#1343)")
class InvoiceFetchSchedulerTest {

    private static final UUID BINDING_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f8a01");
    private static final UUID PROFILE_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f8a02");
    private static final Instant NOW = Instant.parse("2026-08-16T12:00:00Z");
    private static final Duration OVERLAP = Duration.ofDays(2);
    private static final Duration INITIAL_LOOKBACK = Duration.ofDays(30);

    @Mock
    private SupplierEndpointBindingRepository bindingRepository;

    @Mock
    private SupplierProfileRepository profileRepository;

    @Mock
    private SupplierScheduleLeaseRepository leaseRepository;

    @Mock
    private SupplierScheduleCoordinator scheduleCoordinator;

    @Mock
    private InvoiceFetchRunner fetchRunner;

    private InvoiceFetchScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new InvoiceFetchScheduler(
                bindingRepository,
                profileRepository,
                leaseRepository,
                scheduleCoordinator,
                fetchRunner,
                Clock.fixed(NOW, ZoneOffset.UTC),
                OVERLAP,
                INITIAL_LOOKBACK);

        SupplierEndpointBindingEntity binding = newBinding();
        when(bindingRepository.findByCapabilityAndEnabledTrueAndScheduleCronIsNotNull(SupplierCapability.INVOICE_FETCH))
                .thenReturn(List.of(binding));

        SupplierProfileEntity profile = new SupplierProfileEntity();
        profile.setVendorProfileId(PROFILE_ID);
        profile.setSupplierRef("michelin-de");
        profile.setEnabled(true);
        when(profileRepository.findById(PROFILE_ID)).thenReturn(Optional.of(profile));

        when(scheduleCoordinator.tryClaim(eq(BINDING_ID), any())).thenReturn(true);
        when(leaseRepository.findByBindingIdIn(List.of(BINDING_ID))).thenReturn(List.of());
    }

    /** Runs the page the scheduler hands the coordinator, as the real coordinator would. */
    private void runPagesImmediately() {
        org.mockito.Mockito.doAnswer(call -> {
                    SupplierScheduleCoordinator.SchedulePage page = call.getArgument(2);
                    page.process();
                    return null;
                })
                .when(scheduleCoordinator)
                .runPage(any(), any(), any());
    }

    private LocalDate capturedFromDate() {
        ArgumentCaptor<LocalDate> from = ArgumentCaptor.forClass(LocalDate.class);
        verify(fetchRunner).fetchWindow(any(), from.capture(), any());
        return from.getValue();
    }

    @Test
    @DisplayName("the window reaches back past the checkpoint by the overlap")
    void windowOverlapsThePreviousOne() {
        SupplierScheduleLeaseEntity lease = new SupplierScheduleLeaseEntity();
        lease.setCheckpointAt(Instant.parse("2026-08-14T12:00:00Z"));
        when(leaseRepository.findByBindingIdIn(List.of(BINDING_ID))).thenReturn(List.of(lease));
        runPagesImmediately();

        scheduler.runDueFetches();

        // A vendor may date an invoice a day or two before it becomes fetchable. Starting exactly
        // where the last window ended would step straight over it, and nothing would ever look
        // there again.
        assertThat(capturedFromDate()).isEqualTo(LocalDate.of(2026, 8, 12));
    }

    @Test
    @DisplayName("a binding with no checkpoint starts from the bounded initial lookback")
    void firstFetchIsBounded() {
        runPagesImmediately();

        scheduler.runDueFetches();

        // Bounded rather than unlimited: an unbounded first fetch against a vendor with years of
        // history would time out, and a timed-out first fetch never establishes a checkpoint, so it
        // would time out again forever.
        assertThat(capturedFromDate()).isEqualTo(LocalDate.of(2026, 7, 17));
    }

    @Test
    @DisplayName("the checkpoint advances only through the page, and only on success")
    void checkpointAdvancesOnlyWithCompletedWork() {
        runPagesImmediately();

        scheduler.runDueFetches();

        // The scheduler never advances the checkpoint itself. It hands the coordinator a page,
        // which advances it in the same transaction as the work — so a failure rolls both back.
        verify(scheduleCoordinator).runPage(eq(BINDING_ID), any(), any());
        verify(leaseRepository, never()).advanceCheckpoint(any(), any(), any());
    }

    @Test
    @DisplayName("a failed window leaves the checkpoint where it was")
    void failedWindowDoesNotAdvanceTheCheckpoint() {
        when(fetchRunner.fetchWindow(any(), any(), any())).thenThrow(new InvoiceFetchException("vendor unreachable"));
        org.mockito.Mockito.doAnswer(call -> {
                    SupplierScheduleCoordinator.SchedulePage page = call.getArgument(2);
                    // The real coordinator advances the checkpoint after the page returns. A page
                    // that throws never reaches that line, and the surrounding transaction rolls
                    // back — which is exactly the behaviour being relied on here.
                    page.process();
                    throw new AssertionError("the page returned despite the fetch failing");
                })
                .when(scheduleCoordinator)
                .runPage(any(), any(), any());

        // Swallowed at the top of the tick so one bad vendor does not stop the others; the point is
        // that the checkpoint was never touched.
        scheduler.runDueFetches();

        verify(leaseRepository, never()).advanceCheckpoint(any(), any(), any());
    }

    @Test
    @DisplayName("the lease is released whether the window succeeded or not")
    void leaseIsAlwaysReleased() {
        when(fetchRunner.fetchWindow(any(), any(), any())).thenThrow(new InvoiceFetchException("vendor unreachable"));
        runPagesImmediately();

        scheduler.runDueFetches();

        // A lease held by a run that died would lock the vendor out until it expired.
        ArgumentCaptor<String> outcome = ArgumentCaptor.forClass(String.class);
        verify(scheduleCoordinator).release(eq(BINDING_ID), any(), outcome.capture());
        assertThat(outcome.getValue()).isEqualTo("FAILED");
    }

    @Test
    @DisplayName("a binding another instance holds is left alone")
    void unclaimedBindingIsSkipped() {
        when(scheduleCoordinator.tryClaim(eq(BINDING_ID), any())).thenReturn(false);

        scheduler.runDueFetches();

        // Not an error: exactly one fetcher per vendor is the point of the lease.
        verify(fetchRunner, never()).fetchWindow(any(), any(), any());
    }

    @Test
    @DisplayName("a disabled profile is not fetched for")
    void disabledProfileIsSkipped() {
        SupplierProfileEntity disabled = new SupplierProfileEntity();
        disabled.setVendorProfileId(PROFILE_ID);
        disabled.setSupplierRef("michelin-de");
        disabled.setEnabled(false);
        when(profileRepository.findById(PROFILE_ID)).thenReturn(Optional.of(disabled));

        scheduler.runDueFetches();

        verify(scheduleCoordinator, never()).tryClaim(any(), any());
        verify(fetchRunner, never()).fetchWindow(any(), any(), any());
    }

    @Test
    @DisplayName("an on-demand fetch runs the same path and leaves the checkpoint alone")
    void onDemandDoesNotMoveTheCheckpoint() {
        when(fetchRunner.fetchWindow(any(), any(), any())).thenReturn(new InvoiceFetchRunner.FetchOutcome(5, 3));

        InvoiceFetchRunner.FetchOutcome outcome = scheduler.fetchOnDemand(
                new SupplierRef("michelin-de"), LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 15));

        // Both numbers: five sent, three new. A window that fetches and imports nothing new is
        // working, and only the pair makes that legible.
        assertThat(outcome.fetched()).isEqualTo(5);
        assertThat(outcome.imported()).isEqualTo(3);
        verify(fetchRunner).fetchWindow(any(), eq(LocalDate.of(2026, 7, 1)), eq(LocalDate.of(2026, 7, 15)));
        // Moving the checkpoint to satisfy an investigation would skip everything between the
        // queried window and where the schedule had actually reached.
        verify(scheduleCoordinator, never()).runPage(any(), any(), any());
        verify(leaseRepository, never()).advanceCheckpoint(any(), any(), any());
    }

    @Test
    @DisplayName("one unreachable vendor does not stop the others")
    void oneVendorFailureDoesNotStopTheTick() {
        SupplierEndpointBindingEntity second = new SupplierEndpointBindingEntity();
        second.setId(UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f8a09"));
        second.setVendorProfileId(PROFILE_ID);
        second.setScheduleCron("0 0 * * * *");
        when(bindingRepository.findByCapabilityAndEnabledTrueAndScheduleCronIsNotNull(SupplierCapability.INVOICE_FETCH))
                .thenReturn(List.of(newBinding(), second));
        when(scheduleCoordinator.tryClaim(any(), any())).thenReturn(true);
        runPagesImmediately();
        when(fetchRunner.fetchWindow(any(), any(), any()))
                .thenThrow(new InvoiceFetchException("vendor unreachable"))
                .thenReturn(new InvoiceFetchRunner.FetchOutcome(1, 1));

        scheduler.runDueFetches();

        // The second vendor's invoices must not be held hostage by the first vendor's outage.
        verify(fetchRunner, org.mockito.Mockito.times(2)).fetchWindow(any(), any(), any());
    }

    private static SupplierEndpointBindingEntity newBinding() {
        SupplierEndpointBindingEntity binding = new SupplierEndpointBindingEntity();
        binding.setId(BINDING_ID);
        binding.setVendorProfileId(PROFILE_ID);
        // Hourly. The poll tick is how often the scheduler looks; this is how often the vendor is
        // actually asked.
        binding.setScheduleCron("0 0 * * * *");
        return binding;
    }

    @Test
    @DisplayName("a binding whose cadence has not come round is not fetched")
    void bindingNotYetDueIsSkipped() {
        SupplierScheduleLeaseEntity lease = new SupplierScheduleLeaseEntity();
        // Checkpointed at the top of this hour, against an hourly cron: the next firing is an hour
        // away.
        lease.setCheckpointAt(NOW);
        when(leaseRepository.findByBindingIdIn(List.of(BINDING_ID))).thenReturn(List.of(lease));

        scheduler.runDueFetches();

        // The poll tick is how often the scheduler looks, not how often a vendor is asked. Without
        // this the vendor would be asked for the same fortnight every five minutes.
        verify(scheduleCoordinator, never()).tryClaim(any(), any());
        verify(fetchRunner, never()).fetchWindow(any(), any(), any());
    }

    @Test
    @DisplayName("a binding whose cadence has come round is fetched")
    void bindingDueIsFetched() {
        SupplierScheduleLeaseEntity lease = new SupplierScheduleLeaseEntity();
        lease.setCheckpointAt(NOW.minus(Duration.ofHours(3)));
        when(leaseRepository.findByBindingIdIn(List.of(BINDING_ID))).thenReturn(List.of(lease));
        runPagesImmediately();

        scheduler.runDueFetches();

        verify(fetchRunner).fetchWindow(any(), any(), any());
    }

    @Test
    @DisplayName("an unparseable cron stops that binding rather than firing it every tick")
    void unparseableCronDoesNotFire() {
        SupplierEndpointBindingEntity broken = newBinding();
        broken.setScheduleCron("not a cron");
        when(bindingRepository.findByCapabilityAndEnabledTrueAndScheduleCronIsNotNull(SupplierCapability.INVOICE_FETCH))
                .thenReturn(List.of(broken));

        scheduler.runDueFetches();

        // Failing closed rather than open: a misconfigured cron that fired every tick would hammer
        // a vendor, and the log line is what an operator has to notice instead.
        verify(fetchRunner, never()).fetchWindow(any(), any(), any());
    }
}

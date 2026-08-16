package com.positivity.supplier.internal.invoice.service;

import com.positivity.supplier.internal.domain.model.SupplierCapability;
import com.positivity.supplier.internal.domain.model.SupplierRef;
import com.positivity.supplier.internal.entity.SupplierEndpointBindingEntity;
import com.positivity.supplier.internal.entity.SupplierProfileEntity;
import com.positivity.supplier.internal.entity.SupplierScheduleLeaseEntity;
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
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Runs the vendor invoice fetch on a schedule (CAP-321 #1343).
 *
 * <h2>The window deliberately overlaps its predecessor</h2>
 *
 * Each run asks for everything since the last committed checkpoint, minus an overlap. That looks
 * wasteful and is not: a vendor may date an invoice a day or two before it becomes fetchable, and a
 * window that started exactly where the last one ended would step straight over it. Re-asking is
 * cheap because the importer recognises an invoice it already holds by the vendor's own identity —
 * the overlap costs a few duplicate rows in a response and buys immunity to the seam.
 *
 * <h2>The checkpoint moves only when a window completes</h2>
 *
 * Not when the vendor answers, and not when some invoices are imported. The fetch runs inside
 * {@link SupplierScheduleCoordinator#runPage}, which advances the checkpoint in the same
 * transaction as the work, so a failure rolls both back and the next run re-covers the same ground.
 *
 * <p>That ordering is the whole reason this class is careful. Advance a checkpoint over a window
 * that failed halfway and the invoices in the unfetched remainder are gone for good: no later run
 * will ever ask for those dates again, nothing reports them missing, and the first anyone knows is
 * a vendor asking why an invoice has not been paid.
 */
@Slf4j
@Service
public class InvoiceFetchScheduler {

    private final SupplierEndpointBindingRepository bindingRepository;
    private final SupplierProfileRepository profileRepository;
    private final SupplierScheduleLeaseRepository leaseRepository;
    private final SupplierScheduleCoordinator scheduleCoordinator;
    private final InvoiceFetchRunner fetchRunner;
    private final Clock clock;

    /**
     * How far back each window reaches beyond the last checkpoint.
     *
     * <p>Configurable and stated, rather than a constant buried in the window arithmetic, because
     * the right value depends on how long a given vendor takes to make an issued invoice
     * retrievable — and that is only learned from a live vendor.
     */
    private final Duration overlap;

    /**
     * How far back the first ever fetch reaches, when a binding has no checkpoint.
     *
     * <p>Bounded rather than unlimited: an unbounded first fetch against a vendor with years of
     * history would time out, and a timed-out first fetch never establishes a checkpoint, so it
     * would time out again forever.
     */
    private final Duration initialLookback;

    public InvoiceFetchScheduler(
            SupplierEndpointBindingRepository bindingRepository,
            SupplierProfileRepository profileRepository,
            SupplierScheduleLeaseRepository leaseRepository,
            SupplierScheduleCoordinator scheduleCoordinator,
            InvoiceFetchRunner fetchRunner,
            Clock clock,
            @Value("${pos.supplier.invoice.window-overlap:P2D}") Duration overlap,
            @Value("${pos.supplier.invoice.initial-lookback:P30D}") Duration initialLookback) {
        this.bindingRepository = bindingRepository;
        this.profileRepository = profileRepository;
        this.leaseRepository = leaseRepository;
        this.scheduleCoordinator = scheduleCoordinator;
        this.fetchRunner = fetchRunner;
        this.clock = clock;
        this.overlap = overlap;
        this.initialLookback = initialLookback;
    }

    /** Polls due bindings. Each runs at most once per tick, on at most one instance. */
    @Scheduled(fixedDelayString = "${pos.supplier.invoice.poll-interval-ms:300000}")
    public void runDueFetches() {
        Instant now = Instant.now(clock);
        for (SupplierEndpointBindingEntity binding :
                bindingRepository.findByCapabilityAndEnabledTrueAndScheduleCronIsNotNull(
                        SupplierCapability.INVOICE_FETCH)) {
            try {
                runIfConfigured(binding, now);
            } catch (Exception e) {
                // One unreachable vendor must not stop the others being invoiced. This binding's
                // checkpoint stayed where it was, so its window is re-covered next tick.
                log.warn("Scheduled invoice fetch for binding {} failed: {}", binding.getId(), e.toString(), e);
            }
        }
    }

    private void runIfConfigured(SupplierEndpointBindingEntity binding, Instant now) {
        Optional<SupplierProfileEntity> profile = profileRepository.findById(binding.getVendorProfileId());
        if (profile.isEmpty() || !profile.get().isEnabled()) {
            return;
        }

        String ownerToken = SupplierScheduleCoordinator.newOwnerToken();
        if (!scheduleCoordinator.tryClaim(binding.getId(), ownerToken)) {
            // Another instance holds this binding. Not an error: exactly one fetcher per vendor is
            // the point of the lease.
            return;
        }

        String outcome = "FAILED";
        try {
            Instant windowStart = windowStartFor(binding.getId(), now);
            fetchWindowAsPage(binding, profile.get(), ownerToken, windowStart, now);
            outcome = "SUCCEEDED";
        } finally {
            scheduleCoordinator.release(binding.getId(), ownerToken, outcome);
        }
    }

    /**
     * Runs one window as a schedule page, so the checkpoint advances only with the work.
     *
     * <p>The page reports {@code now} as the window it completed. It is reached only if the fetch
     * returned without throwing, which is what ties the checkpoint to a window actually covered
     * rather than one merely attempted.
     */
    private void fetchWindowAsPage(
            SupplierEndpointBindingEntity binding,
            SupplierProfileEntity profile,
            String ownerToken,
            Instant windowStart,
            Instant now) {
        SupplierRef supplierRef = new SupplierRef(profile.getSupplierRef());
        LocalDate fromDate = LocalDate.ofInstant(windowStart, ZoneOffset.UTC);
        LocalDate toDate = LocalDate.ofInstant(now, ZoneOffset.UTC);

        scheduleCoordinator.runPage(binding.getId(), ownerToken, () -> {
            fetchRunner.fetchWindow(supplierRef, fromDate, toDate);
            return now;
        });
    }

    /**
     * Where this window starts: the last committed checkpoint less the overlap, or the initial
     * lookback when the binding has never completed one.
     */
    private Instant windowStartFor(UUID bindingId, Instant now) {
        List<SupplierScheduleLeaseEntity> leases = leaseRepository.findByBindingIdIn(List.of(bindingId));
        Instant checkpoint = leases.stream()
                .map(SupplierScheduleLeaseEntity::getCheckpointAt)
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElse(null);
        if (checkpoint == null) {
            return now.minus(initialLookback);
        }
        return checkpoint.minus(overlap);
    }

    /**
     * Fetches an explicit window on demand, outside the schedule.
     *
     * <p>Runs the same code as the scheduled path but does <em>not</em> touch the checkpoint. An
     * operator asking about a particular fortnight is investigating, not redefining where the
     * schedule has got to — and moving the checkpoint to satisfy a query would skip everything
     * between it and the schedule's real position.
     *
     * @return how many invoices were new
     */
    public int fetchOnDemand(@NonNull SupplierRef supplierRef, @NonNull LocalDate fromDate, @NonNull LocalDate toDate) {
        log.info("On-demand invoice fetch for {} over {}..{}", supplierRef.value(), fromDate, toDate);
        return fetchRunner.fetchWindow(supplierRef, fromDate, toDate);
    }
}

package com.positivity.supplier.internal.stockreport.service;

import com.positivity.supplier.internal.domain.model.SupplierCapability;
import com.positivity.supplier.internal.domain.model.SupplierRef;
import com.positivity.supplier.internal.entity.StockSnapshotEntity;
import com.positivity.supplier.internal.entity.SupplierEndpointBindingEntity;
import com.positivity.supplier.internal.entity.SupplierProfileEntity;
import com.positivity.supplier.internal.repository.StockSnapshotRepository;
import com.positivity.supplier.internal.repository.SupplierEndpointBindingRepository;
import com.positivity.supplier.internal.repository.SupplierProfileRepository;
import com.positivity.supplier.internal.service.SupplierScheduleCoordinator;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;

/**
 * Fires scheduled stock-report snapshots for bindings that carry a cron (CAP-322 #1228).
 *
 * <p>Same shape as the PRICAT scheduler and for the same reasons: a poll re-reads binding
 * configuration every cycle so a retimed or disabled binding takes effect without a restart, the
 * schedule lease keeps two instances from fetching the same snapshot twice, and the coordinator's
 * checkpointed page mechanism is deliberately unused because a stock report is a whole snapshot
 * rather than a window — there is nothing to advance a checkpoint over.
 *
 * <p>"When did this last run" is answered by the snapshot table, which is the real record.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "pos.supplier.stockreport", name = "scheduler-enabled", matchIfMissing = true)
public class StockReportScheduler {

    private final SupplierEndpointBindingRepository bindingRepository;
    private final SupplierProfileRepository profileRepository;
    private final StockSnapshotRepository snapshotRepository;
    private final SupplierScheduleCoordinator scheduleCoordinator;
    private final StockReportImporter importer;
    private final Clock clock;

    /** Polls due bindings. Each runs at most once per tick, on at most one instance. */
    @Scheduled(fixedDelayString = "${pos.supplier.stockreport.poll-interval-ms:60000}")
    public void runDueSnapshots() {
        Instant now = Instant.now(clock);
        for (SupplierEndpointBindingEntity binding :
                bindingRepository.findByCapabilityAndEnabledTrueAndScheduleCronIsNotNull(
                        SupplierCapability.STOCK_REPORT)) {
            try {
                runIfDue(binding, now);
            } catch (Exception e) {
                // One unreachable vendor must not stop the others from refreshing their snapshots.
                log.warn("Scheduled stock report for binding {} failed: {}", binding.getId(), e.toString(), e);
            }
        }
    }

    private void runIfDue(SupplierEndpointBindingEntity binding, Instant now) {
        Optional<SupplierProfileEntity> profile = profileRepository.findById(binding.getVendorProfileId());
        if (profile.isEmpty() || !profile.get().isEnabled()) {
            return;
        }
        if (!isDue(binding, now)) {
            return;
        }

        String ownerToken = SupplierScheduleCoordinator.newOwnerToken();
        if (!scheduleCoordinator.tryClaim(binding.getId(), ownerToken)) {
            return;
        }
        String outcome = "FAILED";
        try {
            StockSnapshotEntity result =
                    importer.runSnapshot(new SupplierRef(profile.get().getSupplierRef()));
            outcome = result.getStatus();
            log.info(
                    "Scheduled stock snapshot {} for profile {} finished with status {}",
                    result.getSnapshotId(),
                    result.getVendorProfileId(),
                    result.getStatus());
        } finally {
            // Released whatever happened: holding the lease after a failure would block the next
            // scheduled attempt until it expired.
            scheduleCoordinator.release(binding.getId(), ownerToken, outcome);
        }
    }

    private boolean isDue(SupplierEndpointBindingEntity binding, Instant now) {
        CronExpression cron;
        try {
            cron = CronExpression.parse(binding.getScheduleCron());
        } catch (IllegalArgumentException e) {
            // Logged every tick on purpose: a binding that silently never fires is exactly the
            // failure an operator cannot see.
            log.warn(
                    "Binding {} has an unparseable schedule cron '{}': {}",
                    binding.getId(),
                    binding.getScheduleCron(),
                    e.getMessage());
            return false;
        }
        Optional<StockSnapshotEntity> last =
                snapshotRepository
                        .findByVendorProfileIdOrderByFetchedAtDesc(binding.getVendorProfileId(), PageRequest.of(0, 1))
                        .stream()
                        .findFirst();
        if (last.isEmpty()) {
            return true;
        }
        LocalDateTime lastRun = LocalDateTime.ofInstant(last.get().getFetchedAt(), ZoneOffset.UTC);
        LocalDateTime next = cron.next(lastRun);
        return next != null && !next.isAfter(LocalDateTime.ofInstant(now, ZoneOffset.UTC));
    }
}

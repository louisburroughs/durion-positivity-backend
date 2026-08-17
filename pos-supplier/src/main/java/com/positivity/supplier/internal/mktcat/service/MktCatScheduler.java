package com.positivity.supplier.internal.mktcat.service;

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
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;

/**
 * Sweeps the marketing catalogue on a schedule (CAP-324 #1230).
 *
 * <h2>The mode that always works</h2>
 *
 * Change subscription is better when a catalogue offers it: fewer calls, and changes arrive in
 * minutes rather than at the next sweep. Not every deployment can accept callbacks, and a
 * subscription the vendor has silently forgotten produces no error — just a catalogue that stops
 * changing. The sweep is what makes either of those survivable, so it runs whether or not a
 * subscription exists.
 *
 * <h2>Nothing is published twice by running both</h2>
 *
 * A sweep and a callback both go through {@code MktCatImporter}, which publishes on content change
 * rather than on arrival. Running both modes together therefore costs extra reads and emits nothing
 * extra — which is what makes belt and braces a sensible default rather than a duplicate feed.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MktCatScheduler {

    private final SupplierEndpointBindingRepository bindingRepository;
    private final SupplierProfileRepository profileRepository;
    private final SupplierScheduleLeaseRepository leaseRepository;
    private final SupplierScheduleCoordinator scheduleCoordinator;
    private final MktCatImporter importer;
    private final Clock clock;

    /** Polls catalogue bindings that are due. Each runs at most once per tick, on one instance. */
    @Scheduled(fixedDelayString = "${pos.supplier.mktcat.poll-interval-ms:900000}")
    public void runDueSweeps() {
        Instant now = Instant.now(clock);
        for (SupplierEndpointBindingEntity binding :
                bindingRepository.findByCapabilityAndEnabledTrueAndScheduleCronIsNotNull(
                        SupplierCapability.MARKETING_CATALOG)) {
            try {
                runIfDue(binding, now);
            } catch (Exception e) {
                // One catalogue's problem must not stop the others. The checkpoint stayed put, so
                // this binding is swept again next tick.
                log.warn("Scheduled MKCAT sweep for binding {} failed: {}", binding.getId(), e.toString(), e);
            }
        }
    }

    private void runIfDue(@NonNull SupplierEndpointBindingEntity binding, @NonNull Instant now) {
        Optional<SupplierProfileEntity> profile = profileRepository.findById(binding.getVendorProfileId());
        if (profile.isEmpty() || !profile.get().isEnabled()) {
            return;
        }
        if (!isDue(binding, now)) {
            return;
        }

        String ownerToken = SupplierScheduleCoordinator.newOwnerToken();
        if (!scheduleCoordinator.tryClaim(binding.getId(), ownerToken)) {
            // Another instance holds this binding. Exactly one sweeper per catalogue is the point
            // of the lease.
            return;
        }

        String outcome = "FAILED";
        try {
            SupplierRef supplierRef = new SupplierRef(profile.get().getSupplierRef());
            scheduleCoordinator.runPage(binding.getId(), ownerToken, () -> {
                importer.importAll(supplierRef);
                return now;
            });
            outcome = "SUCCEEDED";
        } finally {
            scheduleCoordinator.release(binding.getId(), ownerToken, outcome);
        }
    }

    /**
     * Whether this binding's own cadence says to sweep now.
     *
     * <p>Measured from the last completed sweep, because that is the only record of when this
     * catalogue was actually read. Without evaluating the cron at all, every binding would be swept
     * on every poll tick, which for a full catalogue read means thousands of calls a day to a
     * standards body that is serving every consumer at once.
     */
    private boolean isDue(@NonNull SupplierEndpointBindingEntity binding, @NonNull Instant now) {
        CronExpression cron;
        try {
            cron = CronExpression.parse(binding.getScheduleCron());
        } catch (IllegalArgumentException e) {
            // Logged every tick deliberately: a binding that silently never fires is exactly the
            // failure an operator cannot see.
            log.warn(
                    "Binding {} has an unparseable schedule cron '{}': {}",
                    binding.getId(),
                    binding.getScheduleCron(),
                    e.getMessage());
            return false;
        }

        Instant checkpoint = checkpointFor(binding.getId());
        if (checkpoint == null) {
            // Never swept. Due immediately, so a newly configured catalogue does not wait a full
            // cron period for its first read.
            return true;
        }
        LocalDateTime next = cron.next(LocalDateTime.ofInstant(checkpoint, ZoneOffset.UTC));
        return next != null && !next.isAfter(LocalDateTime.ofInstant(now, ZoneOffset.UTC));
    }

    private Instant checkpointFor(@NonNull UUID bindingId) {
        return leaseRepository.findByBindingIdIn(List.of(bindingId)).stream()
                .map(SupplierScheduleLeaseEntity::getCheckpointAt)
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElse(null);
    }
}

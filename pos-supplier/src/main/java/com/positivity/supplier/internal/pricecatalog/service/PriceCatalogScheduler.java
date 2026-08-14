package com.positivity.supplier.internal.pricecatalog.service;

import com.positivity.supplier.internal.domain.model.SupplierCapability;
import com.positivity.supplier.internal.domain.model.SupplierRef;
import com.positivity.supplier.internal.entity.PriceCatalogImportEntity;
import com.positivity.supplier.internal.entity.SupplierEndpointBindingEntity;
import com.positivity.supplier.internal.entity.SupplierProfileEntity;
import com.positivity.supplier.internal.repository.PriceCatalogImportRepository;
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
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;

/**
 * Fires scheduled PRICAT imports for bindings that carry a cron (ADR-0053 §7).
 *
 * <h2>Why a poll rather than registered cron triggers</h2>
 *
 * Bindings are configuration rows that an admin can add, retime, or disable at any moment. A poll
 * re-reads them every cycle, so a cron change takes effect on the next tick without a restart or a
 * scheduler-registry rebuild.
 *
 * <h2>Why the lease, but not the checkpoint</h2>
 *
 * The schedule lease is what keeps two instances from fetching the same catalog twice — a
 * duplicated fetch is not merely wasteful, it would stage a second import and publish a second set
 * of chunks. The coordinator's checkpointed page mechanism is deliberately <em>not</em> used: a
 * PRICAT fetch returns the vendor's whole catalog rather than a time window, so there is no window
 * to advance and pretending otherwise would record a checkpoint that means nothing. "When did this
 * last run" is answered by the import table, which is the real record.
 *
 * <h2>Due-ness</h2>
 *
 * A binding is due when its cron's next fire time after the last import for that profile is not in
 * the future. With no prior import, the binding is due immediately — a newly configured catalog
 * feed should not wait a full cron period before its first pull.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "pos.supplier.pricat", name = "scheduler-enabled", matchIfMissing = true)
public class PriceCatalogScheduler {

    private final SupplierEndpointBindingRepository bindingRepository;
    private final SupplierProfileRepository profileRepository;
    private final PriceCatalogImportRepository importRepository;
    private final SupplierScheduleCoordinator scheduleCoordinator;
    private final PriceCatalogImporter importService;
    private final Clock clock;

    /** Polls due bindings. Each due binding runs at most once per tick, on at most one instance. */
    @Scheduled(fixedDelayString = "${pos.supplier.pricat.poll-interval-ms:60000}")
    public void runDueImports() {
        Instant now = Instant.now(clock);
        for (SupplierEndpointBindingEntity binding :
                bindingRepository.findByCapabilityAndEnabledTrueAndScheduleCronIsNotNull(
                        SupplierCapability.PRICE_CATALOG)) {
            try {
                runIfDue(binding, now);
            } catch (Exception e) {
                // One misconfigured or unreachable vendor must not stop the others from importing.
                log.warn("Scheduled PRICAT import for binding {} failed: {}", binding.getId(), e.toString(), e);
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
            // Another instance owns this run. The normal outcome on every instance but one.
            return;
        }
        String outcome = "FAILED";
        try {
            PriceCatalogImportEntity result =
                    importService.runImport(new SupplierRef(profile.get().getSupplierRef()));
            outcome = result.getStatus().name();
            log.info(
                    "Scheduled PRICAT import {} for profile {} finished with status {}",
                    result.getImportManifestId(),
                    result.getVendorProfileId(),
                    result.getStatus());
        } finally {
            // Released whatever happened: holding a lease after a failed run would block the next
            // scheduled attempt until the lease expired.
            scheduleCoordinator.release(binding.getId(), ownerToken, outcome);
        }
    }

    private boolean isDue(SupplierEndpointBindingEntity binding, Instant now) {
        CronExpression cron;
        try {
            cron = CronExpression.parse(binding.getScheduleCron());
        } catch (IllegalArgumentException e) {
            // A cron that does not parse is a configuration defect. Logging it every tick is
            // deliberate: silently never firing is exactly the failure an operator cannot see.
            log.warn(
                    "Binding {} has an unparseable schedule cron '{}': {}",
                    binding.getId(),
                    binding.getScheduleCron(),
                    e.getMessage());
            return false;
        }
        Optional<PriceCatalogImportEntity> last = importRepository
                .findByVendorProfileIdOrderByFetchedAtDesc(
                        binding.getVendorProfileId(), org.springframework.data.domain.PageRequest.of(0, 1))
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

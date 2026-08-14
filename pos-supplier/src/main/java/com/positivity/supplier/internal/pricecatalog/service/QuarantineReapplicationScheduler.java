package com.positivity.supplier.internal.pricecatalog.service;

import com.positivity.supplier.internal.entity.SupplierProfileEntity;
import com.positivity.supplier.internal.repository.SupplierProfileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Sweeps every enabled profile's quarantine on a cron, re-applying lines that the catalog can now
 * resolve (#1310, ADR-0053 §5).
 *
 * <h2>Its own cadence, not the import's</h2>
 *
 * What makes a quarantined line matchable is a change in the <em>catalog</em> — a product created,
 * a code corrected, or the product-code replica finally seeded. None of that has anything to do
 * with when the vendor is next fetched, so tying re-application to the import schedule would leave
 * lines waiting on a vendor for a fix that already happened on our side. A vendor fetched weekly
 * would strand a correction for a week.
 *
 * <h2>No lease</h2>
 *
 * Unlike the import scheduler this takes no schedule lease. Two instances sweeping at once is
 * harmless: the work is idempotent by construction — a line closed by one instance is no longer
 * open for the other, and a line matched twice in the same instant produces one closed row because
 * the close and the staging commit together. A lease would add a failure mode (a stuck lease
 * blocking recovery) to protect against something that costs nothing.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "pos.supplier.pricat", name = "reapply-enabled", matchIfMissing = true)
public class QuarantineReapplicationScheduler {

    private final SupplierProfileRepository profileRepository;
    private final QuarantineReapplier reapplicationService;

    /** Re-applies each enabled profile's quarantine; one failure never stops the other profiles. */
    @Scheduled(cron = "${pos.supplier.pricat.reapply-cron:0 15 * * * *}")
    public void sweep() {
        for (SupplierProfileEntity profile : profileRepository.findAll()) {
            if (!profile.isEnabled()) {
                continue;
            }
            try {
                reapplicationService
                        .reapply(profile.getVendorProfileId())
                        .ifPresent(manifest -> log.info(
                                "Quarantine sweep re-applied {} lines for profile {} as import {}",
                                manifest.getLinesMatched(),
                                profile.getVendorProfileId(),
                                manifest.getImportManifestId()));
            } catch (Exception e) {
                log.warn(
                        "Quarantine re-application failed for profile {}: {}",
                        profile.getVendorProfileId(),
                        e.toString(),
                        e);
            }
        }
    }
}

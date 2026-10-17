package com.positivity.supplier.internal.vendor.service;

import com.positivity.supplier.internal.entity.SupplierProfileEntity;
import com.positivity.supplier.internal.entity.SupplierVendorEntity;
import com.positivity.supplier.internal.repository.SupplierProfileRepository;
import com.positivity.supplier.internal.repository.SupplierVendorRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import com.positivity.supplier.internal.event.VendorProfilesChanged;
import com.positivity.supplier.internal.event.VendorProfilesChanging;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * A vendor's designated purchasing profile (CAP:550 #2712, the Order ruling on #2682): the transmission profile a
 * purchase order to the vendor is sent through, published on {@code supplier.vendor.updated} as {@code
 * purchaseOrderSupplierRef} so pos-order can stamp it on the order without ever asking pos-supplier (ADR-0044 R1).
 *
 * <p>The rule: the vendor's one {@code enabled}, non-{@code sandbox} profile. None, or more than one, is no channel
 * (null): the platform owner decides whether several are resolved by an explicit designation (open item of the
 * ruling); until then the vendor's administrator keeps one purchasing profile enabled.
 *
 * <p>Whenever a profile change can move a vendor's designation (create, update, re-point, enable, disable, delete,
 * YAML reconciliation), the writer publishes {@link VendorProfilesChanging} before and {@link VendorProfilesChanged}
 * after it: the designation is read at both, and a vendor whose channel moved is republished with a higher {@code
 * aggregateVersion}, as for any vendor change (ADR-0044 §3). A change that leaves every designation alone publishes
 * nothing. Events, not calls, because the vendor slice already depends on the service slice (ArchUnit cycle rule).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class VendorPurchasingProfile {

    private final SupplierProfileRepository profiles;
    private final SupplierVendorRepository vendors;
    private final VendorFactPublisher factPublisher;
    private final EntityManager entityManager;
    private final Clock clock;

    private static final Object SNAPSHOT_KEY = new Object();

    /** The alias of the vendor's designated purchasing profile, or empty when it has none (or several). */
    public @NonNull Optional<String> purchaseOrderSupplierRef(@NonNull UUID vendorId) {
        return Optional.ofNullable(designated(profiles.findByVendorIdOrderBySupplierRefAsc(vendorId)));
    }

    /** The rule itself, over a vendor's profiles: exactly one enabled non-sandbox profile, else null. */
    static String designated(@NonNull List<SupplierProfileEntity> vendorProfiles) {
        List<SupplierProfileEntity> candidates = vendorProfiles.stream()
                .filter(profile -> profile.isEnabled() && !profile.isSandbox())
                .toList();
        return candidates.size() == 1 ? candidates.getFirst().getSupplierRef() : null;
    }

    /**
     * Before a profile write: remembers each touched vendor's designation for the rest of the transaction, so the
     * matching {@link VendorProfilesChanged} knows what moved. A vendor already remembered in this transaction keeps
     * its first snapshot (the state before any of the transaction's writes).
     */
    @EventListener
    public void onChanging(@NonNull VendorProfilesChanging event) {
        Map<UUID, Optional<String>> snapshot = snapshot();
        for (UUID vendorId : event.vendorIds()) {
            snapshot.computeIfAbsent(vendorId, this::purchaseOrderSupplierRef);
        }
    }

    /**
     * After the write: republishes every touched vendor whose designation differs from its snapshot (or from "none"
     * when the write announced no snapshot). Runs in the publisher's transaction, so the fact is queued with the
     * profile change.
     */
    @EventListener
    public void onChanged(@NonNull VendorProfilesChanged event) {
        entityManager.flush();
        Map<UUID, Optional<String>> snapshot = snapshot();
        for (UUID vendorId : event.vendorIds()) {
            Optional<String> before = snapshot.getOrDefault(vendorId, Optional.empty());
            Optional<String> after = purchaseOrderSupplierRef(vendorId);
            if (!Objects.equals(before, after)) {
                republish(vendorId);
                snapshot.put(vendorId, after);
            }
        }
    }

    /** The transaction's snapshot of designations; a plain map outside a transaction (tests, bootstraps). */
    @SuppressWarnings("unchecked")
    private Map<UUID, Optional<String>> snapshot() {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return new HashMap<>();
        }
        Object bound = TransactionSynchronizationManager.getResource(SNAPSHOT_KEY);
        if (bound == null) {
            Map<UUID, Optional<String>> fresh = new HashMap<>();
            TransactionSynchronizationManager.bindResource(SNAPSHOT_KEY, fresh);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    TransactionSynchronizationManager.unbindResourceIfPossible(SNAPSHOT_KEY);
                }
            });
            return fresh;
        }
        return (Map<UUID, Optional<String>>) bound;
    }

    /**
     * The vendor's current fact again, under a higher {@code aggregateVersion}: the version is forced up although no
     * column of the vendor changed, so consumers' stale-fact guards apply it in order (ADR-0044 §3).
     */
    private void republish(@NonNull UUID vendorId) {
        Optional<SupplierVendorEntity> vendor = vendors.findById(vendorId);
        if (vendor.isEmpty()) {
            // A YAML profile may name a vendor this tenant does not hold yet; there is nothing to republish.
            return;
        }
        entityManager.lock(vendor.get(), LockModeType.PESSIMISTIC_FORCE_INCREMENT);
        entityManager.flush();
        factPublisher.publish(vendor.get(), Instant.now(clock), SupplierVendorServiceImpl.currentActorOrNull());
        log.info("Republished vendor {} after its purchasing profile changed", vendor.get().getVendorNumber());
    }
}

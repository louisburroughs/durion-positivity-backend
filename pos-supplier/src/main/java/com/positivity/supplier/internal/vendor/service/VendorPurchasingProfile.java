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
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;

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
 * YAML reconciliation), the change runs through {@link #republishIfChanged}: the designation is read before and
 * after, and a vendor whose channel changed is republished with a higher {@code aggregateVersion}, as for any vendor
 * change (ADR-0044 §3). A change that leaves every designation alone publishes nothing.
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
     * Runs {@code change} and republishes every vendor among {@code vendorIds} whose designation it moved. Call it
     * from inside the transaction that makes the change; the republished fact is queued in that transaction.
     */
    public <T> T republishIfChanged(@NonNull Set<UUID> vendorIds, @NonNull Supplier<T> change) {
        Map<UUID, Optional<String>> before = new HashMap<>();
        for (UUID vendorId : vendorIds) {
            before.put(vendorId, purchaseOrderSupplierRef(vendorId));
        }
        T result = change.get();
        entityManager.flush();
        for (Map.Entry<UUID, Optional<String>> entry : before.entrySet()) {
            Optional<String> after = purchaseOrderSupplierRef(entry.getKey());
            if (!Objects.equals(entry.getValue(), after)) {
                republish(entry.getKey());
            }
        }
        return result;
    }

    /** {@link #republishIfChanged} for a change with no result. */
    public void republishIfChanged(@NonNull Set<UUID> vendorIds, @NonNull Runnable change) {
        republishIfChanged(vendorIds, () -> {
            change.run();
            return null;
        });
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

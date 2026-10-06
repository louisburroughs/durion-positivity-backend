package com.positivity.supplier.internal.vendor.service;

import com.positivity.shared.id.UUIDv7Generator;
import com.positivity.supplier.internal.entity.SupplierVendorNumberSequenceEntity;
import com.positivity.supplier.internal.repository.SupplierVendorNumberSequenceRepository;
import com.positivity.supplier.internal.repository.SupplierVendorRepository;
import com.positivity.tenancy.TenantResolver;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Hands out {@code V-000001}, {@code V-000002}, … per tenant without two creates ever picking the
 * same number (#2516; the pos-workorder {@code DocumentNumberAllocator} pattern, #2150).
 *
 * <p>The tenant's counter row is read {@code FOR UPDATE} and advanced in the caller's transaction
 * ({@code MANDATORY}), so the lock is held until the vendor insert commits. A candidate someone
 * already chose by hand is skipped under the lock.
 */
@Component
@RequiredArgsConstructor
public class VendorNumberAllocator {

    /** Prefix of allocated numbers. */
    static final String PREFIX = "V-";

    private final SupplierVendorNumberSequenceRepository sequenceRepository;
    private final SupplierVendorRepository vendorRepository;
    private final TenantResolver tenantResolver;
    private final Clock clock;

    /** The next free allocated number of the bound tenant. */
    @NonNull
    @Transactional(propagation = Propagation.MANDATORY)
    public String allocate() {
        SupplierVendorNumberSequenceEntity sequence =
                sequenceRepository.findFirstByOrderByIdAsc().orElseGet(this::provisionAndRelock);
        long value = sequence.getNextValue();
        String candidate = format(value);
        while (vendorRepository.existsByVendorNumber(candidate)) {
            value++;
            candidate = format(value);
        }
        sequence.setNextValue(value + 1);
        return candidate;
    }

    static String format(long value) {
        return PREFIX + String.format(Locale.ROOT, "%06d", value);
    }

    private SupplierVendorNumberSequenceEntity provisionAndRelock() {
        sequenceRepository.insertIfAbsent(tenantResolver.require(), UUIDv7Generator.generate(), Instant.now(clock));
        return sequenceRepository
                .findFirstByOrderByIdAsc()
                .orElseThrow(() -> new IllegalStateException("Vendor number sequence was not provisioned"));
    }
}

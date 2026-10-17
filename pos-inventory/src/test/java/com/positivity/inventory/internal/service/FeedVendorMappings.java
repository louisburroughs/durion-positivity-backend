package com.positivity.inventory.internal.service;

import com.positivity.inventory.internal.entity.ExtSupplierVendorReplica;
import com.positivity.inventory.internal.entity.VendorFeedMapping;
import com.positivity.inventory.internal.enums.FeedVendorType;
import com.positivity.inventory.internal.enums.VendorStatus;
import com.positivity.inventory.internal.repository.ExtSupplierVendorReplicaRepository;
import com.positivity.inventory.internal.repository.VendorFeedMappingRepository;
import java.time.Instant;
import java.util.UUID;

/**
 * CAP:550 S36 (#2525) test support: a feed vendor is a purchase candidate only once it maps to an ACTIVE vendor in
 * the copy, so context tests that seed feed rows map them here (idempotent per feed vendor).
 */
public final class FeedVendorMappings {

    private FeedVendorMappings() {}

    public static UUID mapActive(
            ExtSupplierVendorReplicaRepository vendors,
            VendorFeedMappingRepository mappings,
            FeedVendorType feedType,
            String feedVendorRef) {
        String key = FeedVendorType.canonical(feedVendorRef);
        return mappings.findByFeedTypeAndFeedVendorRef(feedType, key)
                .map(VendorFeedMapping::getVendorId)
                .orElseGet(() -> {
                    UUID vendorId = UUID.randomUUID();
                    vendors.save(ExtSupplierVendorReplica.builder()
                            .vendorId(vendorId)
                            .vendorNumber("V-" + key.substring(Math.max(0, key.length() - 4)))
                            .displayName("Vendor " + key)
                            .status(VendorStatus.ACTIVE)
                            .statusChangedAt(Instant.parse("2026-01-01T00:00:00Z"))
                            .aggregateVersion(1L)
                            .updatedAt(Instant.parse("2026-01-01T00:00:00Z"))
                            .build());
                    mappings.save(VendorFeedMapping.builder()
                            .feedType(feedType)
                            .feedVendorRef(key)
                            .vendorId(vendorId)
                            .createdBy("test")
                            .createdAt(Instant.parse("2026-01-01T00:00:00Z"))
                            .updatedBy("test")
                            .updatedAt(Instant.parse("2026-01-01T00:00:00Z"))
                            .build());
                    return vendorId;
                });
    }
}

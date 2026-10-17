package com.positivity.inventory.internal.service;

import com.positivity.inventory.internal.dto.vendorfeed.UnmappedFeedVendorResponse;
import com.positivity.inventory.internal.dto.vendorfeed.VendorFeedMappingRequest;
import com.positivity.inventory.internal.dto.vendorfeed.VendorFeedMappingResponse;
import com.positivity.inventory.internal.dto.vendorfeed.VendorFeedMappingUpdateRequest;
import com.positivity.inventory.internal.entity.ExtSupplierVendorReplica;
import com.positivity.inventory.internal.entity.VendorFeedMapping;
import com.positivity.inventory.internal.enums.FeedVendorType;
import com.positivity.inventory.internal.enums.PurchaseSuggestionStatus;
import com.positivity.inventory.internal.enums.SuggestedVendorType;
import com.positivity.inventory.internal.enums.UnmappedFeedVendorReason;
import com.positivity.inventory.internal.enums.VendorStatus;
import com.positivity.inventory.internal.exception.VendorFeedMappingException;
import com.positivity.inventory.internal.repository.DistributorNormalizedInventoryRepository;
import com.positivity.inventory.internal.repository.FeedVendorSummary;
import com.positivity.inventory.internal.repository.NormalizedAvailabilityRepository;
import com.positivity.inventory.internal.repository.PurchaseSuggestionRepository;
import com.positivity.inventory.internal.repository.VendorFeedMappingRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Feed vendor to pos-supplier vendor mappings (CAP:550 S36, #2525).
 *
 * <p>The mapping stores the vendor id with no foreign key; the vendor is checked against the local copy
 * ({@link VendorCopyReader}) when the mapping is made or re-pointed, and checked again by whoever resolves it, because a
 * vendor can be deactivated after the mapping was made. Creating, re-pointing and deleting a mapping update the open
 * (SUGGESTED, ACCEPTED) purchase suggestions of the feed vendor in the same transaction.
 */
@Slf4j
@Service
public class VendorFeedMappingServiceImpl implements VendorFeedMappingService {

    /** The unique constraint on {@code (tenant_id, feed_type, feed_vendor_ref)}. */
    static final String FEED_UNIQUE_CONSTRAINT = "uq_vendor_feed_mapping_feed";

    /** Gauge of feed vendors that cannot be purchase candidates, per feed; set when the unmapped list is scanned. */
    static final String UNMAPPED_GAUGE = "inventory.vendor_feed.unmapped";

    private static final List<PurchaseSuggestionStatus> OPEN_STATUSES =
            List.of(PurchaseSuggestionStatus.SUGGESTED, PurchaseSuggestionStatus.ACCEPTED);

    private final VendorFeedMappingRepository mappings;
    private final PurchaseSuggestionRepository suggestions;
    private final NormalizedAvailabilityRepository manufacturerFeed;
    private final DistributorNormalizedInventoryRepository distributorFeed;
    private final VendorCopyReader vendorCopy;
    private final Clock clock;
    private final @Nullable MeterRegistry meterRegistry;
    private final Map<FeedVendorType, AtomicInteger> unmappedCounts = new ConcurrentHashMap<>();

    public VendorFeedMappingServiceImpl(
            VendorFeedMappingRepository mappings,
            PurchaseSuggestionRepository suggestions,
            NormalizedAvailabilityRepository manufacturerFeed,
            DistributorNormalizedInventoryRepository distributorFeed,
            VendorCopyReader vendorCopy,
            Clock clock,
            ObjectProvider<MeterRegistry> meterRegistry) {
        this.mappings = mappings;
        this.suggestions = suggestions;
        this.manufacturerFeed = manufacturerFeed;
        this.distributorFeed = distributorFeed;
        this.vendorCopy = vendorCopy;
        this.clock = clock;
        this.meterRegistry = meterRegistry.getIfAvailable();
    }

    @Override
    @Transactional(readOnly = true)
    public @NonNull Page<VendorFeedMappingResponse> list(
            @Nullable FeedVendorType feedType, @Nullable UUID vendorId, @NonNull Pageable pageable) {
        Page<VendorFeedMapping> page;
        if (feedType != null && vendorId != null) {
            page = mappings.findAllByFeedTypeAndVendorId(feedType, vendorId, pageable);
        } else if (feedType != null) {
            page = mappings.findAllByFeedType(feedType, pageable);
        } else if (vendorId != null) {
            page = mappings.findAllByVendorId(vendorId, pageable);
        } else {
            page = mappings.findAll(pageable);
        }
        Map<UUID, ExtSupplierVendorReplica> vendors = vendorCopy.byIds(
                page.getContent().stream().map(VendorFeedMapping::getVendorId).toList());
        return page.map(mapping -> toResponse(mapping, vendors.get(mapping.getVendorId())));
    }

    @Override
    @Transactional(readOnly = true)
    public @NonNull List<UnmappedFeedVendorResponse> listUnmapped(@Nullable FeedVendorType feedType) {
        List<UnmappedFeedVendorResponse> unmapped = new ArrayList<>();
        for (FeedVendorType type : FeedVendorType.values()) {
            if (feedType == null || feedType == type) {
                List<UnmappedFeedVendorResponse> ofType = scan(type);
                unmappedCount(type).set(ofType.size());
                unmapped.addAll(ofType);
            }
        }
        unmapped.sort(Comparator.comparing(UnmappedFeedVendorResponse::getFeedType)
                .thenComparing(UnmappedFeedVendorResponse::getFeedVendorRef));
        return unmapped;
    }

    private List<UnmappedFeedVendorResponse> scan(FeedVendorType type) {
        List<FeedVendorSummary> feedVendors = type == FeedVendorType.MANUFACTURER
                ? manufacturerFeed.summarizeManufacturers()
                : distributorFeed.summarizeDistributors();
        Map<String, VendorFeedMapping> byRef = new HashMap<>();
        for (VendorFeedMapping mapping : mappings.findAllByFeedType(type)) {
            byRef.put(mapping.getFeedVendorRef(), mapping);
        }
        Map<UUID, ExtSupplierVendorReplica> vendors = vendorCopy.byIds(byRef.values().stream()
                .map(VendorFeedMapping::getVendorId)
                .collect(java.util.stream.Collectors.toSet()));

        Map<String, UnmappedFeedVendorResponse> found = new HashMap<>();
        for (FeedVendorSummary summary : feedVendors) {
            String ref = canonicalFeedRef(type, String.valueOf(summary.getFeedVendorRef()));
            UnmappedFeedVendorReason reason = reasonFor(byRef.get(ref), vendors);
            if (reason == null) {
                continue;
            }
            // Two raw ids that canonicalize alike (case, padding) are one feed vendor: add up what they report.
            found.merge(
                    ref,
                    UnmappedFeedVendorResponse.builder()
                            .feedType(type.name())
                            .feedVendorRef(ref)
                            .reason(reason.name())
                            .productCount(summary.getProductCount())
                            .lastReportedAt(summary.getLastReportedAt())
                            .build(),
                    (a, b) -> {
                        a.setProductCount(a.getProductCount() + b.getProductCount());
                        if (b.getLastReportedAt() != null
                                && (a.getLastReportedAt() == null
                                        || b.getLastReportedAt().isAfter(a.getLastReportedAt()))) {
                            a.setLastReportedAt(b.getLastReportedAt());
                        }
                        return a;
                    });
        }
        return new ArrayList<>(found.values());
    }

    private static @Nullable UnmappedFeedVendorReason reasonFor(
            @Nullable VendorFeedMapping mapping, Map<UUID, ExtSupplierVendorReplica> vendors) {
        if (mapping == null) {
            return UnmappedFeedVendorReason.UNMAPPED;
        }
        ExtSupplierVendorReplica vendor = vendors.get(mapping.getVendorId());
        if (vendor == null) {
            return UnmappedFeedVendorReason.VENDOR_NOT_IN_COPY;
        }
        return vendor.getStatus() == VendorStatus.ACTIVE ? null : UnmappedFeedVendorReason.VENDOR_INACTIVE;
    }

    private AtomicInteger unmappedCount(FeedVendorType type) {
        return unmappedCounts.computeIfAbsent(type, t -> {
            AtomicInteger count = new AtomicInteger();
            if (meterRegistry != null) {
                Gauge.builder(UNMAPPED_GAUGE, count, AtomicInteger::get)
                        .description("Feed vendors with no usable pos-supplier vendor mapping")
                        .tag("feedType", t.name())
                        .register(meterRegistry);
            }
            return count;
        });
    }

    @Override
    @Transactional
    public @NonNull VendorFeedMappingResponse create(@NonNull VendorFeedMappingRequest request) {
        FeedVendorType feedType = request.getFeedType();
        String ref = normalizeFeedRef(feedType, request.getFeedVendorRef());
        ExtSupplierVendorReplica vendor = requireActiveVendor(request.getVendorId());
        if (mappings.findByFeedTypeAndFeedVendorRef(feedType, ref).isPresent()) {
            throw VendorFeedMappingException.exists(feedType.name(), ref);
        }
        VendorFeedMapping mapping = VendorFeedMapping.builder()
                .feedType(feedType)
                .feedVendorRef(ref)
                .vendorId(vendor.getVendorId())
                .note(blankToNull(request.getNote()))
                .build();
        VendorFeedMapping saved;
        try {
            saved = mappings.saveAndFlush(mapping);
        } catch (DataIntegrityViolationException e) {
            if (isConstraintViolation(e, FEED_UNIQUE_CONSTRAINT)) {
                throw VendorFeedMappingException.exists(feedType.name(), ref);
            }
            throw e;
        }
        int followed = followSuggestions(feedType, ref, vendor.getVendorId());
        log.info(
                "Mapped feed vendor feedType={} feedVendorRef={} to vendorId={} suggestionsUpdated={}",
                feedType,
                ref,
                vendor.getVendorId(),
                followed);
        return toResponse(saved, vendor);
    }

    @Override
    @Transactional
    public @NonNull VendorFeedMappingResponse update(
            @NonNull UUID mappingId, @NonNull VendorFeedMappingUpdateRequest request) {
        VendorFeedMapping mapping =
                mappings.findById(mappingId).orElseThrow(() -> VendorFeedMappingException.notFound(mappingId));
        if (!request.getVersion().equals(mapping.getVersion())) {
            throw VendorFeedMappingException.staleVersion(mappingId);
        }
        ExtSupplierVendorReplica vendor = requireActiveVendor(request.getVendorId());
        mapping.setVendorId(vendor.getVendorId());
        mapping.setNote(blankToNull(request.getNote()));
        VendorFeedMapping saved;
        try {
            saved = mappings.saveAndFlush(mapping);
        } catch (ObjectOptimisticLockingFailureException e) {
            throw VendorFeedMappingException.staleVersion(mappingId);
        }
        int followed = followSuggestions(saved.getFeedType(), saved.getFeedVendorRef(), vendor.getVendorId());
        log.info(
                "Re-pointed feed vendor mapping mappingId={} feedType={} feedVendorRef={} vendorId={}"
                        + " suggestionsUpdated={}",
                mappingId,
                saved.getFeedType(),
                saved.getFeedVendorRef(),
                vendor.getVendorId(),
                followed);
        return toResponse(saved, vendor);
    }

    @Override
    @Transactional
    public void delete(@NonNull UUID mappingId) {
        VendorFeedMapping mapping =
                mappings.findById(mappingId).orElseThrow(() -> VendorFeedMappingException.notFound(mappingId));
        mappings.delete(mapping);
        mappings.flush();
        int followed = followSuggestions(mapping.getFeedType(), mapping.getFeedVendorRef(), null);
        log.info(
                "Removed feed vendor mapping mappingId={} feedType={} feedVendorRef={} suggestionsUpdated={}",
                mappingId,
                mapping.getFeedType(),
                mapping.getFeedVendorRef(),
                followed);
    }

    @Override
    @Transactional(readOnly = true)
    public @NonNull Optional<ResolvedFeedVendor> resolve(
            @NonNull FeedVendorType feedType, @NonNull String feedVendorRef) {
        String ref = canonicalFeedRef(feedType, feedVendorRef);
        return mappings.findByFeedTypeAndFeedVendorRef(feedType, ref)
                .flatMap(mapping -> vendorCopy.find(mapping.getVendorId()))
                .map(VendorFeedMappingServiceImpl::toResolved);
    }

    @Override
    @Transactional(readOnly = true)
    public @NonNull Map<String, ResolvedFeedVendor> resolveAll(
            @NonNull FeedVendorType feedType, @NonNull Collection<String> feedVendorRefs) {
        Set<String> refs = new HashSet<>();
        for (String ref : feedVendorRefs) {
            refs.add(canonicalFeedRef(feedType, ref));
        }
        Map<String, ResolvedFeedVendor> resolved = new HashMap<>();
        if (refs.isEmpty()) {
            return resolved;
        }
        List<VendorFeedMapping> found = mappings.findAllByFeedTypeAndFeedVendorRefIn(feedType, refs);
        Map<UUID, ExtSupplierVendorReplica> vendors = vendorCopy.byIds(
                found.stream().map(VendorFeedMapping::getVendorId).collect(java.util.stream.Collectors.toSet()));
        for (VendorFeedMapping mapping : found) {
            ExtSupplierVendorReplica vendor = vendors.get(mapping.getVendorId());
            if (vendor != null) {
                resolved.put(mapping.getFeedVendorRef(), toResolved(vendor));
            }
        }
        return resolved;
    }

    private ExtSupplierVendorReplica requireActiveVendor(UUID vendorId) {
        ExtSupplierVendorReplica vendor =
                vendorCopy.find(vendorId).orElseThrow(() -> VendorFeedMappingException.vendorNotFound(vendorId));
        if (vendor.getStatus() != VendorStatus.ACTIVE) {
            throw VendorFeedMappingException.vendorInactive(vendorId);
        }
        return vendor;
    }

    /** Points the feed vendor's open suggestions at {@code vendorId} (null: at none), in the caller's transaction. */
    private int followSuggestions(FeedVendorType feedType, String feedVendorRef, @Nullable UUID vendorId) {
        return suggestions.updateVendorIdForFeedVendor(
                SuggestedVendorType.valueOf(feedType.name()),
                feedVendorRef,
                OPEN_STATUSES,
                vendorId,
                Instant.now(clock));
    }

    /**
     * The stored form of a feed vendor ref, refusing a blank one and, for a manufacturer, one that is not a UUID.
     */
    static String normalizeFeedRef(FeedVendorType feedType, @Nullable String raw) {
        String trimmed = raw == null ? "" : raw.trim();
        if (trimmed.isEmpty()) {
            throw VendorFeedMappingException.invalidFeedVendorRef("feedVendorRef must not be blank");
        }
        if (feedType == FeedVendorType.MANUFACTURER) {
            try {
                return UUID.fromString(trimmed).toString().toLowerCase(Locale.ROOT);
            } catch (IllegalArgumentException e) {
                throw VendorFeedMappingException.invalidFeedVendorRef(
                        "feedVendorRef must be a UUID for a MANUFACTURER feed vendor");
            }
        }
        return trimmed;
    }

    /** The form a feed's own id is compared in: trimmed, and lower-case for a manufacturer UUID. */
    private static String canonicalFeedRef(FeedVendorType feedType, String raw) {
        String trimmed = raw.trim();
        return feedType == FeedVendorType.MANUFACTURER ? trimmed.toLowerCase(Locale.ROOT) : trimmed;
    }

    private static @Nullable String blankToNull(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static ResolvedFeedVendor toResolved(ExtSupplierVendorReplica vendor) {
        return new ResolvedFeedVendor(
                vendor.getVendorId(), vendor.getVendorNumber(), vendor.getDisplayName(), vendor.getStatus());
    }

    private static VendorFeedMappingResponse toResponse(
            VendorFeedMapping mapping, @Nullable ExtSupplierVendorReplica vendor) {
        return VendorFeedMappingResponse.builder()
                .mappingId(mapping.getMappingId())
                .feedType(mapping.getFeedType().name())
                .feedVendorRef(mapping.getFeedVendorRef())
                .vendorId(mapping.getVendorId())
                .vendorNumber(vendor != null ? vendor.getVendorNumber() : null)
                .vendorDisplayName(vendor != null ? vendor.getDisplayName() : null)
                .vendorStatus(
                        vendor != null && vendor.getStatus() != null
                                ? vendor.getStatus().name()
                                : null)
                .note(mapping.getNote())
                .version(mapping.getVersion())
                .createdBy(mapping.getCreatedBy())
                .createdAt(mapping.getCreatedAt())
                .updatedBy(mapping.getUpdatedBy())
                .updatedAt(mapping.getUpdatedAt())
                .build();
    }

    /** True when the integrity failure names {@code constraint}; any other integrity failure is a bug. */
    static boolean isConstraintViolation(DataIntegrityViolationException e, String constraint) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            String message = t.getMessage();
            if (message != null && message.toLowerCase(Locale.ROOT).contains(constraint)) {
                return true;
            }
        }
        return false;
    }
}

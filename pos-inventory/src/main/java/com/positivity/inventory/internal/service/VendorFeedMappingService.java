package com.positivity.inventory.internal.service;

import com.positivity.inventory.internal.dto.vendorfeed.UnmappedFeedVendorResponse;
import com.positivity.inventory.internal.dto.vendorfeed.VendorFeedMappingRequest;
import com.positivity.inventory.internal.dto.vendorfeed.VendorFeedMappingResponse;
import com.positivity.inventory.internal.dto.vendorfeed.VendorFeedMappingUpdateRequest;
import com.positivity.inventory.internal.enums.FeedVendorType;
import com.positivity.inventory.internal.enums.VendorStatus;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Maintains and resolves the mapping from manufacturer and distributor feed vendors to pos-supplier vendors (CAP:550
 * S36, #2525). A mapping may name only a vendor the local {@code ext_supplier_vendor} copy holds and that is ACTIVE; the
 * open purchase suggestions of the feed vendor follow the mapping in the same transaction.
 */
public interface VendorFeedMappingService {

    /**
     * A feed vendor's pos-supplier vendor, as the local copy holds it. Any status: callers check {@code status}.
     *
     * @param vendorId the pos-supplier vendor id
     * @param vendorNumber the vendor's number; may be null on a copy row written before the number was known
     * @param displayName the vendor's display name; may be null likewise
     * @param status the vendor's status; null only on a copy row with none
     */
    record ResolvedFeedVendor(
            @NonNull UUID vendorId,
            @Nullable String vendorNumber,
            @Nullable String displayName,
            @Nullable VendorStatus status) {

        /** Whether the vendor is ACTIVE, the only status a purchase candidate may have. */
        public boolean isActive() {
            return status == VendorStatus.ACTIVE;
        }
    }

    @NonNull Page<VendorFeedMappingResponse> list(
            @Nullable FeedVendorType feedType, @Nullable UUID vendorId, @NonNull Pageable pageable);

    /** Feed vendors that are unmapped, or mapped to a vendor missing from the copy or INACTIVE. */
    @NonNull List<UnmappedFeedVendorResponse> listUnmapped(@Nullable FeedVendorType feedType);

    @NonNull VendorFeedMappingResponse create(@NonNull VendorFeedMappingRequest request);

    @NonNull VendorFeedMappingResponse update(@NonNull UUID mappingId, @NonNull VendorFeedMappingUpdateRequest request);

    void delete(@NonNull UUID mappingId);

    /**
     * The pos-supplier vendor a feed vendor is mapped to: present whenever a mapping exists and the vendor is in the
     * copy (any status), empty when the feed vendor is unmapped or the vendor is missing from the copy.
     */
    @NonNull Optional<ResolvedFeedVendor> resolve(@NonNull FeedVendorType feedType, @NonNull String feedVendorRef);

    /** {@link #resolve} for many feed vendors of one feed; keyed by the normalized feed vendor ref, unresolved omitted. */
    @NonNull Map<String, ResolvedFeedVendor> resolveAll(
            @NonNull FeedVendorType feedType, @NonNull Collection<String> feedVendorRefs);
}

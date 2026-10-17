package com.positivity.inventory.internal.repository;

import com.positivity.inventory.internal.entity.VendorFeedMapping;
import com.positivity.inventory.internal.enums.FeedVendorType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/** Feed vendor to pos-supplier vendor mappings (CAP:550 S36, #2525). */
public interface VendorFeedMappingRepository extends JpaRepository<VendorFeedMapping, UUID> {

    Optional<VendorFeedMapping> findByFeedTypeAndFeedVendorRef(FeedVendorType feedType, String feedVendorRef);

    /** The mappings of one feed among the given feed vendor refs, for batch resolution. */
    List<VendorFeedMapping> findAllByFeedTypeAndFeedVendorRefIn(FeedVendorType feedType, Collection<String> refs);

    List<VendorFeedMapping> findAllByFeedType(FeedVendorType feedType);

    Page<VendorFeedMapping> findAllByFeedType(FeedVendorType feedType, Pageable pageable);

    Page<VendorFeedMapping> findAllByVendorId(UUID vendorId, Pageable pageable);

    Page<VendorFeedMapping> findAllByFeedTypeAndVendorId(FeedVendorType feedType, UUID vendorId, Pageable pageable);
}

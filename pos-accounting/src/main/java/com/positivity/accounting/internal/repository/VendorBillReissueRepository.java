package com.positivity.accounting.internal.repository;

import com.positivity.accounting.internal.entity.VendorBillReissue;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Re-issues of approved vendor bills, kept as exception items (#2509). */
public interface VendorBillReissueRepository extends JpaRepository<VendorBillReissue, UUID> {

    /** Whether the supplier fact was already recorded as a re-issue. */
    boolean existsBySourceEventId(UUID sourceEventId);

    /** The re-issues recorded against one approved bill, oldest first. */
    List<VendorBillReissue> findByVendorBillIdOrderByCreatedAtAsc(UUID vendorBillId);
}

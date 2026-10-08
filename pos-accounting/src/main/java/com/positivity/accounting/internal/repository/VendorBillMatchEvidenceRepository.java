package com.positivity.accounting.internal.repository;

import com.positivity.accounting.internal.entity.VendorBillMatchEvidence;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** The append-only match evidence of vendor bills (#2509). */
public interface VendorBillMatchEvidenceRepository extends JpaRepository<VendorBillMatchEvidence, UUID> {

    /** The bill's latest evidence: what the bill read serves as {@code match}. */
    Optional<VendorBillMatchEvidence> findFirstByVendorBillIdOrderByRecordedAtDescMatchEvidenceIdDesc(
            UUID vendorBillId);
}

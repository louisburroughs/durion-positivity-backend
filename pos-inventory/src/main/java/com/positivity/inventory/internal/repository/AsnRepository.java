package com.positivity.inventory.internal.repository;

import com.positivity.inventory.internal.entity.AdvanceShippingNoticeEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AsnRepository extends JpaRepository<AdvanceShippingNoticeEntity, UUID> {

    Optional<AdvanceShippingNoticeEntity> findByVendorIdAndAsnReferenceNumber(UUID vendorId, String asnReferenceNumber);

    List<AdvanceShippingNoticeEntity> findByVendorId(UUID vendorId);
}

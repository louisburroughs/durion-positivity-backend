package com.positivity.supplier.internal.repository;

import com.positivity.supplier.internal.entity.SupplierTransmissionLineEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Lines of a transmission intent, kept so a deferred dispatch can rebuild the same document. */
public interface SupplierTransmissionLineRepository extends JpaRepository<SupplierTransmissionLineEntity, UUID> {

    /** One intent's lines in wire order. */
    List<SupplierTransmissionLineEntity> findByTransmissionIntentIdOrderByLineNumberAsc(UUID transmissionIntentId);
}

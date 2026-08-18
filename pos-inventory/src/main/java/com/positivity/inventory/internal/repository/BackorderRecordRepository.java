package com.positivity.inventory.internal.repository;

import com.positivity.inventory.internal.entity.BackorderRecord;
import com.positivity.inventory.internal.enums.BackorderStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/**
 * Repository for {@link BackorderRecord} (odoo-parity G1, issue #1046).
 *
 * <p>{@link JpaSpecificationExecutor} backs the filtered list endpoint; the derived queries serve
 * the duplicate-open guard and the availability-driven auto-resolution scan.
 */
public interface BackorderRecordRepository
        extends JpaRepository<BackorderRecord, UUID>, JpaSpecificationExecutor<BackorderRecord> {

    /** Duplicate-open guard: at most one OPEN backorder per (workorderLineId, sku). */
    Optional<BackorderRecord> findByWorkorderLineIdAndSkuAndStatus(
            UUID workorderLineId, String sku, BackorderStatus status);

    /** Duplicate-open guard for the sales-order demand key (CAP #1315). */
    Optional<BackorderRecord> findBySalesOrderLineIdAndSkuAndStatus(
            UUID salesOrderLineId, String sku, BackorderStatus status);

    /**
     * Open backorders for one SKU at one site, oldest-first — the auto-resolution candidate set
     * (odoo-parity G1). Base ordering is {@code createdAt ASC}; the service refines it with the
     * backing reservation's fairness ordering when a reservation is linked.
     */
    List<BackorderRecord> findBySkuAndLocationIdAndStatusOrderByCreatedAtAsc(
            String sku, UUID locationId, BackorderStatus status);
}

package com.positivity.inventory.internal.repository;

import com.positivity.inventory.internal.entity.TransferOrder;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/**
 * Repository for {@link TransferOrder} aggregates (odoo-parity C1, issue #1035). Lines are
 * cascade-managed through the aggregate root; there is no standalone line repository.
 */
public interface TransferOrderRepository
        extends JpaRepository<TransferOrder, UUID>, JpaSpecificationExecutor<TransferOrder> {}

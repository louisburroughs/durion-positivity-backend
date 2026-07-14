package com.positivity.catalog.internal.service;

import com.positivity.catalog.internal.client.InventoryClient;
import com.positivity.catalog.internal.repository.ExtInventoryAvailabilityReplicaRepository;
import com.positivity.catalog.internal.repository.ExtProductLeadTimeReplicaRepository;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Replica-backed {@link InventoryClient} (ADR-0044 §6, #899): availability and lead time are
 * served from the {@code ext_inventory_availability} / {@code ext_product_lead_time} replicas
 * fed by {@code inventory.events.v1}, replacing the retired synchronous REST implementation.
 *
 * <p>A (sku, location) pair or product the feed has never reported resolves empty — the same
 * degrade behaviour callers already handled when the remote service was unavailable. Freshness:
 * values track the owner's ledger within normal event lag (seconds); they are display inputs,
 * never allocation decisions.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class InventoryAvailabilityReplicaService implements InventoryClient {

    private final ExtInventoryAvailabilityReplicaRepository extInventoryAvailabilityReplicaRepository;
    private final ExtProductLeadTimeReplicaRepository extProductLeadTimeReplicaRepository;

    @Override
    public Optional<AvailabilityClientResponse> fetchAvailability(String productSku, UUID locationId) {
        return extInventoryAvailabilityReplicaRepository
                .findByStockItemIdAndLocationId(productSku, locationId)
                .map(replica -> new AvailabilityClientResponse(
                        replica.getOnHandQuantity(),
                        replica.getAllocatedQuantity(),
                        replica.getAvailableToPromiseQuantity(),
                        replica.getUnitOfMeasure()));
    }

    @Override
    public Optional<LeadTimeClientResponse> fetchLeadTime(UUID productId, UUID locationId) {
        return extProductLeadTimeReplicaRepository
                .findById(productId)
                .map(replica -> new LeadTimeClientResponse(
                        replica.getMinDays(),
                        replica.getMaxDays(),
                        replica.getDisplayText(),
                        replica.getSource(),
                        replica.getConfidence(),
                        replica.getAsOf()));
    }
}

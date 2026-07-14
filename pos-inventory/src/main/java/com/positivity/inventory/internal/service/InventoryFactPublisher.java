package com.positivity.inventory.internal.service;

import com.positivity.domainevents.DomainEventEnvelope;
import com.positivity.domainevents.inventory.InventoryAvailabilityUpdatedV1;
import com.positivity.domainevents.inventory.LeadTimeUpdatedV1;
import com.positivity.domainevents.inventory.StorageLocationOnHandUpdatedV1;
import com.positivity.inventory.internal.config.OutboxEventWriter;
import com.positivity.inventory.internal.entity.InventoryLedgerEntry;
import com.positivity.inventory.internal.dto.AvailabilityView;
import com.positivity.inventory.internal.dto.LeadTimeView;
import com.positivity.inventory.internal.dto.LocationInventoryInquiryResponse;
import com.positivity.inventory.service.InventoryAvailabilityService;
import com.positivity.inventory.service.InventoryLeadTimeService;
import com.positivity.inventory.service.LocationInventoryInquiryService;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Publishes inventory facts to the transactional outbox (ADR-0044 §6, issue #899 Phase 5.3).
 *
 * <p>Ledger-writing services call {@link #markLedgerChanged} after appending entries; feed
 * ingest calls {@link #markLeadTimeChanged}. Keys are deduplicated per transaction and ONE
 * snapshot fact per touched (stockItem, location), storage location, and product is emitted at
 * {@code beforeCommit}, recomputed from the final persisted state. When Kafka publishing is
 * disabled the outbox writer bean is absent and every call is a no-op.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InventoryFactPublisher {

    private static final Object TX_RESOURCE_KEY = InventoryFactPublisher.class;

    private static final String SOURCE = "pos-inventory";

    private final ObjectProvider<OutboxEventWriter> outboxEventWriter;
    private final InventoryAvailabilityService inventoryAvailabilityService;
    private final LocationInventoryInquiryService locationInventoryInquiryService;
    private final InventoryLeadTimeService inventoryLeadTimeService;
    private final Clock clock;

    // Same property ManifestPublisher scans event_outbox by, so an override can never
    // desync the outbox rows from the manifest computation.
    @Value("${pos.inventory.kafka.events-topic:inventory.events.v1}")
    private String eventsTopic;

    /** Mark a ledger change for one stock item at one (possibly null) location. */
    public void markLedgerChanged(@NonNull String stockItemId, @Nullable UUID locationId) {
        Pending pending = pending();
        if (pending == null) {
            return;
        }
        pending.availabilityKeys.add(new AvailabilityKey(stockItemId, locationId));
        if (locationId != null) {
            pending.storageLocationIds.add(locationId);
        }
    }

    /** Mark ledger changes from just-persisted entries (covers from/to transfer locations). */
    public void markEntries(@NonNull Iterable<InventoryLedgerEntry> entries) {
        for (InventoryLedgerEntry entry : entries) {
            markEntry(entry);
        }
    }

    /** Mark a ledger change from one just-persisted entry. */
    public void markEntry(@NonNull InventoryLedgerEntry entry) {
        if (entry.getStockItemId() == null || entry.getStockItemId().isBlank()) {
            return;
        }
        markLedgerChanged(entry.getStockItemId(), entry.getLocationId());
        if (entry.getFromLocationId() != null) {
            markLedgerChanged(entry.getStockItemId(), entry.getFromLocationId());
        }
        if (entry.getToLocationId() != null) {
            markLedgerChanged(entry.getStockItemId(), entry.getToLocationId());
        }
    }

    /** Mark a normalized supply-feed change for one product. */
    public void markLeadTimeChanged(@NonNull UUID productId) {
        Pending pending = pending();
        if (pending == null) {
            return;
        }
        pending.leadTimeProductIds.add(productId);
    }

    private @Nullable Pending pending() {
        if (outboxEventWriter.getIfAvailable() == null
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            return null;
        }
        Pending pending = (Pending) TransactionSynchronizationManager.getResource(TX_RESOURCE_KEY);
        if (pending == null) {
            pending = new Pending();
            TransactionSynchronizationManager.bindResource(TX_RESOURCE_KEY, pending);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void beforeCommit(boolean readOnly) {
                    if (!readOnly) {
                        publishPending();
                    }
                }

                @Override
                public void afterCompletion(int status) {
                    TransactionSynchronizationManager.unbindResourceIfPossible(TX_RESOURCE_KEY);
                }
            });
        }
        return pending;
    }

    private void publishPending() {
        OutboxEventWriter writer = outboxEventWriter.getIfAvailable();
        Pending pending = (Pending) TransactionSynchronizationManager.getResource(TX_RESOURCE_KEY);
        if (writer == null || pending == null) {
            return;
        }
        for (AvailabilityKey key : pending.availabilityKeys) {
            try {
                AvailabilityView view =
                        inventoryAvailabilityService.queryAvailability(key.stockItemId(), key.locationId(), null, null);
                publish(
                        writer,
                        InventoryAvailabilityUpdatedV1.EVENT_TYPE,
                        availabilityAggregateId(key),
                        new InventoryAvailabilityUpdatedV1(
                                key.stockItemId(),
                                key.locationId(),
                                view.getOnHandQuantity(),
                                view.getAllocatedQuantity(),
                                view.getAvailableToPromiseQuantity(),
                                view.getUnitOfMeasure()));
            } catch (Exception e) {
                // A snapshot that cannot be computed must not roll back the business write;
                // the next mutation or a manifest replay repairs the replica.
                log.warn("Skipping availability fact for {}: {}", key, e.getMessage());
            }
        }
        for (UUID storageLocationId : pending.storageLocationIds) {
            try {
                LocationInventoryInquiryResponse inquiry =
                        locationInventoryInquiryService.getLocationInventory(storageLocationId, null);
                publish(
                        writer,
                        StorageLocationOnHandUpdatedV1.EVENT_TYPE,
                        storageLocationId,
                        new StorageLocationOnHandUpdatedV1(storageLocationId, inquiry.getOnHandQuantity()));
            } catch (Exception e) {
                log.warn("Skipping storage-location on-hand fact for {}: {}", storageLocationId, e.getMessage());
            }
        }
        for (UUID productId : pending.leadTimeProductIds) {
            try {
                LeadTimeView view = inventoryLeadTimeService.queryLeadTime(productId, null, null);
                publish(
                        writer,
                        LeadTimeUpdatedV1.EVENT_TYPE,
                        productId,
                        new LeadTimeUpdatedV1(
                                productId,
                                view.getMinDays(),
                                view.getMaxDays(),
                                view.getDisplayText(),
                                view.getSource(),
                                view.getConfidence(),
                                view.getAsOf()));
            } catch (Exception e) {
                log.warn("Skipping lead-time fact for {}: {}", productId, e.getMessage());
            }
        }
    }

    private void publish(
            @NonNull OutboxEventWriter writer, @NonNull String eventType, @NonNull UUID aggregateId, Object payload) {
        DomainEventEnvelope<Object> envelope = DomainEventEnvelope.of(
                eventType, 1, aggregateId, Instant.now(clock).toEpochMilli(), SOURCE, null, null, payload, clock);
        writer.publish(eventsTopic, envelope);
    }

    /** Deterministic aggregate identity for a (stockItemId, locationId) availability key. */
    private static UUID availabilityAggregateId(@NonNull AvailabilityKey key) {
        String raw = key.stockItemId() + ":" + (key.locationId() != null ? key.locationId() : "");
        return UUID.nameUUIDFromBytes(raw.getBytes(StandardCharsets.UTF_8));
    }

    private record AvailabilityKey(@NonNull String stockItemId, @Nullable UUID locationId) {}

    private static final class Pending {
        private final Set<AvailabilityKey> availabilityKeys = new LinkedHashSet<>();
        private final Set<UUID> storageLocationIds = new LinkedHashSet<>();
        private final Set<UUID> leadTimeProductIds = new LinkedHashSet<>();
    }
}

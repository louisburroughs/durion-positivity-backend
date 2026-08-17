package com.positivity.inventory.internal.service;

import com.positivity.domainevents.DomainEventEnvelope;
import com.positivity.domainevents.inventory.BackorderCreatedV1;
import com.positivity.domainevents.inventory.BackorderResolvedV1;
import com.positivity.domainevents.inventory.ConsumptionRecordedV1;
import com.positivity.domainevents.inventory.ExpectedSupplyDroppedV1;
import com.positivity.domainevents.inventory.InventoryAvailabilityUpdatedV1;
import com.positivity.domainevents.inventory.LeadTimeUpdatedV1;
import com.positivity.domainevents.inventory.LotExpiryAlertV1;
import com.positivity.domainevents.inventory.PickListUpdatedV1;
import com.positivity.domainevents.inventory.PickTaskUpdatedV1;
import com.positivity.domainevents.inventory.ProductValueChangedV1;
import com.positivity.domainevents.inventory.ReservationOutcomeV1;
import com.positivity.domainevents.inventory.ScrapPostedV1;
import com.positivity.domainevents.inventory.StorageLocationOnHandUpdatedV1;
import com.positivity.domainevents.inventory.TransferOrderUpdatedV1;
import com.positivity.inventory.internal.config.OutboxEventWriter;
import com.positivity.inventory.internal.dto.AvailabilityView;
import com.positivity.inventory.internal.dto.LeadTimeView;
import com.positivity.inventory.internal.dto.LocationInventoryInquiryResponse;
import com.positivity.inventory.internal.entity.InventoryLedgerEntry;
import com.positivity.inventory.internal.entity.PickListEntity;
import com.positivity.inventory.internal.entity.PickTaskEntity;
import com.positivity.inventory.internal.repository.PickListRepository;
import com.positivity.inventory.internal.repository.PickTaskRepository;
import com.positivity.inventory.service.InventoryAvailabilityService;
import com.positivity.inventory.service.InventoryLeadTimeService;
import com.positivity.inventory.service.LocationInventoryInquiryService;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
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
    private final PickListRepository pickListRepository;
    private final PickTaskRepository pickTaskRepository;
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

    /** Mark a pick-list state change; a snapshot fact is emitted at beforeCommit (#901). */
    public void markPickListChanged(@NonNull UUID pickListId) {
        Pending pending = pending();
        if (pending == null) {
            return;
        }
        pending.pickListIds.add(pickListId);
    }

    /** Mark a pick-task state change; a snapshot fact is emitted at beforeCommit (#901). */
    public void markPickTaskChanged(@NonNull UUID pickTaskId) {
        Pending pending = pending();
        if (pending == null) {
            return;
        }
        pending.pickTaskIds.add(pickTaskId);
    }

    /**
     * Record a consumption occurrence fact (#901). Unlike the snapshot facts this payload is
     * queued as-is: a consumption is an event, not state, and is never re-emitted.
     */
    public void recordConsumption(@NonNull ConsumptionRecordedV1 fact) {
        Pending pending = pending();
        if (pending == null) {
            return;
        }
        pending.consumptions.add(fact);
    }

    /**
     * Record an expected-supply-dropped occurrence fact (odoo-parity A4, issue #1028): a
     * purchase order reached CLOSED/CANCELLED with open quantity. Queued as-is — this is an
     * occurrence, not a snapshot, and is never re-emitted.
     */
    public void recordExpectedSupplyDropped(@NonNull ExpectedSupplyDroppedV1 fact) {
        Pending pending = pending();
        if (pending == null) {
            return;
        }
        pending.expectedSupplyDrops.add(fact);
    }

    /**
     * Record a scrap-posted occurrence fact (odoo-parity D1, issue #1030): a scrap document's
     * {@code SCRAP_OUT} entry reached the ledger. Queued as-is — this is an occurrence, not a
     * snapshot, and is never re-emitted. pos-accounting consumes it for shrinkage GL posting.
     */
    public void recordScrapPosted(@NonNull ScrapPostedV1 fact) {
        Pending pending = pending();
        if (pending == null) {
            return;
        }
        pending.scrapPosts.add(fact);
    }

    /**
     * Record a product-value-changed occurrence fact (odoo-parity J4, issue #1054): a manual cost
     * revaluation was applied to a SKU's cost state. Queued as-is — this is an occurrence, not a
     * snapshot, and is never re-emitted. pos-accounting consumes it to post the revaluation JE.
     */
    public void recordProductValueChanged(@NonNull ProductValueChangedV1 fact) {
        Pending pending = pending();
        if (pending == null) {
            return;
        }
        pending.productValueChanges.add(fact);
    }

    /**
     * Record a transfer-order lifecycle occurrence fact (odoo-parity C1, issue #1035): one fact
     * per state change (create, approve, dispatch, receive, short-close, cancel). Queued as-is —
     * this is an occurrence, not a snapshot, and is never re-emitted.
     */
    public void recordTransferOrderUpdated(@NonNull TransferOrderUpdatedV1 fact) {
        Pending pending = pending();
        if (pending == null) {
            return;
        }
        pending.transferOrderUpdates.add(fact);
    }

    /**
     * Record a backorder-created occurrence fact (odoo-parity G1, issue #1046): a backorder was
     * opened for short demand. Queued as-is — this is an occurrence, not a snapshot, and is never
     * re-emitted. pos-workorder consumes it read-side for workorder-line shortage visibility.
     */
    public void recordBackorderCreated(@NonNull BackorderCreatedV1 fact) {
        Pending pending = pending();
        if (pending == null) {
            return;
        }
        pending.backorderCreations.add(fact);
    }

    /**
     * Record a backorder-resolved occurrence fact (odoo-parity G1, issue #1046): an open backorder
     * was resolved. Queued as-is — this is an occurrence, not a snapshot, and is never re-emitted; a
     * backorder resolves at most once, so the fact is emitted exactly once per backorder.
     */
    public void recordBackorderResolved(@NonNull BackorderResolvedV1 fact) {
        Pending pending = pending();
        if (pending == null) {
            return;
        }
        pending.backorderResolutions.add(fact);
    }

    /**
     * Record a reservation-request outcome fact (CAP #1315): the requesting module's demand line
     * either is currently covered by owned ATP, or was not and a backorder was opened for the
     * shortfall. Queued as-is — one outcome per reservation-request command processed.
     */
    public void recordReservationOutcome(@NonNull ReservationOutcomeV1 fact) {
        Pending pending = pending();
        if (pending == null) {
            return;
        }
        pending.reservationOutcomes.add(fact);
    }

    /**
     * Record a lot-expiry alert occurrence fact (odoo-parity E3, issue #1047): the daily
     * {@code LotExpiryScheduler} raised an EXPIRING/EXPIRED transition for a lot. Queued as-is —
     * this is an occurrence, not a snapshot, and the scheduler's emit-once bookkeeping guarantees
     * exactly one fact per lot per transition.
     */
    public void recordLotExpiryAlert(@NonNull LotExpiryAlertV1 fact) {
        Pending pending = pending();
        if (pending == null) {
            return;
        }
        pending.lotExpiryAlerts.add(fact);
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
                        InventoryAvailabilityUpdatedV1.SCHEMA_VERSION,
                        availabilityAggregateId(key),
                        new InventoryAvailabilityUpdatedV1(
                                key.stockItemId(),
                                key.locationId(),
                                view.getOnHandQuantity(),
                                view.getAllocatedQuantity(),
                                view.getAvailableToPromiseQuantity(),
                                view.getUnitOfMeasure(),
                                zeroIfNull(view.getIncomingQty()),
                                zeroIfNull(view.getOutgoingQty()),
                                zeroIfNull(view.getProjectedAvailable())));
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
                        StorageLocationOnHandUpdatedV1.SCHEMA_VERSION,
                        storageLocationId,
                        new StorageLocationOnHandUpdatedV1(
                                storageLocationId, Math.toIntExact(inquiry.getOnHandQuantity())));
            } catch (Exception e) {
                log.warn("Skipping storage-location on-hand fact for {}: {}", storageLocationId, e.getMessage());
            }
        }
        for (UUID pickListId : pending.pickListIds) {
            try {
                PickListEntity pickList =
                        pickListRepository.findById(pickListId).orElse(null);
                if (pickList == null) {
                    continue;
                }
                publish(
                        writer,
                        PickListUpdatedV1.EVENT_TYPE,
                        PickListUpdatedV1.SCHEMA_VERSION,
                        pickListId,
                        new PickListUpdatedV1(
                                pickListId,
                                pickList.getWorkorderId(),
                                pickList.getStatus().name(),
                                pickList.getPriority(),
                                pickList.getDueAt(),
                                pickList.getCreatedAt()));
            } catch (Exception e) {
                log.warn("Skipping pick-list fact for {}: {}", pickListId, e.getMessage());
            }
        }
        for (UUID pickTaskId : pending.pickTaskIds) {
            try {
                PickTaskEntity task = pickTaskRepository.findById(pickTaskId).orElse(null);
                if (task == null) {
                    continue;
                }
                PickListEntity owning = task.getPickList();
                publish(
                        writer,
                        PickTaskUpdatedV1.EVENT_TYPE,
                        PickTaskUpdatedV1.SCHEMA_VERSION,
                        pickTaskId,
                        new PickTaskUpdatedV1(
                                pickTaskId,
                                owning == null ? null : owning.getPickListId(),
                                owning == null ? null : owning.getWorkorderId(),
                                task.getProductId(),
                                task.getSuggestedLocationId(),
                                task.getQuantityRequired(),
                                task.getQuantityPicked(),
                                task.getStatus().name(),
                                task.getSortOrder()));
            } catch (Exception e) {
                log.warn("Skipping pick-task fact for {}: {}", pickTaskId, e.getMessage());
            }
        }
        for (ConsumptionRecordedV1 consumption : pending.consumptions) {
            try {
                publish(
                        writer,
                        ConsumptionRecordedV1.EVENT_TYPE,
                        ConsumptionRecordedV1.SCHEMA_VERSION,
                        consumption.consumptionId(),
                        consumption);
            } catch (Exception e) {
                log.warn("Skipping consumption fact for {}: {}", consumption.consumptionId(), e.getMessage());
            }
        }
        for (ExpectedSupplyDroppedV1 drop : pending.expectedSupplyDrops) {
            try {
                publish(
                        writer,
                        ExpectedSupplyDroppedV1.EVENT_TYPE,
                        ExpectedSupplyDroppedV1.SCHEMA_VERSION,
                        drop.poId(),
                        drop);
            } catch (Exception e) {
                log.warn("Skipping expected-supply-dropped fact for {}: {}", drop.poId(), e.getMessage());
            }
        }
        for (ScrapPostedV1 scrapPost : pending.scrapPosts) {
            try {
                publish(writer, ScrapPostedV1.EVENT_TYPE, ScrapPostedV1.SCHEMA_VERSION, scrapPost.scrapId(), scrapPost);
            } catch (Exception e) {
                log.warn("Skipping scrap-posted fact for {}: {}", scrapPost.scrapId(), e.getMessage());
            }
        }
        for (ProductValueChangedV1 valueChange : pending.productValueChanges) {
            try {
                publish(
                        writer,
                        ProductValueChangedV1.EVENT_TYPE,
                        ProductValueChangedV1.SCHEMA_VERSION,
                        valueChange.revaluationId(),
                        valueChange);
            } catch (Exception e) {
                log.warn("Skipping product-value-changed fact for {}: {}", valueChange.revaluationId(), e.getMessage());
            }
        }
        for (TransferOrderUpdatedV1 transferUpdate : pending.transferOrderUpdates) {
            try {
                publish(
                        writer,
                        TransferOrderUpdatedV1.EVENT_TYPE,
                        TransferOrderUpdatedV1.SCHEMA_VERSION,
                        transferUpdate.transferOrderId(),
                        transferUpdate);
            } catch (Exception e) {
                log.warn("Skipping transfer-order fact for {}: {}", transferUpdate.transferOrderId(), e.getMessage());
            }
        }
        for (BackorderCreatedV1 backorderCreated : pending.backorderCreations) {
            try {
                publish(
                        writer,
                        BackorderCreatedV1.EVENT_TYPE,
                        BackorderCreatedV1.SCHEMA_VERSION,
                        backorderCreated.backorderId(),
                        backorderCreated);
            } catch (Exception e) {
                log.warn("Skipping backorder-created fact for {}: {}", backorderCreated.backorderId(), e.getMessage());
            }
        }
        for (BackorderResolvedV1 backorderResolved : pending.backorderResolutions) {
            try {
                publish(
                        writer,
                        BackorderResolvedV1.EVENT_TYPE,
                        BackorderResolvedV1.SCHEMA_VERSION,
                        backorderResolved.backorderId(),
                        backorderResolved);
            } catch (Exception e) {
                log.warn(
                        "Skipping backorder-resolved fact for {}: {}", backorderResolved.backorderId(), e.getMessage());
            }
        }
        for (ReservationOutcomeV1 reservationOutcome : pending.reservationOutcomes) {
            try {
                publish(
                        writer,
                        ReservationOutcomeV1.EVENT_TYPE,
                        ReservationOutcomeV1.SCHEMA_VERSION,
                        reservationOutcome.reservationId(),
                        reservationOutcome);
            } catch (Exception e) {
                log.warn(
                        "Skipping reservation-outcome fact for {}: {}",
                        reservationOutcome.reservationId(),
                        e.getMessage());
            }
        }
        for (LotExpiryAlertV1 lotExpiryAlert : pending.lotExpiryAlerts) {
            try {
                publish(
                        writer,
                        LotExpiryAlertV1.EVENT_TYPE,
                        LotExpiryAlertV1.SCHEMA_VERSION,
                        lotExpiryAlert.lotId(),
                        lotExpiryAlert);
            } catch (Exception e) {
                log.warn("Skipping lot-expiry-alert fact for {}: {}", lotExpiryAlert.lotId(), e.getMessage());
            }
        }
        for (UUID productId : pending.leadTimeProductIds) {
            try {
                LeadTimeView view = inventoryLeadTimeService.queryLeadTime(productId, null, null);
                publish(
                        writer,
                        LeadTimeUpdatedV1.EVENT_TYPE,
                        LeadTimeUpdatedV1.SCHEMA_VERSION,
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
            @NonNull OutboxEventWriter writer,
            @NonNull String eventType,
            int schemaVersion,
            @NonNull UUID aggregateId,
            Object payload) {
        DomainEventEnvelope<Object> envelope = DomainEventEnvelope.of(
                eventType,
                schemaVersion,
                aggregateId,
                Instant.now(clock).toEpochMilli(),
                SOURCE,
                null,
                null,
                payload,
                clock);
        writer.publish(eventsTopic, envelope);
    }

    private static long zeroIfNull(@Nullable Long value) {
        return value == null ? 0L : value;
    }

    /** Deterministic aggregate identity for a (stockItemId, locationId) availability key. */
    private static UUID availabilityAggregateId(@NonNull AvailabilityKey key) {
        String raw = key.stockItemId() + ":" + (key.locationId() != null ? key.locationId() : "");
        return UUID.nameUUIDFromBytes(raw.getBytes(StandardCharsets.UTF_8));
    }

    private record AvailabilityKey(
            @NonNull String stockItemId, @Nullable UUID locationId) {}

    private static final class Pending {
        private final Set<AvailabilityKey> availabilityKeys = new LinkedHashSet<>();
        private final Set<UUID> storageLocationIds = new LinkedHashSet<>();
        private final Set<UUID> leadTimeProductIds = new LinkedHashSet<>();
        private final Set<UUID> pickListIds = new LinkedHashSet<>();
        private final Set<UUID> pickTaskIds = new LinkedHashSet<>();
        private final List<ConsumptionRecordedV1> consumptions = new ArrayList<>();
        private final List<ExpectedSupplyDroppedV1> expectedSupplyDrops = new ArrayList<>();
        private final List<ScrapPostedV1> scrapPosts = new ArrayList<>();
        private final List<ProductValueChangedV1> productValueChanges = new ArrayList<>();
        private final List<TransferOrderUpdatedV1> transferOrderUpdates = new ArrayList<>();
        private final List<BackorderCreatedV1> backorderCreations = new ArrayList<>();
        private final List<BackorderResolvedV1> backorderResolutions = new ArrayList<>();
        private final List<ReservationOutcomeV1> reservationOutcomes = new ArrayList<>();
        private final List<LotExpiryAlertV1> lotExpiryAlerts = new ArrayList<>();
    }
}

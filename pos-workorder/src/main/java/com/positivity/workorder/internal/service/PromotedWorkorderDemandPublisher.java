package com.positivity.workorder.internal.service;

import com.positivity.workorder.internal.config.InventoryCommandPublisher;
import com.positivity.workorder.internal.entity.Workorder;
import com.positivity.workorder.internal.entity.WorkorderPart;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Registers a promoted workorder's parts demand with pos-inventory (issues #1479 and #1481).
 *
 * <p>Promotion used to create a workorder with its part lines and stop there. Nothing reserved
 * against those lines and nothing generated a pick list, with two consequences that share one
 * root:
 *
 * <ul>
 *   <li>#1479 — {@code GET /v1/workorders/{id}/pick-list/tasks} answered 404 forever, so a part on
 *       a workorder could not be picked or consumed through the documented flow.
 *   <li>#1481 — a part line whose SKU had no stock raised no signal anywhere. Reserving is what
 *       discovers the shortfall, and pos-inventory opens the backorder for it as part of handling
 *       the reservation request.
 * </ul>
 *
 * <p>Two commands go out per promotion, both on {@code inventory.commands.v1} (ADR-0044 §4): one
 * reservation request per part line, which registers the demand and either covers it or opens a
 * backorder naming the SKU, the location and the shortfall; and one pick-list generate request for
 * the workorder, which produces the list and its tasks. The reservation outcome fact comes back on
 * {@code inventory.events.v1} and stamps {@code reservationId}/{@code backorderId} onto the part
 * line, which is what makes the shortage reachable from the workorder id rather than only from a
 * SKU the caller would have to know in advance.
 *
 * <h2>Published after commit, and never fatal</h2>
 *
 * The commands go out in {@code afterCommit}, so a workorder is never promoted on the strength of
 * a Kafka send, and a broker that is down cannot turn a valid promotion into a 500. A failed send
 * is logged: the workorder exists, its parts are on it, and the demand can be re-registered by
 * issuing the parts — which publishes the same reservation command.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PromotedWorkorderDemandPublisher {

    /** Promotion has no priority of its own; pick lists it generates start at the base. */
    private static final int BASE_PRIORITY = 0;

    private final ObjectProvider<InventoryCommandPublisher> inventoryCommandPublisher;

    /**
     * Registers the workorder's part demand once the promoting transaction commits.
     *
     * @param workorder the promoted workorder; needs an id and a servicing site to be actionable
     * @param parts the part lines copied onto it, each needing a product to be actionable
     */
    public void registerPartsDemand(@NonNull Workorder workorder, @NonNull List<WorkorderPart> parts) {
        List<WorkorderPart> actionable = parts.stream()
                .filter(part -> part.getId() != null)
                .filter(part -> part.getProductEntityId() != null)
                .filter(part -> isPositive(part.getQuantity()))
                .toList();
        if (actionable.isEmpty()) {
            return;
        }
        if (workorder.getShopId() == null) {
            // Reservation and sourcing are both site-scoped; without a site there is nothing to
            // ask pos-inventory about. The workorder is still promoted — the assign page already
            // reports the missing shop rather than inventing one.
            log.warn(
                    "Workorder {} has no shop; skipping parts reservation and pick-list generation", workorder.getId());
            return;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            publish(workorder, actionable);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                publish(workorder, actionable);
            }
        });
    }

    private void publish(Workorder workorder, List<WorkorderPart> parts) {
        InventoryCommandPublisher publisher = inventoryCommandPublisher.getIfAvailable();
        if (publisher == null) {
            log.debug(
                    "Inventory command publisher unavailable; workorder {} parts demand not registered",
                    workorder.getId());
            return;
        }

        UUID locationId = workorder.getShopId();
        List<InventoryCommandPublisher.PickLine> pickLines = new ArrayList<>(parts.size());
        for (WorkorderPart part : parts) {
            try {
                publisher.requestReservation(
                        part.getId(), part.getProductEntityId(), part.getQuantity(), locationId, part.getUomCode());
            } catch (RuntimeException e) {
                // One line's send failing must not cost the other lines their demand registration.
                log.warn(
                        "Could not register demand for workorder {} part {}: {}",
                        workorder.getId(),
                        part.getId(),
                        e.getMessage());
            }
            pickLines.add(new InventoryCommandPublisher.PickLine(
                    part.getId(), part.getProductEntityId().toString(), part.getQuantity()));
        }

        try {
            publisher.requestPickListGeneration(
                    workorder.getId(), scheduledStartAt(workorder.getScheduledDate()), BASE_PRIORITY, pickLines);
        } catch (RuntimeException e) {
            log.warn("Could not request pick-list generation for workorder {}: {}", workorder.getId(), e.getMessage());
        }
    }

    /** The scheduled day as an instant, used only to order pick work; null when unscheduled. */
    private static @Nullable Instant scheduledStartAt(@Nullable LocalDate scheduledDate) {
        return scheduledDate == null
                ? null
                : scheduledDate.atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    private static boolean isPositive(@Nullable BigDecimal quantity) {
        return quantity != null && quantity.signum() > 0;
    }
}

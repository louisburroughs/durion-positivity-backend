package com.positivity.inventory.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import org.junit.jupiter.api.Test;

import com.positivity.inventory.internal.entity.InventoryLedgerEventType;

class InventoryLedgerEventTypeTest {

    @Test
    void onHandAffectingTypes_includesOnlyPhysicalMovementEvents() {
        Set<InventoryLedgerEventType> onHandTypes = InventoryLedgerEventType.onHandAffectingTypes();

        assertThat(onHandTypes).contains(
                InventoryLedgerEventType.GOODS_RECEIPT,
                InventoryLedgerEventType.GOODS_ISSUE,
                InventoryLedgerEventType.TRANSFER_IN,
                InventoryLedgerEventType.TRANSFER_OUT,
                InventoryLedgerEventType.ADJUSTMENT_IN,
                InventoryLedgerEventType.ADJUSTMENT_OUT,
                InventoryLedgerEventType.COUNT_VARIANCE_IN,
                InventoryLedgerEventType.COUNT_VARIANCE_OUT,
                InventoryLedgerEventType.ADJUST_CYCLE_COUNT,
                InventoryLedgerEventType.PUTAWAY,
                InventoryLedgerEventType.RETURN_TO_STOCK);

        assertThat(onHandTypes).doesNotContain(
                InventoryLedgerEventType.RESERVATION_CREATED,
                InventoryLedgerEventType.RESERVATION_RELEASED,
                InventoryLedgerEventType.ALLOCATION_CREATED,
                InventoryLedgerEventType.ALLOCATION_RELEASED,
                InventoryLedgerEventType.BACKORDER_CREATED,
                InventoryLedgerEventType.BACKORDER_RESOLVED,
                InventoryLedgerEventType.PICK_TASK_CREATED,
                InventoryLedgerEventType.PICK_TASK_COMPLETED);
    }

    @Test
    void onHandAffectingTypes_matchesAffectsOnHandFlagForAllEventTypes() {
        Set<InventoryLedgerEventType> onHandTypes = InventoryLedgerEventType.onHandAffectingTypes();

        assertThat(InventoryLedgerEventType.values())
                .allSatisfy(eventType -> assertThat(onHandTypes.contains(eventType))
                        .isEqualTo(eventType.affectsOnHand()));
    }
}

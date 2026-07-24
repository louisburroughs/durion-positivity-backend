package com.positivity.domainevents.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ConsumptionRecordedV1Test {

    private static final UUID CONSUMPTION_ID = UUID.fromString("01980002-0000-7000-8000-000000000010");
    private static final UUID WORKORDER_ID = UUID.fromString("01980002-0000-7000-8000-000000000020");
    private static final UUID PICK_TASK_ID = UUID.fromString("01980002-0000-7000-8000-000000000030");
    private static final UUID SKU_ID = UUID.fromString("01980002-0000-7000-8000-000000000040");
    private static final Instant CONSUMED_AT = Instant.parse("2026-07-23T12:00:00Z");

    @Test
    void carriesSchemaIdentity() {
        assertThat(ConsumptionRecordedV1.EVENT_TYPE).isEqualTo("inventory.consumption.recorded");
        // odoo-parity J3 (#1053): additive engine-cost fields bump the schema to v2.
        assertThat(ConsumptionRecordedV1.SCHEMA_VERSION).isEqualTo(2);
    }

    @Test
    void carriesEngineCostPerLine() {
        ConsumptionRecordedV1.Line line = new ConsumptionRecordedV1.Line(
                PICK_TASK_ID, SKU_ID, 3, new BigDecimal("4.2500"), new BigDecimal("12.7500"));
        ConsumptionRecordedV1 fact =
                new ConsumptionRecordedV1(CONSUMPTION_ID, WORKORDER_ID, null, 3, CONSUMED_AT, List.of(line), "ENGINE");

        assertThat(fact.costSource()).isEqualTo("ENGINE");
        assertThat(fact.lines()).singleElement().satisfies(l -> {
            assertThat(l.unitCost()).isEqualByComparingTo("4.25");
            assertThat(l.extendedCost()).isEqualByComparingTo("12.75");
        });
    }

    @Test
    void toleratesUncostedLinesAndNoneSource() {
        ConsumptionRecordedV1.Line line = new ConsumptionRecordedV1.Line(PICK_TASK_ID, SKU_ID, 3, null, null);
        ConsumptionRecordedV1 fact =
                new ConsumptionRecordedV1(CONSUMPTION_ID, WORKORDER_ID, null, 3, CONSUMED_AT, List.of(line), "NONE");

        assertThat(fact.costSource()).isEqualTo("NONE");
        assertThat(fact.lines().getFirst().unitCost()).isNull();
        assertThat(fact.lines().getFirst().extendedCost()).isNull();
    }

    @Test
    void nullLinesBecomeEmptyAndNullCostSourceAllowed() {
        ConsumptionRecordedV1 fact =
                new ConsumptionRecordedV1(CONSUMPTION_ID, WORKORDER_ID, null, 0, CONSUMED_AT, null, null);

        assertThat(fact.lines()).isEmpty();
        assertThat(fact.costSource()).isNull();
    }

    @Test
    void rejectsNullRequiredIds() {
        assertThatThrownBy(() -> new ConsumptionRecordedV1(null, WORKORDER_ID, null, 0, CONSUMED_AT, List.of(), "NONE"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("consumptionId");
        assertThatThrownBy(
                        () -> new ConsumptionRecordedV1(CONSUMPTION_ID, null, null, 0, CONSUMED_AT, List.of(), "NONE"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("workorderId");
    }
}

package com.positivity.supplier.internal.order.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.domainevents.supplier.SupplierOrderConfirmedLine;
import com.positivity.domainevents.supplier.SupplierOrderStatusChangedV1;
import com.positivity.domainevents.supplier.SupplierOrderStatusDespatch;
import com.positivity.domainevents.supplier.SupplierOrderStatusLine;
import com.positivity.domainevents.supplier.SupplierOrderStatusSchedule;
import com.positivity.supplier.internal.domain.model.SupplierDeliverySchedule;
import com.positivity.supplier.internal.domain.model.SupplierDespatch;
import com.positivity.supplier.internal.domain.model.SupplierOrderResult;
import com.positivity.supplier.internal.domain.model.SupplierOrderStatusResult;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Field-for-field translation from the canonical supplier model to the cross-module event
 * payloads (ADR-0049 §3, ADR-0044). The mapper promises nothing is invented in translation —
 * these tests pin that a missing value in the canonical model stays missing in the event, not
 * defaulted, and that every field lands in the right place rather than merely "some value".
 */
@DisplayName("OrderEventMapper — canonical model to event payload translation")
class OrderEventMapperTest {

    private static final SupplierDespatch DESPATCH =
            new SupplierDespatch(LocalDate.of(2026, 8, 1), "ASN-1", "ASN-1-L1", 4);

    private static final SupplierDeliverySchedule SCHEDULE =
            new SupplierDeliverySchedule(LocalDate.of(2026, 8, 5), 4, 1, 2, 0, 7, 4, List.of(DESPATCH));

    @Test
    @DisplayName("toEventStatusLines carries every field verbatim, schedules included")
    void toEventStatusLinesCarriesEveryField() {
        SupplierOrderStatusResult.Line line = new SupplierOrderStatusResult.Line(
                "L1",
                "CLI-1",
                "0000000000001",
                "VEND-1",
                "BUY-1",
                "Tyre",
                10,
                LocalDate.of(2026, 8, 3),
                List.of(SCHEDULE),
                "E1",
                "vendor error text");

        List<SupplierOrderStatusLine> result = OrderEventMapper.toEventStatusLines(List.of(line));

        assertThat(result).hasSize(1);
        SupplierOrderStatusLine event = result.get(0);
        assertThat(event.lineId()).isEqualTo("L1");
        assertThat(event.customerLineItemNumber()).isEqualTo("CLI-1");
        assertThat(event.articleEan()).isEqualTo("0000000000001");
        assertThat(event.supplierArticleCode()).isEqualTo("VEND-1");
        assertThat(event.buyersArticleId()).isEqualTo("BUY-1");
        assertThat(event.description()).isEqualTo("Tyre");
        assertThat(event.orderedQuantity()).isEqualTo(10);
        assertThat(event.requestedDeliveryDate()).isEqualTo(LocalDate.of(2026, 8, 3));
        assertThat(event.vendorErrorCode()).isEqualTo("E1");
        assertThat(event.vendorErrorText()).isEqualTo("vendor error text");
        assertThat(event.schedules()).hasSize(1);
        assertThat(event.schedules().get(0).deliveryDate()).isEqualTo(LocalDate.of(2026, 8, 5));
    }

    @Test
    @DisplayName("toEventStatusLines maps an empty line list to an empty event list")
    void toEventStatusLinesHandlesEmptyInput() {
        assertThat(OrderEventMapper.toEventStatusLines(List.of())).isEmpty();
    }

    @Test
    @DisplayName("toEventConfirmedLines carries ordered/confirmed/killed quantities separately")
    void toEventConfirmedLinesCarriesEveryField() {
        SupplierOrderResult.Line line = new SupplierOrderResult.Line(
                "L2", "0000000000002", "VEND-2", 5, 3, 2, List.of(SCHEDULE), "E2", "vendor rejected 2");

        List<SupplierOrderConfirmedLine> result = OrderEventMapper.toEventConfirmedLines(List.of(line));

        assertThat(result).hasSize(1);
        SupplierOrderConfirmedLine event = result.get(0);
        assertThat(event.lineId()).isEqualTo("L2");
        assertThat(event.articleEan()).isEqualTo("0000000000002");
        assertThat(event.supplierArticleCode()).isEqualTo("VEND-2");
        assertThat(event.orderedQuantity()).isEqualTo(5);
        assertThat(event.confirmedQuantity()).isEqualTo(3);
        assertThat(event.killedQuantity()).isEqualTo(2);
        assertThat(event.vendorErrorCode()).isEqualTo("E2");
        assertThat(event.vendorErrorText()).isEqualTo("vendor rejected 2");
        assertThat(event.schedules()).hasSize(1);
    }

    @Test
    @DisplayName("toEventConfirmedLinesFromStatus never invents a killedQuantity — it is always null")
    void toEventConfirmedLinesFromStatusNeverStatesKilledQuantity() {
        SupplierOrderStatusResult.Line line = new SupplierOrderStatusResult.Line(
                "L3", null, null, null, null, null, 6, null, List.of(SCHEDULE), null, null);

        List<SupplierOrderConfirmedLine> result = OrderEventMapper.toEventConfirmedLinesFromStatus(List.of(line));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).killedQuantity()).isNull();
        assertThat(result.get(0).orderedQuantity()).isEqualTo(6);
    }

    @Test
    @DisplayName("toEventConfirmedLinesFromStatus totals confirmed quantity across every schedule")
    void toEventConfirmedLinesFromStatusTotalsConfirmedQuantityAcrossSchedules() {
        SupplierDeliverySchedule first = new SupplierDeliverySchedule(null, 3, null, null, null, null, null, List.of());
        SupplierDeliverySchedule second =
                new SupplierDeliverySchedule(null, 4, null, null, null, null, null, List.of());
        SupplierOrderStatusResult.Line line = new SupplierOrderStatusResult.Line(
                "L4", null, null, null, null, null, null, null, List.of(first, second), null, null);

        List<SupplierOrderConfirmedLine> result = OrderEventMapper.toEventConfirmedLinesFromStatus(List.of(line));

        assertThat(result.get(0).confirmedQuantity()).isEqualTo(7);
    }

    @Test
    @DisplayName("toEventConfirmedLinesFromStatus leaves confirmedQuantity null, not zero, when no schedule stated one")
    void toEventConfirmedLinesFromStatusLeavesConfirmedQuantityNullWhenUnstated() {
        SupplierDeliverySchedule noQuantity =
                new SupplierDeliverySchedule(null, null, null, null, null, null, null, List.of());
        SupplierOrderStatusResult.Line line = new SupplierOrderStatusResult.Line(
                "L5", null, null, null, null, null, null, null, List.of(noQuantity), null, null);

        List<SupplierOrderConfirmedLine> result = OrderEventMapper.toEventConfirmedLinesFromStatus(List.of(line));

        assertThat(result.get(0).confirmedQuantity()).isNull();
    }

    @Test
    @DisplayName("toEventConfirmedLinesFromStatus with no schedules at all totals to null, not zero")
    void toEventConfirmedLinesFromStatusTotalsNullWithNoSchedules() {
        SupplierOrderStatusResult.Line line = new SupplierOrderStatusResult.Line(
                "L6", null, null, null, null, null, null, null, List.of(), null, null);

        List<SupplierOrderConfirmedLine> result = OrderEventMapper.toEventConfirmedLinesFromStatus(List.of(line));

        assertThat(result.get(0).confirmedQuantity()).isNull();
        assertThat(result.get(0).schedules()).isEmpty();
    }

    @Test
    @DisplayName("toEventSchedules carries every quantity field and nested despatches")
    void toEventSchedulesCarriesEveryField() {
        List<SupplierOrderStatusSchedule> result = OrderEventMapper.toEventSchedules(List.of(SCHEDULE));

        assertThat(result).hasSize(1);
        SupplierOrderStatusSchedule event = result.get(0);
        assertThat(event.deliveryDate()).isEqualTo(LocalDate.of(2026, 8, 5));
        assertThat(event.confirmedQuantity()).isEqualTo(4);
        assertThat(event.cancelledQuantity()).isEqualTo(1);
        assertThat(event.openQuantity()).isEqualTo(2);
        assertThat(event.backorderedQuantity()).isEqualTo(0);
        assertThat(event.scheduledQuantity()).isEqualTo(7);
        assertThat(event.shippedQuantity()).isEqualTo(4);
        assertThat(event.despatches()).hasSize(1);
    }

    @Test
    @DisplayName("toEventDespatches keeps the despatch advice reference verbatim, never parsed")
    void toEventDespatchesCarriesEveryField() {
        List<SupplierOrderStatusDespatch> result = OrderEventMapper.toEventDespatches(List.of(DESPATCH));

        assertThat(result).hasSize(1);
        SupplierOrderStatusDespatch event = result.get(0);
        assertThat(event.despatchDate()).isEqualTo(LocalDate.of(2026, 8, 1));
        assertThat(event.despatchAdviceDocumentId()).isEqualTo("ASN-1");
        assertThat(event.despatchAdviceLineId()).isEqualTo("ASN-1-L1");
        assertThat(event.despatchedQuantity()).isEqualTo(4);
    }

    @Test
    @DisplayName("toEventDespatches maps an empty despatch list to an empty event list")
    void toEventDespatchesHandlesEmptyInput() {
        assertThat(OrderEventMapper.toEventDespatches(List.of())).isEmpty();
    }

    @Test
    @DisplayName("toEventStatus maps every canonical status to its event counterpart, one-for-one")
    void toEventStatusMapsEveryEnumValue() {
        assertThat(OrderEventMapper.toEventStatus(SupplierOrderStatusResult.Status.NOT_FOUND))
                .isEqualTo(SupplierOrderStatusChangedV1.Status.NOT_FOUND);
        assertThat(OrderEventMapper.toEventStatus(SupplierOrderStatusResult.Status.IN_PROGRESS))
                .isEqualTo(SupplierOrderStatusChangedV1.Status.IN_PROGRESS);
        assertThat(OrderEventMapper.toEventStatus(SupplierOrderStatusResult.Status.CONFIRMED))
                .isEqualTo(SupplierOrderStatusChangedV1.Status.CONFIRMED);
        assertThat(OrderEventMapper.toEventStatus(SupplierOrderStatusResult.Status.REJECTED))
                .isEqualTo(SupplierOrderStatusChangedV1.Status.REJECTED);
    }
}

package com.positivity.order.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.domainevents.workorder.EstimateUpdatedV1;
import com.positivity.domainevents.workorder.WorkorderUpdatedV1;
import com.positivity.order.internal.entity.ExtEstimate;
import com.positivity.order.internal.entity.ExtEstimateLine;
import com.positivity.order.internal.entity.ExtWorkorder;
import com.positivity.order.internal.entity.ExtWorkorderLine;
import com.positivity.order.internal.repository.ExtEstimateLineRepository;
import com.positivity.order.internal.repository.ExtEstimateRepository;
import com.positivity.order.internal.repository.ExtWorkorderLineRepository;
import com.positivity.order.internal.repository.ExtWorkorderRepository;
import com.positivity.order.internal.repository.ProcessedEventRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import tools.jackson.databind.ObjectMapper;

/**
 * Unit tests for {@link WorkorderEventsListener} (parity story E1, spec R7.1).
 *
 * <p>
 * The source-document import reads these replicas to build an order, so what
 * matters is that a snapshot is a <em>replacement</em>: every fact fully
 * replaces the document's line set, because a line removed upstream would
 * otherwise linger and be imported onto an order that should not contain it. The
 * aggregate-version guard is the other half — Kafka only orders within a
 * partition, so an older snapshot arriving late must not overwrite newer prices.
 *
 * <p>
 * The delivery contract this shares with the other replica listeners is covered
 * once in {@link ReplicaListenerContractTest}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("WorkorderEventsListener — workorder and estimate replicas")
class WorkorderEventsListenerTest {

    private static final UUID WORKORDER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000e1");
    private static final UUID ESTIMATE_ID = UUID.fromString("00000000-0000-0000-0000-0000000000e2");
    private static final UUID CUSTOMER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000e3");
    private static final UUID VEHICLE_ID = UUID.fromString("00000000-0000-0000-0000-0000000000e4");
    private static final UUID LINE_ID = UUID.fromString("00000000-0000-0000-0000-0000000000e5");
    private static final UUID SERVICE_LINE_ID = UUID.fromString("00000000-0000-0000-0000-0000000000e6");
    private static final UUID ITEM_ID = UUID.fromString("00000000-0000-0000-0000-0000000000e7");
    private static final UUID PRODUCT_ID = UUID.fromString("00000000-0000-0000-0000-0000000000e8");
    private static final Instant NOW = Instant.parse("2026-08-11T09:00:00Z");

    @Mock
    private ProcessedEventRepository processedEventRepository;

    @Mock
    private ExtWorkorderRepository extWorkorderRepository;

    @Mock
    private ExtWorkorderLineRepository extWorkorderLineRepository;

    @Mock
    private ExtEstimateRepository extEstimateRepository;

    @Mock
    private ExtEstimateLineRepository extEstimateLineRepository;

    private WorkorderEventsListener listener;

    @BeforeEach
    void setUp() {
        listener = new WorkorderEventsListener(
                Clock.fixed(NOW, ZoneOffset.UTC),
                new ObjectMapper(),
                processedEventRepository,
                extWorkorderRepository,
                extWorkorderLineRepository,
                extEstimateRepository,
                extEstimateLineRepository);
        when(processedEventRepository.existsById(any())).thenReturn(false);
        when(extWorkorderRepository.findById(any())).thenReturn(Optional.empty());
        when(extEstimateRepository.findById(any())).thenReturn(Optional.empty());
    }

    private static String workorderEnvelope(long version) {
        return """
                {"eventId":"evt-1","eventType":"%s","aggregateVersion":%d,
                 "payload":{"workorderId":"%s","workorderNumber":"WO-1001","status":"CLOSED",
                   "customerId":"%s","vehicleId":"%s","shopId":null,"invoiceId":null,
                   "parts":[{"workorderLineId":"%s","productEntityId":"%s","description":"Brake pad",
                     "quantity":2,"unitPrice":45.50,"lineTotal":91.00,"declined":false,"returnable":true}],
                   "services":[{"workorderLineId":"%s","description":"Labor",
                     "quantity":1.5,"unitPrice":100,"lineTotal":150,"declined":true}]}}
                """.formatted(
                        WorkorderUpdatedV1.EVENT_TYPE,
                        version,
                        WORKORDER_ID,
                        CUSTOMER_ID,
                        VEHICLE_ID,
                        LINE_ID,
                        PRODUCT_ID,
                        SERVICE_LINE_ID);
    }

    private static String estimateEnvelope(long version) {
        return """
                {"eventId":"evt-2","eventType":"%s","aggregateVersion":%d,
                 "payload":{"estimateId":"%s","estimateNumber":"EST-7","status":"APPROVED",
                   "customerId":"%s","vehicleId":"%s","locationId":null,
                   "items":[{"estimateItemId":"%s","itemType":"PART","description":"Filter",
                     "quantity":1,"unitPrice":19.99,"lineTotal":19.99,"productId":"%s",
                     "serviceId":null,"approvalStatus":"APPROVED","deleted":false}]}}
                """.formatted(
                EstimateUpdatedV1.EVENT_TYPE, version, ESTIMATE_ID, CUSTOMER_ID, VEHICLE_ID, ITEM_ID, PRODUCT_ID);
    }

    @Test
    @DisplayName("replicates a workorder header and both line kinds")
    void replicatesWorkorderAndLines() {
        listener.onWorkorderEvent(workorderEnvelope(3));

        ArgumentCaptor<ExtWorkorder> header = ArgumentCaptor.forClass(ExtWorkorder.class);
        verify(extWorkorderRepository).save(header.capture());
        assertThat(header.getValue().getWorkorderNumber()).isEqualTo("WO-1001");
        assertThat(header.getValue().getStatus()).isEqualTo("CLOSED");
        assertThat(header.getValue().getCustomerId()).isEqualTo(CUSTOMER_ID);
        assertThat(header.getValue().getVehicleId()).isEqualTo(VEHICLE_ID);
        // A null id in the payload must land as null, not as a parse failure.
        assertThat(header.getValue().getShopId()).isNull();
        assertThat(header.getValue().getAggregateVersion()).isEqualTo(3);
        assertThat(header.getValue().getSyncedAt()).isEqualTo(NOW);

        ArgumentCaptor<ExtWorkorderLine> lines = ArgumentCaptor.forClass(ExtWorkorderLine.class);
        verify(extWorkorderLineRepository, org.mockito.Mockito.times(2)).save(lines.capture());
        assertThat(lines.getAllValues())
                .extracting(
                        ExtWorkorderLine::getLineId,
                        ExtWorkorderLine::getLineKind,
                        ExtWorkorderLine::getDescription,
                        ExtWorkorderLine::getDeclined)
                .containsExactly(
                        tuple(LINE_ID, ExtWorkorderLine.LineKind.PART, "Brake pad", false),
                        tuple(SERVICE_LINE_ID, ExtWorkorderLine.LineKind.SERVICE, "Labor", true));
        // A service line carries no product; the part line does.
        assertThat(lines.getAllValues().getFirst().getProductId()).isEqualTo(PRODUCT_ID);
        assertThat(lines.getAllValues().getFirst().getUnitPrice()).isEqualByComparingTo("45.50");
        assertThat(lines.getAllValues().getLast().getProductId()).isNull();
        assertThat(lines.getAllValues().getLast().getReturnable()).isNull();
    }

    @Test
    @DisplayName("replaces the whole line set on each snapshot rather than accumulating")
    void linesAreFullyReplaced() {
        listener.onWorkorderEvent(workorderEnvelope(3));

        // Delete precedes the inserts: a line removed upstream must not survive the refresh.
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(extWorkorderLineRepository);
        order.verify(extWorkorderLineRepository).deleteByWorkorderId(WORKORDER_ID);
        order.verify(extWorkorderLineRepository, org.mockito.Mockito.times(2)).save(any());
    }

    @Test
    @DisplayName("ignores a snapshot strictly older than the replica it already holds")
    void strictlyOlderWorkorderSnapshotIsIgnored() {
        ExtWorkorder existing = new ExtWorkorder();
        existing.setWorkorderId(WORKORDER_ID);
        existing.setAggregateVersion(5);
        when(extWorkorderRepository.findById(WORKORDER_ID)).thenReturn(Optional.of(existing));

        listener.onWorkorderEvent(workorderEnvelope(3));

        verify(extWorkorderRepository, never()).save(any());
        verify(extWorkorderLineRepository, never()).deleteByWorkorderId(any());
        // Still recorded: the event was consumed and correctly decided against.
        verify(processedEventRepository).save(any());
    }

    @Test
    @DisplayName("applies a workorder snapshot at the same version as the replica it already holds (#1486)")
    void workorderSnapshotAtTheSameVersionIsApplied() {
        // Load-bearing: pos-workorder's aggregateVersion strictly advances, so an equal version
        // means identical content, and a future regenerate-from-state replay would deliberately
        // resend a fact at the held version to repair a replica with wrong or missing rows.
        // Skipping on equal (the old `>=` guard) would silently turn that into a no-op (#1486).
        ExtWorkorder existing = new ExtWorkorder();
        existing.setWorkorderId(WORKORDER_ID);
        existing.setAggregateVersion(5);
        when(extWorkorderRepository.findById(WORKORDER_ID)).thenReturn(Optional.of(existing));

        listener.onWorkorderEvent(workorderEnvelope(5));

        verify(extWorkorderRepository).save(existing);
        assertThat(existing.getAggregateVersion()).isEqualTo(5);
    }

    @Test
    @DisplayName("updates the existing replica row in place when the snapshot is newer")
    void newerSnapshotUpdatesInPlace() {
        ExtWorkorder existing = new ExtWorkorder();
        existing.setWorkorderId(WORKORDER_ID);
        existing.setAggregateVersion(2);
        existing.setStatus("OPEN");
        when(extWorkorderRepository.findById(WORKORDER_ID)).thenReturn(Optional.of(existing));

        listener.onWorkorderEvent(workorderEnvelope(3));

        verify(extWorkorderRepository).save(existing);
        assertThat(existing.getStatus()).isEqualTo("CLOSED");
        assertThat(existing.getAggregateVersion()).isEqualTo(3);
    }

    @Test
    @DisplayName("replicates an estimate and its items")
    void replicatesEstimateAndItems() {
        listener.onWorkorderEvent(estimateEnvelope(4));

        ArgumentCaptor<ExtEstimate> header = ArgumentCaptor.forClass(ExtEstimate.class);
        verify(extEstimateRepository).save(header.capture());
        assertThat(header.getValue().getEstimateNumber()).isEqualTo("EST-7");
        assertThat(header.getValue().getStatus()).isEqualTo("APPROVED");
        assertThat(header.getValue().getLocationId()).isNull();
        assertThat(header.getValue().getAggregateVersion()).isEqualTo(4);

        ArgumentCaptor<ExtEstimateLine> items = ArgumentCaptor.forClass(ExtEstimateLine.class);
        verify(extEstimateLineRepository).deleteByEstimateId(ESTIMATE_ID);
        verify(extEstimateLineRepository).save(items.capture());
        assertThat(items.getValue().getItemId()).isEqualTo(ITEM_ID);
        assertThat(items.getValue().getItemType()).isEqualTo("PART");
        assertThat(items.getValue().getApprovalStatus()).isEqualTo("APPROVED");
        assertThat(items.getValue().getProductId()).isEqualTo(PRODUCT_ID);
        assertThat(items.getValue().getServiceId()).isNull();
        assertThat(items.getValue().getLineTotal()).isEqualByComparingTo("19.99");
    }

    @Test
    @DisplayName("ignores an estimate snapshot strictly older than the replica it already holds")
    void strictlyOlderEstimateSnapshotIsIgnored() {
        ExtEstimate existing = new ExtEstimate();
        existing.setEstimateId(ESTIMATE_ID);
        existing.setAggregateVersion(9);
        when(extEstimateRepository.findById(ESTIMATE_ID)).thenReturn(Optional.of(existing));

        listener.onWorkorderEvent(estimateEnvelope(4));

        verify(extEstimateRepository, never()).save(any());
        verify(extEstimateLineRepository, never()).deleteByEstimateId(any());
    }

    @Test
    @DisplayName("applies an estimate snapshot at the same version as the replica it already holds (#1486)")
    void estimateSnapshotAtTheSameVersionIsApplied() {
        // Load-bearing: pos-workorder's aggregateVersion strictly advances, so an equal version
        // means identical content, and a future regenerate-from-state replay would deliberately
        // resend a fact at the held version to repair a replica with wrong or missing rows.
        // Skipping on equal (the old `>=` guard) would silently turn that into a no-op (#1486).
        ExtEstimate existing = new ExtEstimate();
        existing.setEstimateId(ESTIMATE_ID);
        existing.setAggregateVersion(9);
        when(extEstimateRepository.findById(ESTIMATE_ID)).thenReturn(Optional.of(existing));

        listener.onWorkorderEvent(estimateEnvelope(9));

        ArgumentCaptor<ExtEstimate> header = ArgumentCaptor.forClass(ExtEstimate.class);
        verify(extEstimateRepository).save(header.capture());
        assertThat(header.getValue().getAggregateVersion()).isEqualTo(9);
    }
}

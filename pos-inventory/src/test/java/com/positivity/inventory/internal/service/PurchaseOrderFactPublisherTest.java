package com.positivity.inventory.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.positivity.domainevents.DomainEventEnvelope;
import com.positivity.domainevents.order.PurchaseOrderUpdatedV1;
import com.positivity.inventory.internal.config.OutboxEventWriter;
import com.positivity.inventory.internal.entity.PurchaseOrderEntity;
import com.positivity.inventory.internal.entity.PurchaseOrderLineEntity;
import com.positivity.inventory.internal.enums.PurchaseOrderStatus;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
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
import org.springframework.beans.factory.ObjectProvider;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Purchase-order facts for the ext_purchase_order projection (#1333)")
class PurchaseOrderFactPublisherTest {

    private static final UUID PO_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a01");
    private static final UUID VENDOR_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a02");
    private static final UUID SITE_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a03");
    private static final UUID SKU_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a04");
    private static final Instant NOW = Instant.parse("2026-08-15T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Mock
    private ObjectProvider<OutboxEventWriter> outboxProvider;

    @Mock
    private OutboxEventWriter outboxEventWriter;

    private PurchaseOrderFactPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new PurchaseOrderFactPublisher(outboxProvider, CLOCK);
        when(outboxProvider.getIfAvailable()).thenReturn(outboxEventWriter);
    }

    private static PurchaseOrderEntity order(PurchaseOrderStatus status, LocalDate expectedDelivery) {
        PurchaseOrderEntity po = new PurchaseOrderEntity();
        po.setPurchaseOrderId(PO_ID);
        po.setPoNumber("PO-2026-0001");
        po.setVendorId(VENDOR_ID);
        po.setStatus(status);
        po.setShipToLocationId(SITE_ID);
        po.setExpectedDeliveryDate(expectedDelivery);
        po.setCurrency("USD");
        po.setGrandTotalMinor(100_000L);
        po.setVersionNumber(3);
        return po;
    }

    private static PurchaseOrderLineEntity line(BigDecimal ordered, BigDecimal open) {
        PurchaseOrderLineEntity l = new PurchaseOrderLineEntity();
        l.setLineId(UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a05"));
        l.setLineNumber(1);
        l.setSkuId(SKU_ID);
        l.setQuantityDecimal(ordered);
        l.setOpenQuantityDecimal(open);
        l.setUnitCostMinor(5_000L);
        l.setDescription("Front brake rotor");
        return l;
    }

    private PurchaseOrderUpdatedV1 captured() {
        ArgumentCaptor<DomainEventEnvelope<?>> captor = ArgumentCaptor.forClass(DomainEventEnvelope.class);
        verify(outboxEventWriter).publish(eq("order.events.v1"), captor.capture());
        return (PurchaseOrderUpdatedV1) captor.getValue().payload();
    }

    @Test
    void publishesOnTheOrderDomainTopicNotTheInventoryOne() {
        publisher.publish(order(PurchaseOrderStatus.APPROVED, LocalDate.of(2026, 9, 1)), List.of(line(TEN(), TEN())));

        // The eventual owner's topic, emitted by the current owner. Publishing on
        // inventory.events.v1 would mean changing the contract when the aggregate moves, which is
        // the simultaneous rewiring this sequencing exists to avoid.
        verify(outboxEventWriter).publish(eq("order.events.v1"), any());
    }

    @Test
    void carriesEverythingAvailabilityToPromiseReads() {
        publisher.publish(
                order(PurchaseOrderStatus.APPROVED, LocalDate.of(2026, 9, 1)),
                List.of(line(TEN(), BigDecimal.valueOf(4))));

        PurchaseOrderUpdatedV1 fact = captured();
        // ExpectedSupplyServiceImpl sums open quantity for a SKU, filtered by order status and
        // ship-to site, bounded by expected delivery date. All five must survive the hop.
        assertThat(fact.status()).isEqualTo("APPROVED");
        assertThat(fact.shipToLocationId()).isEqualTo(SITE_ID);
        assertThat(fact.expectedDeliveryDate()).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(fact.lines()).singleElement().satisfies(l -> {
            assertThat(l.skuId()).isEqualTo(SKU_ID);
            assertThat(l.openQuantity()).isEqualByComparingTo(BigDecimal.valueOf(4));
        });
    }

    @Test
    void keepsOrderedAndOpenQuantitiesApart() {
        publisher.publish(
                order(PurchaseOrderStatus.PARTIALLY_RECEIVED, LocalDate.of(2026, 9, 1)),
                List.of(line(TEN(), BigDecimal.valueOf(4))));

        // Six of ten arrived. Supply still incoming is four; summing the ordered figure instead
        // would count the six already on the shelf a second time.
        assertThat(captured().lines().getFirst().orderedQuantity()).isEqualByComparingTo(TEN());
        assertThat(captured().lines().getFirst().openQuantity()).isEqualByComparingTo(BigDecimal.valueOf(4));
    }

    @Test
    void treatsAnUnreceivedLineAsFullyOutstandingRatherThanAsNothing() {
        PurchaseOrderLineEntity unreceived = line(TEN(), null);

        publisher.publish(order(PurchaseOrderStatus.APPROVED, LocalDate.of(2026, 9, 1)), List.of(unreceived));

        // A line never received against has a null open quantity. That means all of it is still
        // coming, not none of it — defaulting to zero would silently drop the whole order from ATP.
        assertThat(captured().lines().getFirst().openQuantity()).isEqualByComparingTo(TEN());
    }

    @Test
    void keepsAnUndatedOrderUndatedRatherThanDatingItToday() {
        publisher.publish(order(PurchaseOrderStatus.APPROVED, null), List.of(line(TEN(), TEN())));

        // The horizon-bounded supply query deliberately excludes undated orders. Substituting a
        // date here would pull an order with no promised delivery into every horizon.
        assertThat(captured().expectedDeliveryDate()).isNull();
    }

    @Test
    void keysTheEventOnTheOrderAndVersionsItByTheOptimisticLock() {
        publisher.publish(order(PurchaseOrderStatus.APPROVED, null), List.of(line(TEN(), TEN())));

        ArgumentCaptor<DomainEventEnvelope<?>> captor = ArgumentCaptor.forClass(DomainEventEnvelope.class);
        verify(outboxEventWriter).publish(eq("order.events.v1"), captor.capture());
        // Aggregate id keys the partition so one order's facts stay ordered; aggregateVersion is the
        // row's own version, so a consumer's stale guard compares like with like rather than
        // trusting whichever instance's clock emitted the event.
        assertThat(captor.getValue().aggregateId()).isEqualTo(PO_ID);
        assertThat(captor.getValue().aggregateVersion()).isEqualTo(3L);
        assertThat(captor.getValue().eventType()).isEqualTo("purchaseorder.updated");
    }

    @Test
    void marksOnlyApprovedAndPartiallyReceivedAsOpenSupply() {
        assertThat(new PurchaseOrderUpdatedV1(
                                PO_ID, "PO-1", VENDOR_ID, "APPROVED", SITE_ID, null, "USD", 1L, NOW, List.of())
                        .countsAsOpenSupply())
                .isTrue();
        assertThat(new PurchaseOrderUpdatedV1(
                                PO_ID, "PO-1", VENDOR_ID, "DRAFT", SITE_ID, null, "USD", 1L, NOW, List.of())
                        .countsAsOpenSupply())
                .isFalse();
        // A draft counted as supply would promise stock nobody has committed to buying.
        assertThat(new PurchaseOrderUpdatedV1(
                                PO_ID, "PO-1", VENDOR_ID, "CANCELLED", SITE_ID, null, "USD", 1L, NOW, List.of())
                        .countsAsOpenSupply())
                .isFalse();
    }

    @Test
    void publishesNothingWhereTheOutboxIsNotWired() {
        when(outboxProvider.getIfAvailable()).thenReturn(null);

        publisher.publish(order(PurchaseOrderStatus.APPROVED, null), List.of(line(TEN(), TEN())));

        // A deployment without eventing still writes purchase orders; it simply publishes nothing.
        verifyNoInteractions(outboxEventWriter);
    }

    private static BigDecimal TEN() {
        return BigDecimal.valueOf(10);
    }
}

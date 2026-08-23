package com.positivity.workorder.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.workorder.internal.config.InventoryCommandPublisher;
import com.positivity.workorder.internal.entity.Workorder;
import com.positivity.workorder.internal.entity.WorkorderPart;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Issues #1479 and #1481 — promoting a workorder registers its parts demand, which is what makes
 * the parts pickable and what raises a shortage when the site cannot cover them.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("PromotedWorkorderDemandPublisher — promotion registers parts demand (#1479, #1481)")
class PromotedWorkorderDemandPublisherTest {

    private static final UUID WORKORDER_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f8a01");
    private static final UUID SHOP_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f8a02");
    private static final UUID PART_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f8a03");
    private static final UUID PRODUCT_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f8a04");
    private static final UUID OTHER_PART_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f8a05");
    private static final UUID OTHER_PRODUCT_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f8a06");

    @Mock
    private ObjectProvider<InventoryCommandPublisher> publisherProvider;

    @Mock
    private InventoryCommandPublisher publisher;

    @InjectMocks
    private PromotedWorkorderDemandPublisher demandPublisher;

    /**
     * The reservation request is the shortage signal's origin (#1481): pos-inventory registers the
     * demand, and where owned stock at the site cannot cover it, opens the backorder whose id
     * lands back on the part line.
     */
    @Test
    @DisplayName("each part line gets a reservation request at its servicing site")
    void eachPartLineIsReserved() {
        when(publisherProvider.getIfAvailable()).thenReturn(publisher);

        demandPublisher.registerPartsDemand(workorder(), List.of(part(PART_ID, PRODUCT_ID, "2", "EA")));

        verify(publisher).requestReservation(PART_ID, PRODUCT_ID, new BigDecimal("2"), SHOP_ID, "EA");
    }

    /** #1479: without this, getPickTasks answers empty forever and the part can never be picked. */
    @Test
    @DisplayName("one pick-list generate request carries every part line")
    void pickListIsGeneratedForAllLines() {
        when(publisherProvider.getIfAvailable()).thenReturn(publisher);

        demandPublisher.registerPartsDemand(
                workorder(),
                List.of(part(PART_ID, PRODUCT_ID, "2", "EA"), part(OTHER_PART_ID, OTHER_PRODUCT_ID, "1", null)));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<InventoryCommandPublisher.PickLine>> lines = ArgumentCaptor.forClass(List.class);
        verify(publisher).requestPickListGeneration(eq(WORKORDER_ID), any(), anyInt(), lines.capture());
        assertThat(lines.getValue())
                .extracting(InventoryCommandPublisher.PickLine::workorderLineId)
                .containsExactly(PART_ID, OTHER_PART_ID);
        assertThat(lines.getValue().getFirst().sku()).isEqualTo(PRODUCT_ID.toString());
        assertThat(lines.getValue().getFirst().quantity()).isEqualByComparingTo("2");
    }

    /** A part line with no product cannot be reserved or picked; it must not stop the ones that can. */
    @Test
    @DisplayName("lines without a product or quantity are skipped, not fatal")
    void unactionableLinesAreSkipped() {
        when(publisherProvider.getIfAvailable()).thenReturn(publisher);

        demandPublisher.registerPartsDemand(
                workorder(),
                List.of(
                        part(PART_ID, null, "2", "EA"),
                        part(OTHER_PART_ID, OTHER_PRODUCT_ID, "0", null),
                        part(UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f8a07"), OTHER_PRODUCT_ID, "3", null)));

        verify(publisher, never()).requestReservation(eq(PART_ID), any(), any(), any(), any());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<InventoryCommandPublisher.PickLine>> lines = ArgumentCaptor.forClass(List.class);
        verify(publisher).requestPickListGeneration(eq(WORKORDER_ID), any(), anyInt(), lines.capture());
        assertThat(lines.getValue()).hasSize(1);
    }

    /** Reservation and sourcing are site-scoped; with no site there is nothing to ask about. */
    @Test
    @DisplayName("a workorder with no shop registers nothing")
    void noShopRegistersNothing() {
        when(publisherProvider.getIfAvailable()).thenReturn(publisher);
        Workorder shopless = workorder();
        shopless.setShopId(null);

        demandPublisher.registerPartsDemand(shopless, List.of(part(PART_ID, PRODUCT_ID, "2", "EA")));

        verify(publisher, never()).requestReservation(any(), any(), any(), any(), any());
        verify(publisher, never()).requestPickListGeneration(any(), any(), anyInt(), any());
    }

    /**
     * A broker that is down must not cost the promotion. The workorder is already committed by the
     * time this runs; failing here would only turn a valid promotion into an error.
     */
    @Test
    @DisplayName("a failing reservation send does not stop the remaining lines or the pick list")
    void aFailedSendIsNotFatal() {
        when(publisherProvider.getIfAvailable()).thenReturn(publisher);
        doThrow(new IllegalStateException("broker down"))
                .when(publisher)
                .requestReservation(eq(PART_ID), any(), any(), any(), any());

        demandPublisher.registerPartsDemand(
                workorder(),
                List.of(part(PART_ID, PRODUCT_ID, "2", "EA"), part(OTHER_PART_ID, OTHER_PRODUCT_ID, "1", null)));

        verify(publisher).requestReservation(OTHER_PART_ID, OTHER_PRODUCT_ID, new BigDecimal("1"), SHOP_ID, null);
        verify(publisher).requestPickListGeneration(eq(WORKORDER_ID), any(), anyInt(), any());
    }

    @Test
    @DisplayName("no publisher available is a no-op, not a failure")
    void missingPublisherIsANoOp() {
        when(publisherProvider.getIfAvailable()).thenReturn(null);

        demandPublisher.registerPartsDemand(workorder(), List.of(part(PART_ID, PRODUCT_ID, "2", "EA")));
    }

    private static Workorder workorder() {
        return Workorder.builder().id(WORKORDER_ID).shopId(SHOP_ID).build();
    }

    private static WorkorderPart part(UUID id, UUID productId, String quantity, String uomCode) {
        WorkorderPart part = WorkorderPart.builder()
                .productEntityId(productId)
                .quantity(new BigDecimal(quantity))
                .uomCode(uomCode)
                .build();
        part.setId(id);
        return part;
    }
}

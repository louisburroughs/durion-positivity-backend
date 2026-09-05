package com.positivity.order.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.order.internal.dto.purchaseorder.ApprovePurchaseOrderRequest;
import com.positivity.order.internal.dto.purchaseorder.CreatePurchaseOrderRequest;
import com.positivity.order.internal.dto.purchaseorder.PurchaseOrderLineRequest;
import com.positivity.order.internal.dto.purchaseorder.PurchaseOrderResponse;
import com.positivity.order.internal.dto.purchaseorder.RevisePurchaseOrderRequest;
import com.positivity.order.internal.entity.PurchaseOrderEntity;
import com.positivity.order.internal.entity.PurchaseOrderLineEntity;
import com.positivity.order.internal.enums.PurchaseOrderStatus;
import com.positivity.order.internal.exception.PurchaseOrderNotFoundException;
import com.positivity.order.internal.exception.PurchaseOrderStateConflictException;
import com.positivity.order.internal.repository.PurchaseOrderRepository;
import com.positivity.order.internal.repository.PurchaseOrderTransmissionEventRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
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

/**
 * The purchase-order aggregate now that pos-order owns it (CAP-320 #1334).
 *
 * <p>Two properties matter beyond the ordinary lifecycle rules. Every transition must publish the
 * fact, because that is the only way anything outside this module learns the order changed — a
 * transition that forgets is invisible rather than merely late. And a new line must start fully
 * outstanding, because the open quantity is what downstream counts as incoming supply.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("PurchaseOrderServiceImpl — the aggregate in pos-order (#1334)")
class PurchaseOrderServiceImplTest {

    private static final UUID PO_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a01");
    private static final UUID VENDOR_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a02");
    private static final UUID SITE_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a03");
    private static final UUID SKU_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a04");
    private static final Instant NOW = Instant.parse("2026-08-15T12:00:00Z");
    private static final String ACTOR = "buyer-1";

    @Mock
    private PurchaseOrderRepository purchaseOrderRepository;

    @Mock
    private PurchaseOrderTransmissionEventRepository transmissionEventRepository;

    @Mock
    private PurchaseOrderFactPublisher purchaseOrderFactPublisher;

    @Mock
    private DocumentQuantityConverter documentQuantityConverter;

    @Mock
    private jakarta.persistence.EntityManager entityManager;

    private PurchaseOrderServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new PurchaseOrderServiceImpl(
                purchaseOrderRepository,
                transmissionEventRepository,
                entityManager,
                purchaseOrderFactPublisher,
                documentQuantityConverter,
                Clock.fixed(NOW, ZoneOffset.UTC));
        when(documentQuantityConverter.convertIfPresent(any(), any(), any(), any()))
                .thenReturn(Optional.empty());
        when(purchaseOrderRepository.getNextPurchaseOrderSequence()).thenReturn(42L);
        when(purchaseOrderRepository.save(any())).thenAnswer(invocation -> {
            PurchaseOrderEntity saved = invocation.getArgument(0);
            if (saved.getPurchaseOrderId() == null) {
                saved.setPurchaseOrderId(PO_ID);
            }
            return saved;
        });
    }

    private static PurchaseOrderLineRequest lineRequest(String quantity, long unitCostMinor) {
        PurchaseOrderLineRequest line = new PurchaseOrderLineRequest();
        line.setLineNumber(1);
        line.setSkuId(SKU_ID);
        line.setDescription("Front brake rotor");
        line.setQuantity(new BigDecimal(quantity));
        line.setUnitCostMinor(unitCostMinor);
        return line;
    }

    private static CreatePurchaseOrderRequest createRequest() {
        CreatePurchaseOrderRequest request = new CreatePurchaseOrderRequest();
        request.setVendorId(VENDOR_ID);
        request.setCurrency("USD");
        request.setShipToLocationId(SITE_ID);
        request.setExpectedDeliveryDate(LocalDate.of(2026, 9, 1));
        request.setLines(List.of(lineRequest("10", 5_000L)));
        return request;
    }

    private static PurchaseOrderEntity existingOrder(PurchaseOrderStatus status) {
        PurchaseOrderLineEntity line = PurchaseOrderLineEntity.builder()
                .lineId(UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a05"))
                .lineNumber(1)
                .skuId(SKU_ID)
                .description("Front brake rotor")
                .quantityDecimal(BigDecimal.TEN)
                .openQuantityDecimal(BigDecimal.TEN)
                .unitCostMinor(5_000L)
                .lineTotalMinor(50_000L)
                .build();
        PurchaseOrderEntity po = PurchaseOrderEntity.builder()
                .purchaseOrderId(PO_ID)
                .poNumber("0000001A")
                .vendorId(VENDOR_ID)
                .status(status)
                .versionNumber(1)
                .currency("USD")
                .subtotalMinor(50_000L)
                .taxMinor(0L)
                .grandTotalMinor(50_000L)
                .openBalanceMinor(50_000L)
                .shipToLocationId(SITE_ID)
                .lines(new ArrayList<>(List.of(line)))
                .build();
        line.setPurchaseOrder(po);
        return po;
    }

    @Test
    @DisplayName("a created order starts as a draft with every line fully outstanding")
    void createStartsDraftWithFullyOpenLines() {
        PurchaseOrderResponse response = service.createPurchaseOrder(createRequest(), ACTOR);

        assertThat(response.getStatus()).isEqualTo(PurchaseOrderStatus.DRAFT);
        assertThat(response.getGrandTotalMinor()).isEqualTo(50_000L);
        // Open quantity is what downstream counts as incoming supply. A new line that started at
        // zero would be an order nobody could see coming.
        assertThat(response.getLines()).singleElement().satisfies(line -> {
            assertThat(line.getQuantityDecimal()).isEqualByComparingTo("10");
            assertThat(line.getOpenQuantityDecimal()).isEqualByComparingTo("10");
        });
    }

    @Test
    @DisplayName("the PO number renders the sequence as eight base-36 characters")
    void poNumberIsEightBase36Characters() {
        PurchaseOrderResponse response = service.createPurchaseOrder(createRequest(), ACTOR);

        assertThat(response.getPoNumber()).hasSize(8).isEqualTo("00000016");
    }

    @Test
    @DisplayName("a sequence value too large for eight characters is refused rather than truncated")
    void sequenceOverflowIsRefused() {
        when(purchaseOrderRepository.getNextPurchaseOrderSequence()).thenReturn(Long.MAX_VALUE);

        // Truncating would mint a number that collides with one already printed on an order.
        assertThatThrownBy(() -> service.createPurchaseOrder(createRequest(), ACTOR))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("approval stamps who approved and when, and only from draft")
    void approvalStampsApproverAndRejectsNonDraft() {
        when(purchaseOrderRepository.findById(PO_ID)).thenReturn(Optional.of(existingOrder(PurchaseOrderStatus.DRAFT)));
        ApprovePurchaseOrderRequest request = new ApprovePurchaseOrderRequest();
        request.setApprovalNotes("within budget");

        PurchaseOrderResponse approved = service.approvePurchaseOrder(PO_ID, request, ACTOR);

        assertThat(approved.getStatus()).isEqualTo(PurchaseOrderStatus.APPROVED);
        assertThat(approved.getApproverId()).isEqualTo(ACTOR);
        assertThat(approved.getApprovalTimestamp()).isEqualTo(NOW);

        when(purchaseOrderRepository.findById(PO_ID))
                .thenReturn(Optional.of(existingOrder(PurchaseOrderStatus.APPROVED)));
        assertThatThrownBy(() -> service.approvePurchaseOrder(PO_ID, request, ACTOR))
                .isInstanceOf(PurchaseOrderStateConflictException.class);
    }

    @Test
    @DisplayName("a revision bumps the version and replaces the lines outright")
    void revisionBumpsVersionAndReplacesLines() {
        when(purchaseOrderRepository.findById(PO_ID)).thenReturn(Optional.of(existingOrder(PurchaseOrderStatus.DRAFT)));
        RevisePurchaseOrderRequest request = new RevisePurchaseOrderRequest();
        request.setRevisionReason("vendor changed the price");
        request.setLines(List.of(lineRequest("4", 6_000L)));

        PurchaseOrderResponse revised = service.revisePurchaseOrder(PO_ID, request, ACTOR);

        assertThat(revised.getVersionNumber()).isEqualTo(2);
        // Replaced, not merged: a line the buyer removed must be gone, or it keeps counting as
        // supply nobody ordered.
        assertThat(revised.getLines()).singleElement().satisfies(line -> {
            assertThat(line.getQuantityDecimal()).isEqualByComparingTo("4");
            assertThat(line.getUnitCostMinor()).isEqualTo(6_000L);
        });
        assertThat(revised.getGrandTotalMinor()).isEqualTo(24_000L);
    }

    @Test
    @DisplayName("a received or closed order cannot be cancelled")
    void cancelIsBlockedOnTerminalStatuses() {
        for (PurchaseOrderStatus terminal : List.of(PurchaseOrderStatus.FULLY_RECEIVED, PurchaseOrderStatus.CLOSED)) {
            when(purchaseOrderRepository.findById(PO_ID)).thenReturn(Optional.of(existingOrder(terminal)));

            // The goods are already here. Cancelling would deny an obligation the vendor has met.
            assertThatThrownBy(() -> service.cancelPurchaseOrder(PO_ID, ACTOR))
                    .isInstanceOf(PurchaseOrderStateConflictException.class);
        }
        verify(purchaseOrderFactPublisher, never()).publish(any(), any());
    }

    @Test
    @DisplayName("cancelling an approved order publishes the fact that removes it from supply")
    void cancelPublishesTheFact() {
        when(purchaseOrderRepository.findById(PO_ID))
                .thenReturn(Optional.of(existingOrder(PurchaseOrderStatus.APPROVED)));

        service.cancelPurchaseOrder(PO_ID, ACTOR);

        ArgumentCaptor<PurchaseOrderEntity> captor = ArgumentCaptor.forClass(PurchaseOrderEntity.class);
        verify(purchaseOrderFactPublisher).publish(captor.capture(), any());
        // CANCELLED is not an open-supply status, so a consumer applying this fact drops the
        // order's quantities. No separate "supply dropped" message is needed from here.
        assertThat(captor.getValue().getStatus()).isEqualTo(PurchaseOrderStatus.CANCELLED);
    }

    @Test
    @DisplayName("every state-changing transition publishes exactly one fact")
    void everyTransitionPublishes() {
        service.createPurchaseOrder(createRequest(), ACTOR);
        verify(purchaseOrderFactPublisher).publish(any(), any());

        when(purchaseOrderRepository.findById(PO_ID)).thenReturn(Optional.of(existingOrder(PurchaseOrderStatus.DRAFT)));
        service.approvePurchaseOrder(PO_ID, new ApprovePurchaseOrderRequest(), ACTOR);

        RevisePurchaseOrderRequest revise = new RevisePurchaseOrderRequest();
        revise.setLines(List.of(lineRequest("1", 100L)));
        when(purchaseOrderRepository.findById(PO_ID)).thenReturn(Optional.of(existingOrder(PurchaseOrderStatus.DRAFT)));
        service.revisePurchaseOrder(PO_ID, revise, ACTOR);

        when(purchaseOrderRepository.findById(PO_ID))
                .thenReturn(Optional.of(existingOrder(PurchaseOrderStatus.APPROVED)));
        service.cancelPurchaseOrder(PO_ID, ACTOR);

        // Four transitions, four facts. A transition that publishes nothing is invisible to every
        // reader rather than merely late, and the projection would never correct itself.
        verify(purchaseOrderFactPublisher, org.mockito.Mockito.times(4)).publish(any(), any());
    }

    @Test
    @DisplayName("an unknown order id is a not-found, not a null")
    void unknownOrderThrows() {
        when(purchaseOrderRepository.findById(PO_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getPurchaseOrder(PO_ID)).isInstanceOf(PurchaseOrderNotFoundException.class);
    }

    @Test
    @DisplayName("a requested order is inserted at the id it was asked for, never merged into one")
    void requestedOrderIsInsertedNotMerged() {
        CreatePurchaseOrderRequest request = createRequest();

        service.createRequested(PO_ID, request, "pos-inventory");

        // save() would read the pre-set id as "not new" and merge, which silently updates an
        // existing row — the duplicate would overwrite the order it was meant not to create twice.
        // persist() inserts or fails, so the primary key is what enforces one-order-per-request.
        ArgumentCaptor<PurchaseOrderEntity> captor = ArgumentCaptor.forClass(PurchaseOrderEntity.class);
        verify(entityManager).persist(captor.capture());
        assertThat(captor.getValue().getPurchaseOrderId()).isEqualTo(PO_ID);
        verify(purchaseOrderRepository, never()).save(any());

        // The fact still goes out, or nothing downstream learns the order exists.
        verify(purchaseOrderFactPublisher).publish(any(), any());
    }
}

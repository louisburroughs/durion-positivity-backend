package com.positivity.order.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.order.internal.dto.purchaseorder.CancelPurchaseOrderLineRequest;
import com.positivity.order.internal.dto.purchaseorder.ClosePurchaseOrderRequest;
import com.positivity.order.internal.dto.purchaseorder.PurchaseOrderResponse;
import com.positivity.order.internal.dto.purchaseorder.ReopenPurchaseOrderLineRequest;
import com.positivity.order.internal.entity.ExtSupplierVendor;
import com.positivity.order.internal.entity.PurchaseOrderEntity;
import com.positivity.order.internal.entity.PurchaseOrderLineEntity;
import com.positivity.order.internal.enums.PurchaseOrderStatus;
import com.positivity.order.internal.exception.PurchaseOrderNotFoundException;
import com.positivity.order.internal.exception.PurchaseOrderStateConflictException;
import com.positivity.order.internal.repository.ExtSupplierVendorRepository;
import com.positivity.order.internal.repository.PurchaseOrderLineRepository;
import com.positivity.order.internal.repository.PurchaseOrderRepository;
import com.positivity.order.internal.repository.PurchaseOrderTransmissionEventRepository;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * The per-line commands of #2635 (reopen for replacement, line cancel, close): their effect on the figures, the
 * status and the fact, and the refusals with their {@code nextAction}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Purchase-order line lifecycle commands (#2635)")
class PurchaseOrderLineLifecycleTest {

    private static final UUID PO_ID = UUID.fromString("019a0000-0000-7000-8000-000000002635");
    private static final UUID LINE_ID = UUID.fromString("019a0000-0000-7000-8000-0000000026a1");
    private static final UUID VENDOR_ID = UUID.fromString("019a0000-0000-7000-8000-0000000026b1");

    @Mock
    private PurchaseOrderRepository purchaseOrderRepository;

    @Mock
    private PurchaseOrderLineRepository purchaseOrderLineRepository;

    @Mock
    private PurchaseOrderTransmissionEventRepository transmissionEventRepository;

    @Mock
    private EntityManager entityManager;

    @Mock
    private PurchaseOrderFactPublisher purchaseOrderFactPublisher;

    @Mock
    private DocumentQuantityConverter documentQuantityConverter;

    @Mock
    private ExtSupplierVendorRepository vendorRepository;

    private PurchaseOrderServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new PurchaseOrderServiceImpl(
                purchaseOrderRepository,
                purchaseOrderLineRepository,
                transmissionEventRepository,
                entityManager,
                purchaseOrderFactPublisher,
                documentQuantityConverter,
                Clock.fixed(Instant.parse("2026-10-17T12:00:00Z"), ZoneOffset.UTC),
                new SupplierVendorGuard(vendorRepository));
        when(purchaseOrderRepository.findByIdForUpdate(any()))
                .thenAnswer(invocation -> purchaseOrderRepository.findById(invocation.getArgument(0)));
        when(purchaseOrderRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    /** One line of 10 at 1_000 minor each, {@code received} of them received, {@code returned} returned. */
    private PurchaseOrderEntity order(PurchaseOrderStatus status, String received, String returned) {
        PurchaseOrderLineEntity line = PurchaseOrderLineEntity.builder()
                .lineId(LINE_ID)
                .lineNumber(1)
                .description("Front brake rotor")
                .quantityDecimal(BigDecimal.TEN)
                .unitCostMinor(1_000L)
                .lineTotalMinor(10_000L)
                .receivedQuantity(new BigDecimal(received))
                .receivedAccruedMinor(new BigDecimal(received).longValueExact() * 1_000L)
                .returnedQuantity(new BigDecimal(returned))
                .returnedValueMinor(new BigDecimal(returned).longValueExact() * 1_000L)
                .build();
        PurchaseOrderEntity po = PurchaseOrderEntity.builder()
                .purchaseOrderId(PO_ID)
                .poNumber("0000001A")
                .vendorId(VENDOR_ID)
                .status(status)
                .versionNumber(3)
                .currency("USD")
                .subtotalMinor(10_000L)
                .taxMinor(0L)
                .grandTotalMinor(10_000L)
                .openBalanceMinor(10_000L
                        - new BigDecimal(received).longValueExact() * 1_000L
                        - new BigDecimal(returned).longValueExact() * 1_000L)
                .lines(new ArrayList<>(List.of(line)))
                .build();
        line.setPurchaseOrder(po);
        PurchaseOrderLineFigures.refresh(po);
        when(purchaseOrderRepository.findById(PO_ID)).thenReturn(Optional.of(po));
        return po;
    }

    @Test
    @DisplayName("reopen: the returned quantity is open again, the balance rises by its value, FULLY_RECEIVED → PARTIALLY")
    void reopenPutsTheReturnedQuantityBackInFrontOfReceiving() {
        PurchaseOrderEntity po = order(PurchaseOrderStatus.FULLY_RECEIVED, "8", "2");
        assertThat(po.getOpenBalanceMinor()).isZero();

        PurchaseOrderResponse response = service.reopenPurchaseOrderLine(
                PO_ID, LINE_ID, new ReopenPurchaseOrderLineRequest(new BigDecimal("2"), UUID.randomUUID()), "buyer");

        assertThat(response.getStatus()).isEqualTo(PurchaseOrderStatus.PARTIALLY_RECEIVED);
        assertThat(response.getVersionNumber()).isEqualTo(4);
        assertThat(response.getOpenBalanceMinor()).isEqualTo(2_000L);
        assertThat(response.getLines().getFirst().getOpenQuantityDecimal()).isEqualByComparingTo("2");
        assertThat(response.getLines().getFirst().getReturnedQuantityDecimal()).isEqualByComparingTo("0");
        assertThat(response.getLines().getFirst().getReceivedQuantityDecimal()).isEqualByComparingTo("8");
        verify(purchaseOrderFactPublisher).publish(any(), any());
    }

    @Test
    @DisplayName("reopen refusals: above the returned quantity (409), CANCELLED or CLOSED (409), unknown line (404)")
    void reopenRefusals() {
        order(PurchaseOrderStatus.FULLY_RECEIVED, "8", "2");
        assertThatThrownBy(() -> service.reopenPurchaseOrderLine(
                        PO_ID, LINE_ID, new ReopenPurchaseOrderLineRequest(new BigDecimal("3"), null), "buyer"))
                .isInstanceOf(PurchaseOrderStateConflictException.class)
                .hasMessageContaining("cannot be reopened");
        assertThatThrownBy(() -> service.reopenPurchaseOrderLine(
                        PO_ID, UUID.randomUUID(), new ReopenPurchaseOrderLineRequest(BigDecimal.ONE, null), "buyer"))
                .isInstanceOf(PurchaseOrderNotFoundException.class);
        for (PurchaseOrderStatus terminal : List.of(PurchaseOrderStatus.CANCELLED, PurchaseOrderStatus.CLOSED)) {
            order(terminal, "8", "2");
            assertThatThrownBy(() -> service.reopenPurchaseOrderLine(
                            PO_ID, LINE_ID, new ReopenPurchaseOrderLineRequest(BigDecimal.ONE, null), "buyer"))
                    .isInstanceOf(PurchaseOrderStateConflictException.class);
        }
        verify(purchaseOrderFactPublisher, never()).publish(any(), any());
    }

    @Test
    @DisplayName("line cancel: open and balance fall by the quantity and its value; every line settled → CLOSED")
    void lineCancelShortClosesTheRemainder() {
        order(PurchaseOrderStatus.PARTIALLY_RECEIVED, "6", "0");

        PurchaseOrderResponse partly = service.cancelPurchaseOrderLine(
                PO_ID, LINE_ID, new CancelPurchaseOrderLineRequest(new BigDecimal("1"), "backordered too long"), "buyer");
        assertThat(partly.getStatus()).isEqualTo(PurchaseOrderStatus.PARTIALLY_RECEIVED);
        assertThat(partly.getOpenBalanceMinor()).isEqualTo(3_000L);
        assertThat(partly.getLines().getFirst().getOpenQuantityDecimal()).isEqualByComparingTo("3");
        assertThat(partly.getLines().getFirst().getCancelledQuantityDecimal()).isEqualByComparingTo("1");

        PurchaseOrderResponse closed = service.cancelPurchaseOrderLine(
                PO_ID, LINE_ID, new CancelPurchaseOrderLineRequest(new BigDecimal("3"), "vendor discontinued"), "buyer");
        assertThat(closed.getStatus()).isEqualTo(PurchaseOrderStatus.CLOSED);
        assertThat(closed.getOpenBalanceMinor()).isZero();
        assertThat(closed.getVersionNumber()).isEqualTo(5);
    }

    @Test
    @DisplayName("line cancel refusals: DRAFT (nextAction: revise), above the open quantity, CANCELLED/CLOSED")
    void lineCancelRefusals() {
        order(PurchaseOrderStatus.DRAFT, "0", "0");
        assertThatThrownBy(() -> service.cancelPurchaseOrderLine(
                        PO_ID, LINE_ID, new CancelPurchaseOrderLineRequest(BigDecimal.ONE, "r"), "buyer"))
                .isInstanceOfSatisfying(PurchaseOrderStateConflictException.class, e -> assertThat(e.getNextAction())
                        .contains("revisePurchaseOrder"));
        order(PurchaseOrderStatus.APPROVED, "0", "0");
        assertThatThrownBy(() -> service.cancelPurchaseOrderLine(
                        PO_ID, LINE_ID, new CancelPurchaseOrderLineRequest(new BigDecimal("11"), "r"), "buyer"))
                .isInstanceOf(PurchaseOrderStateConflictException.class)
                .hasMessageContaining("cannot be cancelled");
        order(PurchaseOrderStatus.CLOSED, "10", "0");
        assertThatThrownBy(() -> service.cancelPurchaseOrderLine(
                        PO_ID, LINE_ID, new CancelPurchaseOrderLineRequest(BigDecimal.ONE, "r"), "buyer"))
                .isInstanceOf(PurchaseOrderStateConflictException.class);
        verify(purchaseOrderFactPublisher, never()).publish(any(), any());
    }

    @Test
    @DisplayName("close: every remainder is cancelled, the balance is what was received, the order is CLOSED")
    void closeCancelsEveryRemainder() {
        order(PurchaseOrderStatus.PARTIALLY_RECEIVED, "4", "0");

        PurchaseOrderResponse closed =
                service.closePurchaseOrder(PO_ID, new ClosePurchaseOrderRequest("vendor will not ship the rest"), "buyer");

        assertThat(closed.getStatus()).isEqualTo(PurchaseOrderStatus.CLOSED);
        assertThat(closed.getOpenBalanceMinor()).isZero();
        assertThat(closed.getLines().getFirst().getCancelledQuantityDecimal()).isEqualByComparingTo("6");
        assertThat(closed.getLines().getFirst().getOpenQuantityDecimal()).isEqualByComparingTo("0");
        verify(purchaseOrderFactPublisher).publish(any(), any());
    }

    @Test
    @DisplayName("close on FULLY_RECEIVED is CLOSED with nothing cancelled; on nothing received it is 409 naming cancel")
    void closeEdges() {
        order(PurchaseOrderStatus.FULLY_RECEIVED, "10", "0");
        PurchaseOrderResponse closed = service.closePurchaseOrder(PO_ID, new ClosePurchaseOrderRequest("done"), "buyer");
        assertThat(closed.getStatus()).isEqualTo(PurchaseOrderStatus.CLOSED);
        assertThat(closed.getLines().getFirst().getCancelledQuantityDecimal()).isEqualByComparingTo("0");

        order(PurchaseOrderStatus.APPROVED, "0", "0");
        assertThatThrownBy(() -> service.closePurchaseOrder(PO_ID, new ClosePurchaseOrderRequest("nothing came"), "buyer"))
                .isInstanceOfSatisfying(PurchaseOrderStateConflictException.class, e -> assertThat(e.getNextAction())
                        .contains("cancelPurchaseOrder"));
        order(PurchaseOrderStatus.DRAFT, "0", "0");
        assertThatThrownBy(() -> service.closePurchaseOrder(PO_ID, new ClosePurchaseOrderRequest("draft"), "buyer"))
                .isInstanceOf(PurchaseOrderStateConflictException.class);
    }

    @SuppressWarnings("unused")
    private static ExtSupplierVendor unusedVendor() {
        return mock(ExtSupplierVendor.class);
    }
}

package com.positivity.accounting.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.entity.VendorBill;
import com.positivity.accounting.internal.enums.VendorBillStatus;
import com.positivity.accounting.internal.exception.VendorBillDuplicateException;
import com.positivity.accounting.internal.repository.VendorBillRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/** The delivery-reference duplicate check (G16, ruling 8 of #2518): applies only when both references are given. */
@DisplayName("VendorBillDuplicateGuard: delivery reference (G16)")
class VendorBillDeliveryDuplicateTest {

    private static final UUID VENDOR = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f7a01");
    private static final LocalDateTime DATE = LocalDateTime.of(2026, 10, 1, 9, 30);

    private final VendorBillRepository repository = mock(VendorBillRepository.class);
    private final VendorBillDuplicateGuard guard = new VendorBillDuplicateGuard(repository, mock(ObjectProvider.class));

    private static VendorBill goodsReceiptBill() {
        VendorBill bill = new VendorBill();
        bill.setVendorBillId(UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f7c01"));
        bill.setVendorId(VENDOR);
        bill.setVendorName("Acme Parts Co");
        bill.setBillNumber("BILL_X_1");
        bill.setBillDate(DATE);
        bill.setStatus(VendorBillStatus.PENDING_RECEIPT_MATCH);
        return bill;
    }

    @Test
    @DisplayName("the same vendor, order and delivery (normalised) is refused, matched by DELIVERY_REFERENCE")
    void refusesTheSameDelivery() {
        when(repository.findLiveGoodsReceiptsByDelivery(VENDOR, "PO42", "DN088213"))
                .thenReturn(List.of(goodsReceiptBill()));

        assertThatThrownBy(() -> guard.refuseIfDeliveryDuplicate(
                        VendorBillDuplicateGuard.Channel.UPLOAD, VENDOR, "INV-1", DATE, " po-042 ", " dn-088213 "))
                .isInstanceOfSatisfying(VendorBillDuplicateException.class, e -> {
                    assertThat(e.getMatchedBy()).isEqualTo(VendorBillDuplicateException.MatchedBy.DELIVERY_REFERENCE);
                    assertThat(e.getMessage()).contains("BILL_X_1").doesNotContain("018f0a1b");
                });
    }

    @Test
    @DisplayName("no delivery reference or no purchase order: nothing to match, nothing is looked up")
    void needsBothReferences() {
        assertThat(guard.findDeliveryOriginal(VENDOR, "PO-42", null)).isEmpty();
        assertThat(guard.findDeliveryOriginal(VENDOR, null, "DN-1")).isEmpty();
        assertThat(guard.findDeliveryOriginal(VENDOR, " ", "DN-1")).isEmpty();
        verify(repository, never()).findLiveGoodsReceiptsByDelivery(any(), any(), any());
    }

    @Test
    @DisplayName("an order only: the goods-receipt bills of the order are what a draft is warned about, not refused")
    void purchaseOrderOnlyIsAWarning() {
        when(repository.findLiveGoodsReceiptsByPurchaseOrder(VENDOR, "PO42")).thenReturn(List.of(goodsReceiptBill()));

        assertThat(guard.findPurchaseOrderMatches(VENDOR, "po 42")).hasSize(1);
        assertThat(guard.findPurchaseOrderMatches(VENDOR, null)).isEmpty();
    }

    @Test
    @DisplayName("a bill's purchase-order key is its normalised number; blank is none")
    void billKeepsThePurchaseOrderKey() {
        VendorBill bill = new VendorBill();
        bill.setPurchaseOrderNumber(" po-042 ");
        assertThat(bill.getPurchaseOrderNumber()).isEqualTo("po-042");
        assertThat(bill.getPurchaseOrderNumberKey()).isEqualTo("PO042");
        bill.setPurchaseOrderNumber(" ");
        assertThat(bill.getPurchaseOrderNumber()).isNull();
        assertThat(bill.getPurchaseOrderNumberKey()).isNull();
    }

    @Test
    @DisplayName("a bill's delivery key is its normalised reference; blank is none")
    void billKeepsTheKey() {
        VendorBill bill = new VendorBill();
        bill.setDeliveryReference(" dn-088213 ");
        assertThat(bill.getDeliveryReference()).isEqualTo("dn-088213");
        assertThat(bill.getDeliveryReferenceKey()).isEqualTo("DN088213");
        bill.setDeliveryReference("  ");
        assertThat(bill.getDeliveryReference()).isNull();
        assertThat(bill.getDeliveryReferenceKey()).isNull();
    }
}

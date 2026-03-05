package com.positivity.accounting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import com.positivity.accounting.internal.dto.GoodsReceivedEvent;
import com.positivity.accounting.internal.dto.VendorBillGLPostingEvent;
import com.positivity.accounting.internal.entity.VendorBill;
import com.positivity.accounting.internal.enums.VendorBillStatus;
import com.positivity.accounting.internal.repository.VendorBillLineRepository;
import com.positivity.accounting.internal.repository.VendorBillRepository;
import com.positivity.accounting.internal.service.VendorBillServiceImpl;

/**
 * Unit tests for GL posting event emission in VendorBillServiceImpl.
 * Tests verify that VendorBillGLPostingEvent is emitted after bill creation.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("VendorBillService - GL Posting Event Emission")
class VendorBillServiceGLPostingTest {

    @Mock
    private VendorBillRepository billRepository;

    @Mock
    private VendorBillLineRepository billLineRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private VendorBillServiceImpl vendorBillService;

    private GoodsReceivedEvent testEvent;
    private UUID testVendorId;
    private UUID testPoId;
    private UUID testProductId1;
    private UUID testProductId2;

    @BeforeEach
    void setUp() {
        testVendorId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        testPoId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        testProductId1 = UUID.fromString("00000000-0000-0000-0000-000000000001");
        testProductId2 = UUID.fromString("00000000-0000-0000-0000-000000000001");

        testEvent = GoodsReceivedEvent.builder()
                .eventId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                .organizationId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                .purchaseOrderId(testPoId)
                .vendorId(testVendorId)
                .vendorName("Test Vendor Inc")
                .receivedDate(LocalDateTime.of(2026, 2, 11, 10, 30))
                .lineItems(List.of(
                        GoodsReceivedEvent.ReceivedLineItem.builder()
                                .productId(testProductId1)
                                .description("Widget Type A")
                                .quantity(new BigDecimal("100.00"))
                                .unitPrice(new BigDecimal("12.50"))
                                .isInventoryItem(true)
                                .build(),
                        GoodsReceivedEvent.ReceivedLineItem.builder()
                                .productId(testProductId2)
                                .description("Shipping Fee")
                                .quantity(new BigDecimal("1.00"))
                                .unitPrice(new BigDecimal("50.00"))
                                .isInventoryItem(false)
                                .build()))
                .build();
    }

    @Test
    @DisplayName("Should emit VendorBillGLPostingEvent when bill is created")
    void shouldEmitGLPostingEventWhenBillCreated() {
        // Given: No existing bill (first-time processing)
        when(billRepository.findByOriginEventId(testEvent.getEventId())).thenReturn(Optional.empty());

        VendorBill savedBill = createSavedBill();
        when(billRepository.save(any(VendorBill.class))).thenReturn(savedBill);

        // When: Processing goods received event
        vendorBillService.handleGoodsReceivedEvent(testEvent);

        // Then: GL posting event should be emitted
        ArgumentCaptor<VendorBillGLPostingEvent> captor = ArgumentCaptor
                .forClass(VendorBillGLPostingEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());

        VendorBillGLPostingEvent emittedEvent = captor.getValue();
        assertThat(emittedEvent).isNotNull();
        assertThat(emittedEvent.getEventId()).isNotNull();
        assertThat(emittedEvent.getVendorBillId()).isEqualTo(savedBill.getVendorBillId());
        assertThat(emittedEvent.getVendorId()).isEqualTo(testVendorId);
        assertThat(emittedEvent.getOrganizationId()).isEqualTo(testEvent.getOrganizationId());
        assertThat(emittedEvent.getVendorName()).isEqualTo("Test Vendor Inc");
        assertThat(emittedEvent.getPurchaseOrderId()).isEqualTo(testPoId);
        assertThat(emittedEvent.getTotalAmount()).isEqualByComparingTo("1300.00"); // 100*12.50 + 1*50.00
        assertThat(emittedEvent.getLineItems()).hasSize(2);
    }

    @Test
    @DisplayName("Should preserve isInventoryItem flag in emitted event")
    void shouldPreserveIsInventoryItemFlag() {
        // Given: No existing bill
        when(billRepository.findByOriginEventId(testEvent.getEventId())).thenReturn(Optional.empty());

        VendorBill savedBill = createSavedBill();
        when(billRepository.save(any(VendorBill.class))).thenReturn(savedBill);

        // When: Processing goods received event
        vendorBillService.handleGoodsReceivedEvent(testEvent);

        // Then: isInventoryItem flag should be preserved for each line item
        ArgumentCaptor<VendorBillGLPostingEvent> captor = ArgumentCaptor
                .forClass(VendorBillGLPostingEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());

        List<VendorBillGLPostingEvent.BillLineItem> lineItems = captor.getValue().getLineItems();
        assertThat(lineItems).hasSize(2);

        // First line: Inventory item
        assertThat(lineItems.get(0).getProductId()).isEqualTo(testProductId1);
        assertThat(lineItems.get(0).isInventoryItem()).isTrue();
        assertThat(lineItems.get(0).getLineTotal()).isEqualByComparingTo("1250.00");

        // Second line: Non-inventory (expense) item
        assertThat(lineItems.get(1).getProductId()).isEqualTo(testProductId2);
        assertThat(lineItems.get(1).isInventoryItem()).isFalse();
        assertThat(lineItems.get(1).getLineTotal()).isEqualByComparingTo("50.00");
    }

    @Test
    @DisplayName("Should include purchase order reference in emitted event")
    void shouldIncludePurchaseOrderReference() {
        // Given: No existing bill
        when(billRepository.findByOriginEventId(testEvent.getEventId())).thenReturn(Optional.empty());

        VendorBill savedBill = createSavedBill();
        when(billRepository.save(any(VendorBill.class))).thenReturn(savedBill);

        // When: Processing goods received event
        vendorBillService.handleGoodsReceivedEvent(testEvent);

        // Then: PO ID should be included
        ArgumentCaptor<VendorBillGLPostingEvent> captor = ArgumentCaptor
                .forClass(VendorBillGLPostingEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());

        VendorBillGLPostingEvent emittedEvent = captor.getValue();
        assertThat(emittedEvent.getPurchaseOrderId()).isEqualTo(testPoId);
        // Note: purchaseOrderNumber is not yet populated from GoodsReceivedEvent
    }

    @Test
    @DisplayName("Should map all line item fields correctly")
    void shouldMapAllLineItemFieldsCorrectly() {
        // Given: No existing bill
        when(billRepository.findByOriginEventId(testEvent.getEventId())).thenReturn(Optional.empty());

        VendorBill savedBill = createSavedBill();
        when(billRepository.save(any(VendorBill.class))).thenReturn(savedBill);

        // When: Processing goods received event
        vendorBillService.handleGoodsReceivedEvent(testEvent);

        // Then: All line item fields should be mapped correctly
        ArgumentCaptor<VendorBillGLPostingEvent> captor = ArgumentCaptor
                .forClass(VendorBillGLPostingEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());

        VendorBillGLPostingEvent.BillLineItem firstLine = captor.getValue().getLineItems().get(0);
        assertThat(firstLine.getProductId()).isEqualTo(testProductId1);
        assertThat(firstLine.getDescription()).isEqualTo("Widget Type A");
        assertThat(firstLine.getQuantity()).isEqualByComparingTo("100.00");
        assertThat(firstLine.getUnitPrice()).isEqualByComparingTo("12.50");
        assertThat(firstLine.getLineTotal()).isEqualByComparingTo("1250.00");
        assertThat(firstLine.isInventoryItem()).isTrue();
    }

    @Test
    @DisplayName("Should not emit event for duplicate goods received event")
    void shouldNotEmitEventForDuplicateGoodsReceivedEvent() {
        // Given: Existing bill (duplicate event)
        VendorBill existingBill = createSavedBill();
        when(billRepository.findByOriginEventId(testEvent.getEventId())).thenReturn(Optional.of(existingBill));

        // When: Processing duplicate goods received event
        vendorBillService.handleGoodsReceivedEvent(testEvent);

        // Then: No GL posting event should be emitted (idempotency)
        verify(eventPublisher, org.mockito.Mockito.never()).publishEvent(any(VendorBillGLPostingEvent.class));
    }

    private VendorBill createSavedBill() {
        VendorBill bill = new VendorBill();
        bill.setVendorBillId(UUID.fromString("00000000-0000-0000-0000-000000000001"));
        bill.setVendorId(testVendorId);
        bill.setVendorName("Test Vendor Inc");
        bill.setBillNumber("BILL-2026-00456");
        bill.setBillDate(LocalDateTime.of(2026, 2, 11, 10, 30));
        bill.setStatus(VendorBillStatus.PENDING_RECEIPT_MATCH);
        bill.setOriginEventId(testEvent.getEventId());
        bill.setOriginEventType("GOODS_RECEIVED");
        bill.setPurchaseOrderId(testPoId);
        bill.setTotalAmount(new BigDecimal("1300.00"));
        bill.setCreatedBy("system");
        bill.setModifiedBy("system");
        return bill;
    }
}

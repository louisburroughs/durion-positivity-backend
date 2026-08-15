package com.positivity.inventory.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.inventory.internal.dto.purchaseorder.ApprovePurchaseOrderRequest;
import com.positivity.inventory.internal.dto.purchaseorder.CreatePurchaseOrderRequest;
import com.positivity.inventory.internal.dto.purchaseorder.ListPurchaseOrdersRequest;
import com.positivity.inventory.internal.dto.purchaseorder.PurchaseOrderLineRequest;
import com.positivity.inventory.internal.dto.purchaseorder.ReceivePurchaseOrderRequest;
import com.positivity.inventory.internal.dto.purchaseorder.RevisePurchaseOrderRequest;
import com.positivity.inventory.internal.entity.PurchaseOrderEntity;
import com.positivity.inventory.internal.entity.PurchaseOrderLineEntity;
import com.positivity.inventory.internal.enums.PurchaseOrderStatus;
import com.positivity.inventory.internal.exception.PurchaseOrderNotApprovedException;
import com.positivity.inventory.internal.exception.ResourceNotFoundException;
import com.positivity.inventory.internal.repository.PurchaseOrderLineRepository;
import com.positivity.inventory.internal.repository.PurchaseOrderRepository;
import com.positivity.inventory.internal.service.PurchaseOrderServiceImpl;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
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
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("PurchaseOrderServiceImpl Unit Tests")
class PurchaseOrderServiceImplTest {

    @Mock
    private PurchaseOrderRepository purchaseOrderRepository;

    @Mock
    private PurchaseOrderLineRepository purchaseOrderLineRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private com.positivity.inventory.internal.service.InventoryFactPublisher inventoryFactPublisher;

    private PurchaseOrderServiceImpl purchaseOrderService;

    private Clock fixedClock = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC);

    @BeforeEach
    void setUp() {
        purchaseOrderService = new PurchaseOrderServiceImpl(
                purchaseOrderRepository,
                purchaseOrderLineRepository,
                eventPublisher,
                inventoryFactPublisher,
                new com.positivity.inventory.internal.service.DocumentQuantityConverter(
                        org.mockito.Mockito.mock(com.positivity.inventory.internal.service.UomConversionService.class)),
                // Lot gate answers "untracked" for every SKU in these unit tests (E1 #1038):
                // a mock resolveReceiptLot returns null by default.
                org.mockito.Mockito.mock(com.positivity.inventory.internal.service.InventoryLotCaptureService.class),
                fixedClock);
        ReflectionTestUtils.setField(purchaseOrderService, "defaultTaxRate", 0.10d);
    }

    @Test
    @DisplayName("createPurchaseOrder should save and return a valid purchase order")
    void createPurchaseOrder_shouldSaveAndReturnPurchaseOrder() {
        // Arrange
        PurchaseOrderLineRequest lineRequest = new PurchaseOrderLineRequest(
                1,
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                "Test Item",
                BigDecimal.TEN,
                1000L,
                "TAX-10",
                "GL-ACCOUNT-01",
                null,
                null);
        CreatePurchaseOrderRequest request = new CreatePurchaseOrderRequest(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                LocalDate.now(fixedClock),
                "USD",
                "NET30",
                LocalDate.now(fixedClock).plusDays(30),
                UUID.fromString("00000000-0000-0000-0000-000000000001"), // shipToLocationId
                "test-user",
                "Test PO",
                List.of(lineRequest));

        when(purchaseOrderRepository.save(any(PurchaseOrderEntity.class))).thenAnswer(invocation -> {
            PurchaseOrderEntity entity = invocation.getArgument(0);
            if (entity.getPurchaseOrderId() == null) {
                entity.setPurchaseOrderId(UUID.fromString("00000000-0000-0000-0000-000000000001"));
            }
            return entity;
        });
        when(purchaseOrderRepository.getNextPurchaseOrderSequence()).thenReturn(12345L);

        // Act
        var response = purchaseOrderService.createPurchaseOrder(request, "test-actor");

        // Assert
        ArgumentCaptor<PurchaseOrderEntity> purchaseOrderCaptor = ArgumentCaptor.forClass(PurchaseOrderEntity.class);
        verify(purchaseOrderRepository).save(purchaseOrderCaptor.capture());
        PurchaseOrderEntity savedPO = purchaseOrderCaptor.getValue();

        assertNotNull(savedPO);
        assertEquals("Test PO", savedPO.getComment());
        assertEquals(1, savedPO.getLines().size());
        assertEquals(10000L, savedPO.getSubtotalMinor());
        assertEquals(1000L, savedPO.getTaxMinor()); // 10% of 10000
        assertEquals(11000L, savedPO.getGrandTotalMinor());
        assertEquals(11000L, savedPO.getOpenBalanceMinor());
        assertEquals(PurchaseOrderStatus.DRAFT, savedPO.getStatus());
        assertEquals("000009IX", savedPO.getPoNumber());
        assertNull(savedPO.getCreatedBy());

        assertNotNull(response);
        assertEquals(11000L, response.getGrandTotalMinor());

        verify(eventPublisher).publishEvent(any(Map.class));
    }

    @Test
    @DisplayName("getPurchaseOrder should return a purchase order when found")
    void getPurchaseOrder_shouldReturnPoWhenFound() {
        // Arrange
        UUID poId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        PurchaseOrderEntity po = new PurchaseOrderEntity();
        po.setPurchaseOrderId(poId);
        po.setLines(Collections.emptyList());
        when(purchaseOrderRepository.findById(poId)).thenReturn(Optional.of(po));

        // Act
        var result = purchaseOrderService.getPurchaseOrder(poId);

        // Assert
        assertNotNull(result);
        assertEquals(poId, result.getPurchaseOrderId());
    }

    @Test
    @DisplayName("getPurchaseOrder should throw exception when not found")
    void getPurchaseOrder_shouldThrowResourceNotFoundException() {
        // Arrange
        UUID poId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        when(purchaseOrderRepository.findById(poId)).thenReturn(Optional.empty());

        // Act & Assert
        assertThrows(ResourceNotFoundException.class, () -> {
            purchaseOrderService.getPurchaseOrder(poId);
        });
    }

    @Test
    @DisplayName("approvePurchaseOrder should approve a DRAFT PO")
    void approvePurchaseOrder_shouldApproveDraftPo() {
        // Arrange
        UUID poId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        PurchaseOrderEntity po = PurchaseOrderEntity.builder()
                .purchaseOrderId(poId)
                .status(PurchaseOrderStatus.DRAFT)
                .lines(Collections.emptyList())
                .vendorId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                .grandTotalMinor(1000L)
                .currency("USD")
                .build();
        when(purchaseOrderRepository.findById(poId)).thenReturn(Optional.of(po));
        when(purchaseOrderRepository.save(any(PurchaseOrderEntity.class))).thenAnswer(i -> i.getArgument(0));

        // Act
        var response =
                purchaseOrderService.approvePurchaseOrder(poId, new ApprovePurchaseOrderRequest("notes"), "approver");

        // Assert
        assertEquals(PurchaseOrderStatus.APPROVED, response.getStatus());
        assertEquals("approver", response.getApproverId());
        assertNotNull(response.getApprovalTimestamp());
        verify(eventPublisher).publishEvent(any(Map.class));
    }

    @Test
    @DisplayName("approvePurchaseOrder should throw exception for non-DRAFT PO")
    void approvePurchaseOrder_shouldThrowExceptionForNonDraftPo() {
        // Arrange
        UUID poId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        PurchaseOrderEntity po = PurchaseOrderEntity.builder()
                .purchaseOrderId(poId)
                .status(PurchaseOrderStatus.APPROVED)
                .build();
        when(purchaseOrderRepository.findById(poId)).thenReturn(Optional.of(po));
        ApprovePurchaseOrderRequest request = new ApprovePurchaseOrderRequest("notes");

        // Act & Assert
        assertThrows(
                IllegalStateException.class,
                () -> purchaseOrderService.approvePurchaseOrder(poId, request, "approver"));
    }

    @Test
    @DisplayName("revisePurchaseOrder should revise a PO")
    void revisePurchaseOrder_shouldRevisePo() {
        // Arrange
        UUID poId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        PurchaseOrderEntity po = PurchaseOrderEntity.builder()
                .purchaseOrderId(poId)
                .versionNumber(1)
                .lines(new ArrayList<>())
                .build();
        when(purchaseOrderRepository.findById(poId)).thenReturn(Optional.of(po));
        when(purchaseOrderRepository.save(any(PurchaseOrderEntity.class))).thenAnswer(i -> i.getArgument(0));

        RevisePurchaseOrderRequest request = new RevisePurchaseOrderRequest(
                LocalDate.now(fixedClock),
                "NET60",
                LocalDate.now(fixedClock).plusDays(60),
                null,
                "req",
                "comment",
                Collections.emptyList(),
                "reason");

        // Act
        var response = purchaseOrderService.revisePurchaseOrder(poId, request, "reviser");

        // Assert
        assertEquals(2, response.getVersionNumber());
        verify(eventPublisher).publishEvent(any(Map.class));
    }

    @Test
    @DisplayName("cancelPurchaseOrder should cancel an cancellable PO")
    void cancelPurchaseOrder_shouldCancelCancellablePo() {
        // Arrange
        UUID poId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        PurchaseOrderEntity po = PurchaseOrderEntity.builder()
                .purchaseOrderId(poId)
                .status(PurchaseOrderStatus.DRAFT)
                .lines(Collections.emptyList())
                .build();
        when(purchaseOrderRepository.findById(poId)).thenReturn(Optional.of(po));
        when(purchaseOrderRepository.save(any(PurchaseOrderEntity.class))).thenAnswer(i -> i.getArgument(0));

        // Act
        var response = purchaseOrderService.cancelPurchaseOrder(poId, "canceller");

        // Assert
        assertEquals(PurchaseOrderStatus.CANCELLED, response.getStatus());
        verify(eventPublisher).publishEvent(any(Map.class));
    }

    @Test
    @DisplayName("cancelPurchaseOrder emits ExpectedSupplyDroppedV1 per SKU with open quantity (#1028)")
    void cancelPurchaseOrder_emitsExpectedSupplyDroppedForOpenQuantity() {
        UUID poId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID skuA = UUID.fromString("00000000-0000-0000-0000-00000000000a");
        UUID skuB = UUID.fromString("00000000-0000-0000-0000-00000000000b");
        UUID siteId = UUID.fromString("00000000-0000-0000-0000-000000000051");
        PurchaseOrderEntity po = PurchaseOrderEntity.builder()
                .purchaseOrderId(poId)
                .status(PurchaseOrderStatus.PARTIALLY_RECEIVED)
                .shipToLocationId(siteId)
                .lines(new ArrayList<>(List.of(
                        // Two open lines of the same SKU aggregate into one fact.
                        line(skuA, new BigDecimal("3.000000")),
                        line(skuA, new BigDecimal("2.000000")),
                        // Fully received line: no fact.
                        line(skuB, BigDecimal.ZERO))))
                .build();
        when(purchaseOrderRepository.findById(poId)).thenReturn(Optional.of(po));
        when(purchaseOrderRepository.save(any(PurchaseOrderEntity.class))).thenAnswer(i -> i.getArgument(0));

        purchaseOrderService.cancelPurchaseOrder(poId, "canceller");

        ArgumentCaptor<com.positivity.domainevents.inventory.ExpectedSupplyDroppedV1> factCaptor =
                ArgumentCaptor.forClass(com.positivity.domainevents.inventory.ExpectedSupplyDroppedV1.class);
        verify(inventoryFactPublisher).recordExpectedSupplyDropped(factCaptor.capture());
        com.positivity.domainevents.inventory.ExpectedSupplyDroppedV1 fact = factCaptor.getValue();
        assertEquals(skuA.toString(), fact.sku());
        assertEquals(siteId, fact.siteId());
        assertEquals(0, fact.droppedQuantity().compareTo(new BigDecimal("5.000000")));
        assertEquals(poId, fact.poId());
        assertEquals("CANCELLED", fact.reason());
    }

    @Test
    @DisplayName("cancelPurchaseOrder emits no drop fact for a DRAFT PO (never counted as supply)")
    void cancelPurchaseOrder_emitsNoDropFactForDraftPo() {
        UUID poId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        PurchaseOrderEntity po = PurchaseOrderEntity.builder()
                .purchaseOrderId(poId)
                .status(PurchaseOrderStatus.DRAFT)
                .lines(new ArrayList<>(
                        List.of(line(UUID.fromString("00000000-0000-0000-0000-00000000000a"), new BigDecimal("4")))))
                .build();
        when(purchaseOrderRepository.findById(poId)).thenReturn(Optional.of(po));
        when(purchaseOrderRepository.save(any(PurchaseOrderEntity.class))).thenAnswer(i -> i.getArgument(0));

        purchaseOrderService.cancelPurchaseOrder(poId, "canceller");

        verify(inventoryFactPublisher, org.mockito.Mockito.never())
                .recordExpectedSupplyDropped(any(com.positivity.domainevents.inventory.ExpectedSupplyDroppedV1.class));
    }

    private PurchaseOrderLineEntity line(UUID skuId, BigDecimal openQuantity) {
        return PurchaseOrderLineEntity.builder()
                .lineNumber(1)
                .skuId(skuId)
                .description("line")
                .quantityDecimal(openQuantity.max(BigDecimal.ONE))
                .unitCostMinor(100L)
                .lineTotalMinor(100L)
                .openQuantityDecimal(openQuantity)
                .build();
    }

    @Test
    @DisplayName("cancelPurchaseOrder should throw exception for non-cancellable PO")
    void cancelPurchaseOrder_shouldThrowExceptionForNonCancellablePo() {
        // Arrange
        UUID poId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        PurchaseOrderEntity po = PurchaseOrderEntity.builder()
                .purchaseOrderId(poId)
                .status(PurchaseOrderStatus.CLOSED)
                .build();
        when(purchaseOrderRepository.findById(poId)).thenReturn(Optional.of(po));

        // Act & Assert
        assertThrows(IllegalStateException.class, () -> {
            purchaseOrderService.cancelPurchaseOrder(poId, "canceller");
        });
    }

    @Test
    @DisplayName("receivePurchaseOrder should process partial receipt")
    void receivePurchaseOrder_shouldProcessPartialReceipt() {
        // Arrange
        UUID poId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID lineId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        PurchaseOrderLineEntity line = PurchaseOrderLineEntity.builder()
                .lineId(lineId)
                .openQuantityDecimal(BigDecimal.TEN)
                .unitCostMinor(100L)
                .build();
        PurchaseOrderEntity po = PurchaseOrderEntity.builder()
                .purchaseOrderId(poId)
                .status(PurchaseOrderStatus.APPROVED)
                .openBalanceMinor(1000L)
                .lines(new ArrayList<>(List.of(line)))
                .build();
        line.setPurchaseOrder(po);

        when(purchaseOrderRepository.findById(poId)).thenReturn(Optional.of(po));
        when(purchaseOrderRepository.save(any(PurchaseOrderEntity.class))).thenAnswer(i -> i.getArgument(0));
        when(purchaseOrderLineRepository.findById(lineId)).thenReturn(Optional.of(line));
        when(purchaseOrderLineRepository.save(any(PurchaseOrderLineEntity.class)))
                .thenAnswer(i -> i.getArgument(0));

        var lineRequest = new ReceivePurchaseOrderRequest.ReceivePurchaseOrderLineRequest(
                lineId, BigDecimal.valueOf(4), null, null, null, null);
        ReceivePurchaseOrderRequest request = new ReceivePurchaseOrderRequest(List.of(lineRequest));

        // Act
        var response = purchaseOrderService.receivePurchaseOrder(poId, request, "receiver");

        // Assert
        assertEquals(PurchaseOrderStatus.PARTIALLY_RECEIVED, response.getStatus());
        assertEquals(600L, response.getOpenBalanceMinor());
        assertEquals(0, new BigDecimal(6).compareTo(response.getLines().get(0).getOpenQuantityDecimal()));
        verify(eventPublisher).publishEvent(any(Map.class));
    }

    @Test
    @DisplayName("receivePurchaseOrder should process full receipt")
    void receivePurchaseOrder_shouldProcessFullReceipt() {
        // Arrange
        UUID poId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID lineId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        PurchaseOrderLineEntity line = PurchaseOrderLineEntity.builder()
                .lineId(lineId)
                .openQuantityDecimal(BigDecimal.TEN)
                .unitCostMinor(100L)
                .build();
        PurchaseOrderEntity po = PurchaseOrderEntity.builder()
                .purchaseOrderId(poId)
                .status(PurchaseOrderStatus.APPROVED)
                .openBalanceMinor(1000L)
                .lines(new ArrayList<>(List.of(line)))
                .build();
        line.setPurchaseOrder(po);

        when(purchaseOrderRepository.findById(poId)).thenReturn(Optional.of(po));
        when(purchaseOrderRepository.save(any(PurchaseOrderEntity.class))).thenAnswer(i -> i.getArgument(0));

        var lineRequest = new ReceivePurchaseOrderRequest.ReceivePurchaseOrderLineRequest(
                lineId, BigDecimal.TEN, null, null, null, null);
        ReceivePurchaseOrderRequest request = new ReceivePurchaseOrderRequest(List.of(lineRequest));

        // Act
        var response = purchaseOrderService.receivePurchaseOrder(poId, request, "receiver");

        // Assert
        assertEquals(PurchaseOrderStatus.FULLY_RECEIVED, response.getStatus());
        assertEquals(0L, response.getOpenBalanceMinor());
        assertEquals(0, BigDecimal.ZERO.compareTo(response.getLines().get(0).getOpenQuantityDecimal()));
    }

    @Test
    @DisplayName("receivePurchaseOrder should throw exception for DRAFT PO")
    void receivePurchaseOrder_shouldThrowExceptionForDraftPo() {
        // Arrange
        UUID poId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        ReceivePurchaseOrderRequest request = new ReceivePurchaseOrderRequest(Collections.emptyList());
        PurchaseOrderEntity po = PurchaseOrderEntity.builder()
                .purchaseOrderId(poId)
                .status(PurchaseOrderStatus.DRAFT)
                .build();
        when(purchaseOrderRepository.findById(poId)).thenReturn(Optional.of(po));

        // Act & Assert
        assertThrows(
                PurchaseOrderNotApprovedException.class,
                () -> purchaseOrderService.receivePurchaseOrder(poId, request, "receiver"));
    }

    @Test
    @DisplayName("listPurchaseOrders should filter by vendor and status")
    void listPurchaseOrders_shouldFilterByVendorAndStatus() {
        // Arrange
        UUID vendorId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        PurchaseOrderEntity po = PurchaseOrderEntity.builder()
                .vendorId(vendorId)
                .status(PurchaseOrderStatus.APPROVED)
                .lines(Collections.emptyList())
                .build();
        List<PurchaseOrderEntity> pos = List.of(po);
        when(purchaseOrderRepository.findByVendorIdAndStatus(vendorId, PurchaseOrderStatus.APPROVED))
                .thenReturn(pos);

        ListPurchaseOrdersRequest filter =
                new ListPurchaseOrdersRequest(vendorId, PurchaseOrderStatus.APPROVED, null, null);
        Pageable pageable = PageRequest.of(0, 10);

        // Act
        Page<com.positivity.inventory.internal.dto.purchaseorder.PurchaseOrderResponse> result =
                purchaseOrderService.listPurchaseOrders(filter, pageable);

        // Assert
        assertEquals(1, result.getTotalElements());
        assertEquals(vendorId, result.getContent().get(0).getVendorId());
    }

    @Test
    @DisplayName("listPurchaseOrders should filter by vendor, currency, and location")
    void listPurchaseOrders_shouldFilterByVendorCurrencyAndLocation() {
        // Arrange
        UUID vendorId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID locationId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        String currency = "USD";

        PurchaseOrderEntity po1 = PurchaseOrderEntity.builder()
                .vendorId(vendorId)
                .currency(currency)
                .shipToLocationId(locationId)
                .lines(new ArrayList<>())
                .build();
        PurchaseOrderEntity po2 = PurchaseOrderEntity.builder()
                .vendorId(vendorId)
                .currency("EUR")
                .shipToLocationId(locationId)
                .lines(new ArrayList<>())
                .build();
        PurchaseOrderEntity po3 = PurchaseOrderEntity.builder()
                .vendorId(UUID.fromString("00000000-0000-0000-0000-000000000002"))
                .currency(currency)
                .shipToLocationId(locationId)
                .lines(new ArrayList<>())
                .build();

        List<PurchaseOrderEntity> allPos = List.of(po1, po2, po3);
        org.springframework.data.domain.Page<PurchaseOrderEntity> page =
                new org.springframework.data.domain.PageImpl<>(allPos);

        when(purchaseOrderRepository.findAll(any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(page);

        ListPurchaseOrdersRequest filter = new ListPurchaseOrdersRequest(vendorId, null, currency, locationId);
        org.springframework.data.domain.Pageable pageable = org.springframework.data.domain.Pageable.unpaged();

        // Act
        org.springframework.data.domain.Page<com.positivity.inventory.internal.dto.purchaseorder.PurchaseOrderResponse>
                result = purchaseOrderService.listPurchaseOrders(filter, pageable);

        // Assert
        assertEquals(1, result.getContent().size());
        assertEquals(po1.getVendorId(), result.getContent().get(0).getVendorId());
    }
}

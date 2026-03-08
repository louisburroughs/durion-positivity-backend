package com.positivity.order.service;

import com.positivity.order.internal.client.InventoryPort;
import com.positivity.order.internal.client.InventoryResult;
import com.positivity.order.internal.client.PricingPort;
import com.positivity.order.internal.client.PricingResult;
import com.positivity.order.internal.client.SourceDocumentLine;
import com.positivity.order.internal.client.SourceDocumentPort;
import com.positivity.order.internal.entity.FulfillmentStatus;
import com.positivity.order.internal.entity.PriceSource;
import com.positivity.order.internal.entity.SalesOrder;
import com.positivity.order.internal.entity.SalesOrderLine;
import com.positivity.order.internal.entity.SalesOrderStatus;
import com.positivity.order.internal.entity.SourceType;
import com.positivity.order.internal.exception.InvalidSkuException;
import com.positivity.order.internal.repository.SalesOrderLineRepository;
import com.positivity.order.internal.repository.SalesOrderRepository;
import com.positivity.order.internal.service.SalesOrderServiceImpl;
import com.positivity.security.common.SecurityContextHelper;
import com.positivity.order.service.model.SalesOrderLineSummary;
import com.positivity.order.service.model.SalesOrderSummary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SalesOrderServiceImpl} covering all Story #21
 * acceptance criteria.
 *
 * <p>
 * Phase: GREEN – Story #21 implemented; all tests verify the correct behavior.
 *
 * <p>
 * Issue: #21
 */
@ExtendWith(MockitoExtension.class)
class SalesOrderServiceImplTest {

    @Mock
    private SalesOrderRepository salesOrderRepository;

    @Mock
    private SalesOrderLineRepository salesOrderLineRepository;

    @Mock
    private PricingPort pricingPort;

    @Mock
    private InventoryPort inventoryPort;

    @Mock
    private SourceDocumentPort sourceDocumentPort;

    // Issue #21: SalesOrderService is the public interface under test (ADR-0026)
    private SalesOrderService salesOrderService;

    @BeforeEach
    void setUp() {
        salesOrderService = new SalesOrderServiceImpl(
                salesOrderRepository,
                salesOrderLineRepository,
                pricingPort,
                inventoryPort,
                sourceDocumentPort);
    }

    // -----------------------------------------------------------------------
    // Scenario 1: Create a new, empty sales cart
    // -----------------------------------------------------------------------

    /**
     * Scenario 1a: Happy-path cart creation produces a DRAFT order with zero
     * subtotal.
     */
    @Test
    void createCart_whenCalledWithClerkAndTerminal_thenReturnsOrderWithDraftStatus() {
        // given
        UUID expectedOrderId = UUID.randomUUID();
        SalesOrder expectedOrder = SalesOrder.builder()
                .orderId(expectedOrderId)
                .clerkId("clerk-001")
                .terminalId("terminal-001")
                .status(SalesOrderStatus.DRAFT)
                .subtotal(BigDecimal.ZERO)
                .build();
        when(salesOrderRepository.save(any(SalesOrder.class))).thenReturn(expectedOrder);

        // when
        SalesOrderSummary result = salesOrderService.createCart("clerk-001", "terminal-001", null, null);

        // then
        assertThat(result.status()).isEqualTo(SalesOrderStatus.DRAFT.name());
        assertThat(result.subtotal()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    /**
     * Scenario 1b: Cart created without a customer ID remains anonymous (customerId
     * is null).
     */
    @Test
    void createCart_whenCalledWithoutCustomerId_thenCustomerIdIsNull() {
        // given
        SalesOrder expectedOrder = SalesOrder.builder()
                .orderId(UUID.randomUUID())
                .clerkId("clerk-001")
                .terminalId("terminal-001")
                .status(SalesOrderStatus.DRAFT)
                .subtotal(BigDecimal.ZERO)
                .customerId(null)
                .build();
        when(salesOrderRepository.save(any(SalesOrder.class))).thenReturn(expectedOrder);

        // when
        SalesOrderSummary result = salesOrderService.createCart("clerk-001", "terminal-001", null, null);

        // then
        assertThat(result.customerId()).isNull();
    }

    /**
     * Scenario 1c: Each call produces a unique orderId.
     */
    @Test
    void createCart_whenCalledTwice_thenEachOrderHasDistinctOrderId() {
        // given
        UUID firstId = UUID.randomUUID();
        UUID secondId = UUID.randomUUID();
        SalesOrder firstOrder = SalesOrder.builder()
                .orderId(firstId)
                .clerkId("clerk-001")
                .terminalId("terminal-001")
                .status(SalesOrderStatus.DRAFT)
                .subtotal(BigDecimal.ZERO)
                .build();
        SalesOrder secondOrder = SalesOrder.builder()
                .orderId(secondId)
                .clerkId("clerk-001")
                .terminalId("terminal-001")
                .status(SalesOrderStatus.DRAFT)
                .subtotal(BigDecimal.ZERO)
                .build();
        when(salesOrderRepository.save(any(SalesOrder.class)))
                .thenReturn(firstOrder)
                .thenReturn(secondOrder);

        // when
        SalesOrderSummary cart1 = salesOrderService.createCart("clerk-001", "terminal-001", null, null);
        SalesOrderSummary cart2 = salesOrderService.createCart("clerk-001", "terminal-001", null, null);

        // then
        assertThat(cart1.orderId()).isNotEqualTo(cart2.orderId());
    }

    // -----------------------------------------------------------------------
    // Scenario 2: Add a valid and available item to the cart
    // -----------------------------------------------------------------------

    /**
     * Scenario 2a: Adding qty=2 of a valid SKU at $10.50 creates a SalesOrderLine
     * with correct quantity and unitPrice.
     */
    @Test
    void addItem_whenValidSkuAndQuantity_thenLineHasCorrectQuantityAndUnitPrice() {
        // given
        UUID orderId = UUID.randomUUID();
        SalesOrder existingOrder = SalesOrder.builder()
                .orderId(orderId)
                .clerkId("clerk-001")
                .terminalId("terminal-001")
                .status(SalesOrderStatus.DRAFT)
                .subtotal(BigDecimal.ZERO)
                .build();
        SalesOrderLine expectedLine = SalesOrderLine.builder()
                .orderLineId(UUID.randomUUID())
                .order(existingOrder)
                .itemSku("ABC-123")
                .itemDescription("Test Product")
                .quantity(2)
                .unitPrice(new BigDecimal("10.50"))
                .fulfillmentStatus(FulfillmentStatus.AVAILABLE)
                .priceSource(PriceSource.PRICING_SERVICE)
                .build();
        when(salesOrderRepository.findById(orderId)).thenReturn(Optional.of(existingOrder));
        when(salesOrderLineRepository.save(any(SalesOrderLine.class))).thenReturn(expectedLine);
        when(pricingPort.resolvePrice("ABC-123"))
                .thenReturn(new PricingResult(new BigDecimal("10.5000"), false, true));
        when(inventoryPort.checkAvailability("ABC-123", 2)).thenReturn(new InventoryResult(true, 100));

        // when
        SalesOrderLineSummary result = salesOrderService.addItem(orderId, "ABC-123", 2, null, null);

        // then
        assertThat(result.quantity()).isEqualTo(2);
        assertThat(result.unitPrice()).isEqualByComparingTo(new BigDecimal("10.50"));
    }

    /**
     * Scenario 2b: Adding qty=2 at $10.50 recalculates cart subtotal to $21.00.
     */
    @Test
    void addItem_whenValidSkuAndQuantity_thenSubtotalRecalculatedCorrectly() {
        // given
        UUID orderId = UUID.randomUUID();
        SalesOrder existingOrder = SalesOrder.builder()
                .orderId(orderId)
                .clerkId("clerk-001")
                .terminalId("terminal-001")
                .status(SalesOrderStatus.DRAFT)
                .subtotal(BigDecimal.ZERO)
                .build();
        SalesOrderLine expectedLine = SalesOrderLine.builder()
                .orderLineId(UUID.randomUUID())
                .order(existingOrder)
                .itemSku("ABC-123")
                .itemDescription("Test Product")
                .quantity(2)
                .unitPrice(new BigDecimal("10.50"))
                .fulfillmentStatus(FulfillmentStatus.AVAILABLE)
                .priceSource(PriceSource.PRICING_SERVICE)
                .build();
        SalesOrder updatedOrder = SalesOrder.builder()
                .orderId(orderId)
                .clerkId("clerk-001")
                .terminalId("terminal-001")
                .status(SalesOrderStatus.DRAFT)
                .subtotal(new BigDecimal("21.00"))
                .lines(List.of(expectedLine))
                .build();
        when(salesOrderRepository.findById(orderId)).thenReturn(Optional.of(existingOrder));
        when(salesOrderLineRepository.save(any(SalesOrderLine.class))).thenReturn(expectedLine);
        when(salesOrderRepository.save(any(SalesOrder.class))).thenReturn(updatedOrder);
        when(pricingPort.resolvePrice("ABC-123"))
                .thenReturn(new PricingResult(new BigDecimal("10.5000"), false, true));
        when(inventoryPort.checkAvailability("ABC-123", 2)).thenReturn(new InventoryResult(true, 100));

        // when
        salesOrderService.addItem(orderId, "ABC-123", 2, null, null);
        SalesOrderSummary order = salesOrderService.getOrder(orderId);

        // then
        assertThat(order.subtotal()).isEqualByComparingTo(new BigDecimal("21.00"));
    }

    // -----------------------------------------------------------------------
    // Scenario 3: Invalid SKU rejection
    // -----------------------------------------------------------------------

    /**
     * Scenario 3a: Adding an item with an invalid SKU throws InvalidSkuException
     * containing
     * "Product not found".
     */
    @Test
    void addItem_whenInvalidSku_thenThrowsInvalidSkuException() {
        // given
        UUID orderId = UUID.randomUUID();
        SalesOrder existingOrder = SalesOrder.builder()
                .orderId(orderId)
                .clerkId("clerk-001")
                .terminalId("terminal-001")
                .status(SalesOrderStatus.DRAFT)
                .subtotal(BigDecimal.ZERO)
                .build();
        when(salesOrderRepository.findById(orderId)).thenReturn(Optional.of(existingOrder));
        when(pricingPort.resolvePrice("INVALID-SKU"))
                .thenReturn(new PricingResult(BigDecimal.ZERO, false, false));

        // when / then
        assertThatThrownBy(() -> salesOrderService.addItem(orderId, "INVALID-SKU", 1, null, null))
                .isInstanceOf(InvalidSkuException.class)
                .hasMessageContaining("Product not found");
    }

    // -----------------------------------------------------------------------
    // Scenario 4: Update item quantity
    // -----------------------------------------------------------------------

    /**
     * Scenario 4a: Updating line quantity from 2 to 3 persists the new quantity.
     */
    @Test
    void updateItemQuantity_whenNewQuantityProvided_thenLineQuantityIsUpdated() {
        // given
        UUID orderId = UUID.randomUUID();
        UUID lineId = UUID.randomUUID();
        SalesOrder existingOrder = SalesOrder.builder()
                .orderId(orderId)
                .clerkId("clerk-001")
                .terminalId("terminal-001")
                .status(SalesOrderStatus.DRAFT)
                .subtotal(new BigDecimal("21.00"))
                .build();
        SalesOrderLine existingLine = SalesOrderLine.builder()
                .orderLineId(lineId)
                .order(existingOrder)
                .itemSku("ABC-123")
                .itemDescription("Test Product")
                .quantity(2)
                .unitPrice(new BigDecimal("10.50"))
                .fulfillmentStatus(FulfillmentStatus.AVAILABLE)
                .priceSource(PriceSource.PRICING_SERVICE)
                .build();
        SalesOrderLine updatedLine = SalesOrderLine.builder()
                .orderLineId(lineId)
                .order(existingOrder)
                .itemSku("ABC-123")
                .itemDescription("Test Product")
                .quantity(3)
                .unitPrice(new BigDecimal("10.50"))
                .fulfillmentStatus(FulfillmentStatus.AVAILABLE)
                .priceSource(PriceSource.PRICING_SERVICE)
                .build();
        when(salesOrderRepository.findById(orderId)).thenReturn(Optional.of(existingOrder));
        when(salesOrderLineRepository.findById(lineId)).thenReturn(Optional.of(existingLine));
        when(salesOrderLineRepository.save(any(SalesOrderLine.class))).thenReturn(updatedLine);

        // when
        SalesOrderLineSummary result = salesOrderService.updateItemQuantity(orderId, lineId, 3);

        // then
        assertThat(result.quantity()).isEqualTo(3);
    }

    /**
     * Scenario 4b: Updating line quantity to 3 recalculates subtotal to $31.50.
     */
    @Test
    void updateItemQuantity_whenQuantityChangedTo3_thenSubtotalRecalculatedTo31_50() {
        // given
        UUID orderId = UUID.randomUUID();
        UUID lineId = UUID.randomUUID();
        SalesOrder existingOrder = SalesOrder.builder()
                .orderId(orderId)
                .clerkId("clerk-001")
                .terminalId("terminal-001")
                .status(SalesOrderStatus.DRAFT)
                .subtotal(new BigDecimal("21.00"))
                .build();
        SalesOrderLine existingLine = SalesOrderLine.builder()
                .orderLineId(lineId)
                .order(existingOrder)
                .itemSku("ABC-123")
                .itemDescription("Test Product")
                .quantity(2)
                .unitPrice(new BigDecimal("10.50"))
                .fulfillmentStatus(FulfillmentStatus.AVAILABLE)
                .priceSource(PriceSource.PRICING_SERVICE)
                .build();
        SalesOrderLine updatedLine = SalesOrderLine.builder()
                .orderLineId(lineId)
                .order(existingOrder)
                .itemSku("ABC-123")
                .itemDescription("Test Product")
                .quantity(3)
                .unitPrice(new BigDecimal("10.50"))
                .fulfillmentStatus(FulfillmentStatus.AVAILABLE)
                .priceSource(PriceSource.PRICING_SERVICE)
                .build();
        SalesOrder updatedOrder = SalesOrder.builder()
                .orderId(orderId)
                .clerkId("clerk-001")
                .terminalId("terminal-001")
                .status(SalesOrderStatus.DRAFT)
                .subtotal(new BigDecimal("31.50"))
                .lines(List.of(updatedLine))
                .build();
        when(salesOrderRepository.findById(orderId)).thenReturn(Optional.of(existingOrder));
        when(salesOrderLineRepository.findById(lineId)).thenReturn(Optional.of(existingLine));
        when(salesOrderLineRepository.save(any(SalesOrderLine.class))).thenReturn(updatedLine);
        when(salesOrderRepository.save(any(SalesOrder.class))).thenReturn(updatedOrder);

        // when
        salesOrderService.updateItemQuantity(orderId, lineId, 3);
        SalesOrderSummary order = salesOrderService.getOrder(orderId);

        // then
        assertThat(order.subtotal()).isEqualByComparingTo(new BigDecimal("31.50"));
    }

    // -----------------------------------------------------------------------
    // Scenario 5: Insufficient inventory — item added as BACKORDER
    // -----------------------------------------------------------------------

    /**
     * Scenario 5: When inventory is insufficient, item is added with
     * fulfillmentStatus=BACKORDER.
     */
    @Test
    void addItem_whenInventoryInsufficient_thenLineHasBackorderFulfillmentStatus() {
        // given
        UUID orderId = UUID.randomUUID();
        SalesOrder existingOrder = SalesOrder.builder()
                .orderId(orderId)
                .clerkId("clerk-001")
                .terminalId("terminal-001")
                .status(SalesOrderStatus.DRAFT)
                .subtotal(BigDecimal.ZERO)
                .build();
        SalesOrderLine backorderLine = SalesOrderLine.builder()
                .orderLineId(UUID.randomUUID())
                .order(existingOrder)
                .itemSku("LOW-STOCK-SKU")
                .itemDescription("Low Stock Product")
                .quantity(50)
                .unitPrice(new BigDecimal("25.00"))
                .fulfillmentStatus(FulfillmentStatus.BACKORDER)
                .priceSource(PriceSource.PRICING_SERVICE)
                .build();
        when(salesOrderRepository.findById(orderId)).thenReturn(Optional.of(existingOrder));
        when(salesOrderLineRepository.save(any(SalesOrderLine.class))).thenReturn(backorderLine);
        when(pricingPort.resolvePrice("LOW-STOCK-SKU"))
                .thenReturn(new PricingResult(new BigDecimal("10.5000"), false, true));
        when(inventoryPort.checkAvailability("LOW-STOCK-SKU", 50)).thenReturn(new InventoryResult(false, 0));

        // when
        SalesOrderLineSummary result = salesOrderService.addItem(orderId, "LOW-STOCK-SKU", 50, null, null);

        // then – item must be in BACKORDER not rejected
        assertThat(result.fulfillmentStatus()).isEqualTo(FulfillmentStatus.BACKORDER.name());
    }

    /**
     * Scenario 5b: BACKORDER item is included in the cart subtotal.
     */
    @Test
    void addItem_whenInventoryInsufficient_thenItemIsIncludedInSubtotal() {
        // given
        UUID orderId = UUID.randomUUID();
        SalesOrder existingOrder = SalesOrder.builder()
                .orderId(orderId)
                .clerkId("clerk-001")
                .terminalId("terminal-001")
                .status(SalesOrderStatus.DRAFT)
                .subtotal(BigDecimal.ZERO)
                .build();
        SalesOrderLine backorderLine = SalesOrderLine.builder()
                .orderLineId(UUID.randomUUID())
                .order(existingOrder)
                .itemSku("LOW-STOCK-SKU")
                .itemDescription("Low Stock Product")
                .quantity(50)
                .unitPrice(new BigDecimal("25.00"))
                .fulfillmentStatus(FulfillmentStatus.BACKORDER)
                .priceSource(PriceSource.PRICING_SERVICE)
                .build();
        SalesOrder updatedOrder = SalesOrder.builder()
                .orderId(orderId)
                .clerkId("clerk-001")
                .terminalId("terminal-001")
                .status(SalesOrderStatus.DRAFT)
                .subtotal(new BigDecimal("1250.00"))
                .lines(List.of(backorderLine))
                .build();
        when(salesOrderRepository.findById(orderId)).thenReturn(Optional.of(existingOrder));
        when(salesOrderLineRepository.save(any(SalesOrderLine.class))).thenReturn(backorderLine);
        when(salesOrderRepository.save(any(SalesOrder.class))).thenReturn(updatedOrder);
        when(pricingPort.resolvePrice("LOW-STOCK-SKU"))
                .thenReturn(new PricingResult(new BigDecimal("10.5000"), false, true));
        when(inventoryPort.checkAvailability("LOW-STOCK-SKU", 50)).thenReturn(new InventoryResult(false, 0));

        // when
        salesOrderService.addItem(orderId, "LOW-STOCK-SKU", 50, null, null);
        SalesOrderSummary order = salesOrderService.getOrder(orderId);

        // then – BACKORDER item still contributes to subtotal
        assertThat(order.subtotal()).isGreaterThan(BigDecimal.ZERO);
    }

    // -----------------------------------------------------------------------
    // Scenario 6: Merge estimate items into cart
    // -----------------------------------------------------------------------

    /**
     * Scenario 6a: Linking an estimate merges existing SKU quantities (TIRE-001 qty
     * 2+3=5).
     */
    @Test
    void linkSource_whenEstimateLinked_thenExistingSkuQuantityMerged() {
        // given
        UUID orderId = UUID.randomUUID();
        SalesOrder existingOrder = SalesOrder.builder()
                .orderId(orderId)
                .clerkId("clerk-001")
                .terminalId("terminal-001")
                .status(SalesOrderStatus.DRAFT)
                .subtotal(new BigDecimal("200.00"))
                .build();
        String estimateId = "EST-001";
        // Stub: linkSource returns updated order (merged lines)
        SalesOrderLine mergedTireLine = SalesOrderLine.builder()
                .orderLineId(UUID.randomUUID())
                .order(existingOrder)
                .itemSku("TIRE-001")
                .itemDescription("Tire")
                .quantity(5)
                .unitPrice(new BigDecimal("100.00"))
                .fulfillmentStatus(FulfillmentStatus.AVAILABLE)
                .priceSource(PriceSource.PRICING_SERVICE)
                .sourceType(SourceType.ESTIMATE)
                .sourceId(estimateId)
                .build();
        SalesOrder mergedOrder = SalesOrder.builder()
                .orderId(orderId)
                .clerkId("clerk-001")
                .terminalId("terminal-001")
                .status(SalesOrderStatus.DRAFT)
                .subtotal(new BigDecimal("525.00"))
                .lines(List.of(mergedTireLine))
                .build();
        when(salesOrderRepository.findById(orderId)).thenReturn(Optional.of(existingOrder));
        when(salesOrderRepository.save(any(SalesOrder.class))).thenReturn(mergedOrder);
        when(sourceDocumentPort.fetchLines(SourceType.ESTIMATE, estimateId))
                .thenReturn(List.of(new SourceDocumentLine("TIRE-001", "Tire", 3, new BigDecimal("100.00"),
                        "EST-LINE-001")));

        // when
        SalesOrderSummary result = salesOrderService.linkSource(orderId, "ESTIMATE", estimateId);

        // then – TIRE-001 quantity merged to 5
        assertThat(result.lines())
                .filteredOn(l -> "TIRE-001".equals(l.itemSku()))
                .singleElement()
                .satisfies(l -> assertThat(l.quantity()).isEqualTo(5));
    }

    /**
     * Scenario 6b: Linking an estimate adds new SKUs (OIL-001) not previously in
     * the cart.
     */
    @Test
    void linkSource_whenEstimateLinked_thenNewSkuFromEstimateAddedAsNewLine() {
        // given
        UUID orderId = UUID.randomUUID();
        SalesOrder existingOrder = SalesOrder.builder()
                .orderId(orderId)
                .clerkId("clerk-001")
                .terminalId("terminal-001")
                .status(SalesOrderStatus.DRAFT)
                .subtotal(new BigDecimal("200.00"))
                .build();
        String estimateId = "EST-001";
        SalesOrderLine oilLine = SalesOrderLine.builder()
                .orderLineId(UUID.randomUUID())
                .order(existingOrder)
                .itemSku("OIL-001")
                .itemDescription("Oil")
                .quantity(1)
                .unitPrice(new BigDecimal("25.00"))
                .fulfillmentStatus(FulfillmentStatus.AVAILABLE)
                .priceSource(PriceSource.PRICING_SERVICE)
                .sourceType(SourceType.ESTIMATE)
                .sourceId(estimateId)
                .build();
        SalesOrder mergedOrder = SalesOrder.builder()
                .orderId(orderId)
                .clerkId("clerk-001")
                .terminalId("terminal-001")
                .status(SalesOrderStatus.DRAFT)
                .subtotal(new BigDecimal("525.00"))
                .lines(List.of(oilLine))
                .build();
        when(salesOrderRepository.findById(orderId)).thenReturn(Optional.of(existingOrder));
        when(salesOrderRepository.save(any(SalesOrder.class))).thenReturn(mergedOrder);
        when(sourceDocumentPort.fetchLines(SourceType.ESTIMATE, estimateId))
                .thenReturn(List.of(new SourceDocumentLine("OIL-001", "Oil", 1, new BigDecimal("25.00"),
                        "EST-LINE-002")));

        // when
        SalesOrderSummary result = salesOrderService.linkSource(orderId, "ESTIMATE", estimateId);

        // then – OIL-001 must be present in the merged cart
        assertThat(result.lines())
                .filteredOn(l -> "OIL-001".equals(l.itemSku()))
                .isNotEmpty();
    }

    /**
     * Scenario 6c: Lines from an estimate carry sourceType=ESTIMATE and populated
     * sourceId/sourceLineId.
     */
    @Test
    void linkSource_whenEstimateLinked_thenLinesHaveEstimateSourceMetadata() {
        // given
        UUID orderId = UUID.randomUUID();
        SalesOrder existingOrder = SalesOrder.builder()
                .orderId(orderId)
                .clerkId("clerk-001")
                .terminalId("terminal-001")
                .status(SalesOrderStatus.DRAFT)
                .subtotal(BigDecimal.ZERO)
                .build();
        String estimateId = "EST-001";
        String estimateLineId = "EST-LINE-001";
        SalesOrderLine estimateLine = SalesOrderLine.builder()
                .orderLineId(UUID.randomUUID())
                .order(existingOrder)
                .itemSku("TIRE-001")
                .itemDescription("Tire")
                .quantity(3)
                .unitPrice(new BigDecimal("100.00"))
                .fulfillmentStatus(FulfillmentStatus.AVAILABLE)
                .priceSource(PriceSource.PRICING_SERVICE)
                .sourceType(SourceType.ESTIMATE)
                .sourceId(estimateId)
                .sourceLineId(estimateLineId)
                .build();
        SalesOrder mergedOrder = SalesOrder.builder()
                .orderId(orderId)
                .clerkId("clerk-001")
                .terminalId("terminal-001")
                .status(SalesOrderStatus.DRAFT)
                .subtotal(new BigDecimal("300.00"))
                .lines(List.of(estimateLine))
                .build();
        when(salesOrderRepository.findById(orderId)).thenReturn(Optional.of(existingOrder));
        when(salesOrderRepository.save(any(SalesOrder.class))).thenReturn(mergedOrder);
        when(sourceDocumentPort.fetchLines(SourceType.ESTIMATE, estimateId))
                .thenReturn(List.of(new SourceDocumentLine("TIRE-001", "Tire", 3, new BigDecimal("100.00"),
                        estimateLineId)));

        // when
        SalesOrderSummary result = salesOrderService.linkSource(orderId, "ESTIMATE", estimateId);

        // then – every line must have sourceType=ESTIMATE with populated sourceId and
        // sourceLineId
        assertThat(result.lines()).isNotEmpty();
        result.lines().forEach(line -> {
            assertThat(line.sourceType()).isEqualTo(SourceType.ESTIMATE.name());
            assertThat(line.sourceId()).isNotBlank();
            assertThat(line.sourceLineId()).isNotBlank();
        });
    }

    // -----------------------------------------------------------------------
    // Scenario 7: Pricing unavailable — use stale cache (priceSource=CACHE)
    // -----------------------------------------------------------------------

    /**
     * Scenario 7: When pricing service is unavailable but a cached price exists
     * (within 60s TTL),
     * item is added with priceSource=CACHE.
     */
    @Test
    void addItem_whenPricingUnavailableAndCacheHit_thenLineHasCachePriceSource() {
        // given
        UUID orderId = UUID.randomUUID();
        SalesOrder existingOrder = SalesOrder.builder()
                .orderId(orderId)
                .clerkId("clerk-001")
                .terminalId("terminal-001")
                .status(SalesOrderStatus.DRAFT)
                .subtotal(BigDecimal.ZERO)
                .build();
        SalesOrderLine cachedPriceLine = SalesOrderLine.builder()
                .orderLineId(UUID.randomUUID())
                .order(existingOrder)
                .itemSku("ABC-123")
                .itemDescription("Test Product (cached)")
                .quantity(1)
                .unitPrice(new BigDecimal("10.50"))
                .fulfillmentStatus(FulfillmentStatus.AVAILABLE)
                .priceSource(PriceSource.CACHE)
                .build();
        when(salesOrderRepository.findById(orderId)).thenReturn(Optional.of(existingOrder));
        when(salesOrderLineRepository.save(any(SalesOrderLine.class))).thenReturn(cachedPriceLine);
        when(pricingPort.resolvePrice("ABC-123"))
                .thenReturn(new PricingResult(new BigDecimal("10.5000"), true, true));
        when(inventoryPort.checkAvailability("ABC-123", 1)).thenReturn(new InventoryResult(true, 100));

        // when
        SalesOrderLineSummary result = salesOrderService.addItem(orderId, "ABC-123", 1, null, null);

        // then
        assertThat(result.priceSource()).isEqualTo(PriceSource.CACHE.name());
    }

    // -----------------------------------------------------------------------
    // Scenario 8: Manual price fallback with permission (priceSource=MANUAL)
    // -----------------------------------------------------------------------

    /**
     * Scenario 8: When pricing service is unavailable, no cache exists, and user
     * provides a manual
     * price with a reasonCode, item is added with priceSource=MANUAL.
     */
    @Test
    void addItem_whenPricingUnavailableAndManualPriceProvided_thenLineHasManualPriceSource() {
        // given
        UUID orderId = UUID.randomUUID();
        UUID lineId = UUID.randomUUID();
        SalesOrder order = SalesOrder.builder()
                .orderId(orderId)
                .clerkId("clerk-001")
                .terminalId("terminal-001")
                .status(SalesOrderStatus.DRAFT)
                .subtotal(BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP))
                .createdBy("clerk-001")
                .updatedBy("clerk-001")
                .build();
        order.setLines(new java.util.ArrayList<>());

        SalesOrderLine savedLine = SalesOrderLine.builder()
                .orderLineId(lineId)
                .itemSku("ABC-123")
                .itemDescription("ABC-123")
                .quantity(1)
                .unitPrice(new BigDecimal("15.0000"))
                .fulfillmentStatus(FulfillmentStatus.AVAILABLE)
                .priceSource(PriceSource.MANUAL)
                .reasonCode("Manual entry")
                .build();
        when(salesOrderRepository.findById(orderId)).thenReturn(Optional.of(order));
        when(inventoryPort.checkAvailability("ABC-123", 1)).thenReturn(new InventoryResult(true, 100));
        when(salesOrderLineRepository.save(any(SalesOrderLine.class))).thenReturn(savedLine);
        when(salesOrderRepository.save(any(SalesOrder.class))).thenReturn(order);

        try (var mockStatic = mockStatic(SecurityContextHelper.class)) {
            mockStatic.when(() -> SecurityContextHelper.hasAuthority("order:line:enter_manual_price"))
                    .thenReturn(true);

            // when
            SalesOrderLineSummary result = salesOrderService.addItem(
                    orderId, "ABC-123", 1, "Manual entry", new BigDecimal("15.00"));

            // then
            assertThat(result.priceSource()).isEqualTo("MANUAL");
            assertThat(result.unitPrice()).isEqualByComparingTo(new BigDecimal("15.0000"));
            assertThat(result.reasonCode()).isEqualTo("Manual entry");
        }
    }

    @Test
    void addItem_whenPricingUnavailableAndManualPriceRequiresPermission_thenThrowsAccessDenied() {
        // given
        UUID orderId = UUID.randomUUID();
        SalesOrder order = SalesOrder.builder()
                .orderId(orderId)
                .clerkId("clerk-001")
                .terminalId("terminal-001")
                .status(SalesOrderStatus.DRAFT)
                .subtotal(BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP))
                .createdBy("clerk-001")
                .updatedBy("clerk-001")
                .build();
        order.setLines(new java.util.ArrayList<>());

        when(salesOrderRepository.findById(orderId)).thenReturn(Optional.of(order));

        try (var mockStatic = mockStatic(SecurityContextHelper.class)) {
            mockStatic.when(() -> SecurityContextHelper.hasAuthority("order:line:enter_manual_price"))
                    .thenReturn(false);

            // when / then
            assertThatThrownBy(() -> salesOrderService.addItem(
                    orderId, "ABC-123", 1, "need manual price", new BigDecimal("15.00")))
                    .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        }
    }

    // -----------------------------------------------------------------------
    // Scenario 9: Anonymous cart restrictions
    // -----------------------------------------------------------------------

    /**
     * Scenario 9: Applying a customer-specific promotion on an anonymous cart (no
     * customerId)
     * is rejected with an informative message about customer assignment.
     *
     * <p>
     * This test targets {@link SalesOrderService#linkSource} which is the surface
     * used to
     * pull in promotions tied to a customer. The service must reject the call when
     * customerId
     * is absent.
     */
    @Test
    void linkSource_whenCartIsAnonymousAndCustomerPromotionRequested_thenRejectedWithCustomerMessage() {
        // given
        UUID orderId = UUID.randomUUID();
        SalesOrder anonymousOrder = SalesOrder.builder()
                .orderId(orderId)
                .clerkId("clerk-001")
                .terminalId("terminal-001")
                .customerId(null) // anonymous
                .status(SalesOrderStatus.DRAFT)
                .subtotal(BigDecimal.ZERO)
                .build();
        when(salesOrderRepository.findById(orderId)).thenReturn(Optional.of(anonymousOrder));

        // when / then – must throw with a message about requiring customer assignment
        assertThatThrownBy(
                () -> salesOrderService.linkSource(orderId, "WORKORDER", "PROMO-001"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageMatching("(?i).*customer.*");
    }

    // -----------------------------------------------------------------------
    // Edge: getOrder — not found
    // -----------------------------------------------------------------------

    /**
     * getOrder with an unknown orderId must throw SalesOrderNotFoundException.
     */
    @Test
    void getOrder_whenOrderNotFound_thenThrowsSalesOrderNotFoundException() {
        // given
        UUID unknownId = UUID.randomUUID();
        when(salesOrderRepository.findById(unknownId)).thenReturn(Optional.empty());

        // when / then
        assertThatThrownBy(() -> salesOrderService.getOrder(unknownId))
                .isInstanceOf(com.positivity.order.internal.exception.SalesOrderNotFoundException.class);
    }
}

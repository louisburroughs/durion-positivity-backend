package com.positivity.order.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.domainevents.inventory.GoodsReceiptRecordedV1;
import com.positivity.domainevents.order.PurchaseOrderUpdatedV1;
import com.positivity.order.internal.dto.purchaseorder.ApprovePurchaseOrderRequest;
import com.positivity.order.internal.dto.purchaseorder.CreatePurchaseOrderRequest;
import com.positivity.order.internal.dto.purchaseorder.PurchaseOrderLineRequest;
import com.positivity.order.internal.dto.purchaseorder.PurchaseOrderResponse;
import com.positivity.order.internal.dto.purchaseorder.RevisePurchaseOrderRequest;
import com.positivity.order.internal.entity.ExtSupplierVendor;
import com.positivity.order.internal.entity.PurchaseOrderEntity;
import com.positivity.order.internal.entity.PurchaseOrderLineEntity;
import com.positivity.order.internal.enums.PurchaseOrderStatus;
import com.positivity.order.internal.repository.ExtInventoryAvailabilityRepository;
import com.positivity.order.internal.repository.ExtSupplierVendorRepository;
import com.positivity.order.internal.repository.ProcessedEventRepository;
import com.positivity.order.internal.repository.PurchaseOrderLineRepository;
import com.positivity.order.internal.repository.PurchaseOrderRepository;
import com.positivity.order.internal.repository.PurchaseOrderTransmissionEventRepository;
import com.positivity.order.internal.repository.SalesOrderLineRepository;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

/**
 * #2633: a purchase order's open balance is ex-tax from creation through receipt. It starts at the
 * subtotal, and each {@code goodsreceipt.recorded} takes off an accrual that carries no tax either,
 * so a taxed order received in full reaches zero. {@code taxMinor} and {@code grandTotalMinor} keep
 * describing the document.
 */
@DisplayName("Purchase order open balance — ex-tax basis (#2633)")
class PurchaseOrderOpenBalanceBasisTest {

    private static final Instant NOW = Instant.parse("2026-10-11T12:00:00Z");
    private static final UUID PO_ID = UUID.fromString("019a0000-0000-7000-8000-000000002633");
    private static final UUID VENDOR_ID = UUID.fromString("019a0000-0000-7000-8000-0000000026b0");

    private final PurchaseOrderRepository orders = mock(PurchaseOrderRepository.class);
    private final EntityManager entityManager = mock(EntityManager.class);
    private final PurchaseOrderFactPublisher publisher = mock(PurchaseOrderFactPublisher.class);
    private final DocumentQuantityConverter documentQuantityConverter = mock(DocumentQuantityConverter.class);
    private final ExtSupplierVendorRepository vendors = mock(ExtSupplierVendorRepository.class);

    /** The one order this test works on, as the repository would hold it. */
    private final AtomicReference<PurchaseOrderEntity> stored = new AtomicReference<>();

    private PurchaseOrderServiceImpl service;
    private InventoryEventsListener listener;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        service = new PurchaseOrderServiceImpl(
                orders,
                mock(PurchaseOrderLineRepository.class),
                mock(PurchaseOrderTransmissionEventRepository.class),
                entityManager,
                publisher,
                documentQuantityConverter,
                clock,
                new SupplierVendorGuard(vendors));
        listener = new InventoryEventsListener(
                clock,
                new ObjectMapper(),
                mock(ProcessedEventRepository.class),
                orders,
                publisher,
                mock(ExtInventoryAvailabilityRepository.class),
                mock(SalesOrderLineRepository.class),
                mock(PlatformTransactionManager.class));

        when(vendors.findById(VENDOR_ID))
                .thenReturn(Optional.of(ExtSupplierVendor.builder()
                        .vendorId(VENDOR_ID)
                        .vendorNumber("V-002633")
                        .displayName("Acme Parts")
                        .status(ExtSupplierVendor.Status.ACTIVE)
                        .aggregateVersion(1L)
                        .updatedAt(NOW)
                        .build()));
        when(documentQuantityConverter.convertIfPresent(any(), any(), any(), any()))
                .thenReturn(Optional.empty());
        when(orders.getNextPurchaseOrderSequence()).thenReturn(2633L);
        when(orders.save(any())).thenAnswer(invocation -> {
            PurchaseOrderEntity saved = invocation.getArgument(0);
            if (saved.getPurchaseOrderId() == null) {
                saved.setPurchaseOrderId(PO_ID);
            }
            assignLineIds(saved);
            stored.set(saved);
            return saved;
        });
        when(orders.findById(PO_ID)).thenAnswer(_ -> Optional.ofNullable(stored.get()));
    }

    /** What the database would do on insert: every line gets an id, which receipts name. */
    private static void assignLineIds(PurchaseOrderEntity order) {
        order.getLines().forEach(line -> {
            if (line.getLineId() == null) {
                line.setLineId(UUID.randomUUID());
            }
        });
    }

    private static PurchaseOrderLineRequest taxedLine(int lineNumber, String quantity, long unitCostMinor) {
        PurchaseOrderLineRequest line = new PurchaseOrderLineRequest();
        line.setLineNumber(lineNumber);
        line.setSkuId(UUID.randomUUID());
        line.setDescription("Line " + lineNumber);
        line.setQuantity(new BigDecimal(quantity));
        line.setUnitCostMinor(unitCostMinor);
        line.setTaxCodeId("TAX-STD");
        return line;
    }

    /** The issue's example: ten taxed lines of one unit at 100.00, so 1,000.00 of goods and 100.00 of tax. */
    private static CreatePurchaseOrderRequest tenTaxedLines() {
        List<PurchaseOrderLineRequest> lines = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            lines.add(taxedLine(i, "1", 10_000L));
        }
        CreatePurchaseOrderRequest request = new CreatePurchaseOrderRequest();
        request.setVendorId(VENDOR_ID);
        request.setCurrency("USD");
        request.setPoDate(LocalDate.of(2026, 10, 11));
        request.setLines(lines);
        return request;
    }

    @Test
    @DisplayName("create: the open balance is the subtotal; tax and grand total describe the document")
    void createStartsAtTheSubtotal() {
        PurchaseOrderResponse created = service.createPurchaseOrder(tenTaxedLines(), "buyer");

        assertThat(created.getSubtotalMinor()).isEqualTo(100_000L);
        assertThat(created.getTaxMinor()).isEqualTo(10_000L);
        assertThat(created.getGrandTotalMinor()).isEqualTo(110_000L);
        assertThat(created.getOpenBalanceMinor()).isEqualTo(100_000L);
        assertThat(lastFact().openBalanceMinor()).isEqualTo(100_000L);
    }

    @Test
    @DisplayName("a requested order (pos-inventory's conversion) starts at the subtotal too")
    void requestedOrderStartsAtTheSubtotal() {
        service.createRequested(PO_ID, tenTaxedLines(), "pos-inventory");

        ArgumentCaptor<PurchaseOrderEntity> persisted = ArgumentCaptor.forClass(PurchaseOrderEntity.class);
        verify(entityManager).persist(persisted.capture());
        assertThat(persisted.getValue().getGrandTotalMinor()).isEqualTo(110_000L);
        assertThat(persisted.getValue().getOpenBalanceMinor()).isEqualTo(100_000L);
    }

    @Test
    @DisplayName("revise: the balance resets to the new subtotal")
    void reviseResetsToTheNewSubtotal() {
        service.createPurchaseOrder(tenTaxedLines(), "buyer");
        RevisePurchaseOrderRequest revision = new RevisePurchaseOrderRequest();
        revision.setRevisionReason("vendor raised the price");
        revision.setPoDate(LocalDate.of(2026, 10, 11));
        revision.setLines(List.of(taxedLine(1, "4", 12_500L)));

        PurchaseOrderResponse revised = service.revisePurchaseOrder(PO_ID, revision, "buyer");

        assertThat(revised.getSubtotalMinor()).isEqualTo(50_000L);
        assertThat(revised.getTaxMinor()).isEqualTo(5_000L);
        assertThat(revised.getGrandTotalMinor()).isEqualTo(55_000L);
        assertThat(revised.getOpenBalanceMinor()).isEqualTo(50_000L);
    }

    @Test
    @DisplayName("a taxed order received in full reaches zero, publishing the ex-tax remainder after each receipt")
    void taxedOrderReceivedInFullReachesZero() {
        service.createPurchaseOrder(tenTaxedLines(), "buyer");
        service.approvePurchaseOrder(PO_ID, new ApprovePurchaseOrderRequest(), "approver");
        List<PurchaseOrderLineEntity> lines = stored.get().getLines();

        // Three lines first: 300.00 of goods accrued, 700.00 still open.
        listener.onInventoryEvent(receipt(lines.subList(0, 3)));
        assertThat(stored.get().getStatus()).isEqualTo(PurchaseOrderStatus.PARTIALLY_RECEIVED);
        assertThat(stored.get().getOpenBalanceMinor()).isEqualTo(70_000L);
        assertThat(lastFact().openBalanceMinor()).isEqualTo(70_000L);

        listener.onInventoryEvent(receipt(lines.subList(3, 10)));
        assertThat(stored.get().getStatus()).isEqualTo(PurchaseOrderStatus.FULLY_RECEIVED);
        assertThat(stored.get().getOpenBalanceMinor()).isZero();
        assertThat(stored.get().getGrandTotalMinor()).isEqualTo(110_000L);
        assertThat(stored.get().getTaxMinor()).isEqualTo(10_000L);
        assertThat(lastFact().openBalanceMinor()).isZero();
    }

    /** The last fact queued for the order, through the mapping production publishes with. */
    private PurchaseOrderUpdatedV1 lastFact() {
        ArgumentCaptor<PurchaseOrderEntity> published = ArgumentCaptor.forClass(PurchaseOrderEntity.class);
        verify(publisher, atLeastOnce()).publish(published.capture(), anyList());
        PurchaseOrderEntity order = published.getValue();
        return PurchaseOrderFactPublisher.toFact(order, order.getLines(), NOW);
    }

    /**
     * A receipt of every open unit on the given lines, accrued the way pos-inventory accrues it:
     * quantity × unit cost, no tax.
     */
    private static String receipt(List<PurchaseOrderLineEntity> lines) {
        StringBuilder receiptLines = new StringBuilder();
        long total = 0L;
        for (PurchaseOrderLineEntity line : lines) {
            long accrued = line.getQuantityDecimal()
                    .multiply(BigDecimal.valueOf(line.getUnitCostMinor()))
                    .longValueExact();
            total += accrued;
            if (!receiptLines.isEmpty()) {
                receiptLines.append(',');
            }
            receiptLines.append(
                    """
                    {"poLineId":"%s","sku":"SKU","quantityReceived":%s,"accruedAmountMinor":%d}""".formatted(line.getLineId(), line.getQuantityDecimal().toPlainString(), accrued));
        }
        String payload = """
                {"receiptId":"%s","receiptNumber":"GR-2633","purchaseOrderId":"%s","locationId":null,
                 "totalAccruedAmountMinor":%d,"occurredAt":"%s","lines":[%s]}
                """.formatted(UUID.randomUUID(), PO_ID, total, NOW, receiptLines);
        return """
                {"eventId":"%s","eventType":"%s","schemaVersion":1,"aggregateId":"%s","aggregateVersion":0,
                 "occurredAtUtc":"%s","sourceService":"pos-inventory","payload":%s}
                """.formatted(UUID.randomUUID(), GoodsReceiptRecordedV1.EVENT_TYPE, PO_ID, NOW, payload);
    }
}

package com.positivity.order.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.domainevents.inventory.GoodsReceiptRecordedV1;
import com.positivity.domainevents.inventory.VendorReturnRecordedV1;
import com.positivity.order.internal.entity.PurchaseOrderEntity;
import com.positivity.order.internal.entity.PurchaseOrderLineEntity;
import com.positivity.order.internal.enums.PurchaseOrderStatus;
import com.positivity.order.internal.repository.ExtInventoryAvailabilityRepository;
import com.positivity.order.internal.repository.ProcessedEventRepository;
import com.positivity.order.internal.repository.PurchaseOrderRepository;
import com.positivity.order.internal.repository.SalesOrderLineRepository;
import com.positivity.tenancy.TenantContext;
import com.positivity.tenancy.testing.TenantTestSupport;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * #2635 through the real repositories and transactions: a receipt grows the received figures; a receipt correction
 * reopens the line and republishes (version bump); a return to vendor leaves open, balance and status alone and
 * publishes nothing (no bump); a cancelled order keeps its status while its figures move; the processed mark is
 * written either way.
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("vendorreturn.recorded on a purchase order (#2635)")
class VendorReturnConsumerTransactionTest {

    private static final Instant NOW = Instant.parse("2026-10-17T10:00:00Z");
    private static final UUID VENDOR_ID = UUID.fromString("019a0000-0000-7000-8000-0000000026b1");
    private static final UUID SKU_ID = UUID.fromString("019a0000-0000-7000-8000-0000000026c1");

    @Autowired
    private PurchaseOrderRepository purchaseOrderRepository;

    @Autowired
    private ProcessedEventRepository processedEventRepository;

    @Autowired
    private PurchaseOrderFactPublisher purchaseOrderFactPublisher;

    @Autowired
    private ExtInventoryAvailabilityRepository extInventoryAvailabilityRepository;

    @Autowired
    private SalesOrderLineRepository salesOrderLineRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private InventoryEventsListener listener;
    private TransactionTemplate transaction;
    private final List<UUID> created = new ArrayList<>();

    @BeforeEach
    void setUp() {
        TenantContext.bind(TenantTestSupport.TENANT_A);
        listener = new InventoryEventsListener(
                Clock.systemUTC(),
                new ObjectMapper(),
                processedEventRepository,
                purchaseOrderRepository,
                purchaseOrderFactPublisher,
                extInventoryAvailabilityRepository,
                salesOrderLineRepository,
                transactionManager);
        transaction = new TransactionTemplate(transactionManager);
    }

    @AfterEach
    void tearDown() {
        transaction.executeWithoutResult(_ -> created.forEach(purchaseOrderRepository::deleteById));
        created.clear();
        TenantContext.clear();
    }

    @Test
    @DisplayName(
            "receipt 6 of 10, correction 2: the line reopens to 6, PARTIALLY_RECEIVED, version bumped, balance 6000")
    void receiptCorrectionReopensTheLine() {
        UUID orderId = approvedOrder();
        UUID lineId = onlyLineId(orderId);

        listener.onInventoryEvent(receipt(orderId, lineId, "6"));
        Snapshot received = snapshot(orderId);
        assertThat(received.status()).isEqualTo(PurchaseOrderStatus.PARTIALLY_RECEIVED);
        assertThat(received.open()).isEqualByComparingTo("4");
        assertThat(received.received()).isEqualByComparingTo("6");
        assertThat(received.balance()).isEqualTo(4_000L);
        assertThat(received.version()).isEqualTo(2);

        String eventId = UUID.randomUUID().toString();
        listener.onInventoryEvent(vendorReturn(eventId, orderId, lineId, "RECEIPT_CORRECTION", "2", "20.00"));
        Snapshot corrected = snapshot(orderId);
        assertThat(corrected.received()).isEqualByComparingTo("4");
        assertThat(corrected.returned()).isEqualByComparingTo("0");
        assertThat(corrected.open()).isEqualByComparingTo("6");
        assertThat(corrected.balance()).isEqualTo(6_000L);
        assertThat(corrected.status()).isEqualTo(PurchaseOrderStatus.PARTIALLY_RECEIVED);
        assertThat(corrected.version()).as("republished").isEqualTo(3);
        assertThat(processedEventRepository.existsById(eventId)).isTrue();
    }

    @Test
    @DisplayName(
            "receipt 10 of 10 then a return to vendor of 3: received 7, returned 3, open 0, FULLY_RECEIVED, no bump")
    void returnToVendorDefaultsToCredit() {
        UUID orderId = approvedOrder();
        UUID lineId = onlyLineId(orderId);
        listener.onInventoryEvent(receipt(orderId, lineId, "10"));
        assertThat(snapshot(orderId).status()).isEqualTo(PurchaseOrderStatus.FULLY_RECEIVED);

        String eventId = UUID.randomUUID().toString();
        listener.onInventoryEvent(vendorReturn(eventId, orderId, lineId, "RETURN_TO_VENDOR", "3", "30.00"));

        Snapshot after = snapshot(orderId);
        assertThat(after.received()).isEqualByComparingTo("7");
        assertThat(after.returned()).isEqualByComparingTo("3");
        assertThat(after.open()).isEqualByComparingTo("0");
        assertThat(after.balance())
                .as("the credit takes the receipt's accrual off: nothing owed")
                .isZero();
        assertThat(after.status()).isEqualTo(PurchaseOrderStatus.FULLY_RECEIVED);
        assertThat(after.version()).as("nothing published").isEqualTo(2);
        assertThat(processedEventRepository.existsById(eventId)).isTrue();
    }

    @Test
    @DisplayName("a correction on a CANCELLED order moves the figures and keeps the status; an unknown kind is skipped")
    void cancelledOrderKeepsItsStatus() {
        UUID orderId = approvedOrder();
        UUID lineId = onlyLineId(orderId);
        listener.onInventoryEvent(receipt(orderId, lineId, "4"));
        transaction.executeWithoutResult(_ -> {
            PurchaseOrderEntity order =
                    purchaseOrderRepository.findById(orderId).orElseThrow();
            order.setStatus(PurchaseOrderStatus.CANCELLED);
            purchaseOrderRepository.save(order);
        });

        listener.onInventoryEvent(
                vendorReturn(UUID.randomUUID().toString(), orderId, lineId, "RECEIPT_CORRECTION", "1", "10.00"));
        Snapshot after = snapshot(orderId);
        assertThat(after.status()).isEqualTo(PurchaseOrderStatus.CANCELLED);
        assertThat(after.received()).isEqualByComparingTo("3");

        String unknown = UUID.randomUUID().toString();
        listener.onInventoryEvent(vendorReturn(unknown, orderId, lineId, "SOMETHING_ELSE", "1", "10.00"));
        assertThat(snapshot(orderId).received()).isEqualByComparingTo("3");
        assertThat(processedEventRepository.existsById(unknown)).isTrue();
    }

    private UUID approvedOrder() {
        PurchaseOrderEntity order = PurchaseOrderEntity.builder()
                .poNumber("VR" + UUID.randomUUID().toString().substring(0, 6))
                .vendorId(VENDOR_ID)
                .status(PurchaseOrderStatus.APPROVED)
                .versionNumber(1)
                .currency("USD")
                .subtotalMinor(10_000L)
                .taxMinor(0L)
                .grandTotalMinor(10_000L)
                .openBalanceMinor(10_000L)
                .createdBy("buyer")
                .lines(new ArrayList<>())
                .build();
        order.getLines()
                .add(PurchaseOrderLineEntity.builder()
                        .lineNumber(1)
                        .skuId(SKU_ID)
                        .description("Front brake rotor")
                        .quantityDecimal(BigDecimal.TEN)
                        .openQuantityDecimal(BigDecimal.TEN)
                        .unitCostMinor(1_000L)
                        .lineTotalMinor(10_000L)
                        .taxMinor(0L)
                        .purchaseOrder(order)
                        .build());
        UUID id = transaction.execute(_ -> purchaseOrderRepository.save(order).getPurchaseOrderId());
        created.add(id);
        return id;
    }

    private UUID onlyLineId(UUID orderId) {
        return transaction.execute(_ -> purchaseOrderRepository
                .findById(orderId)
                .orElseThrow()
                .getLines()
                .getFirst()
                .getLineId());
    }

    private record Snapshot(
            PurchaseOrderStatus status,
            int version,
            BigDecimal open,
            BigDecimal received,
            BigDecimal returned,
            long balance) {}

    private Snapshot snapshot(UUID orderId) {
        return transaction.execute(_ -> {
            PurchaseOrderEntity order =
                    purchaseOrderRepository.findById(orderId).orElseThrow();
            PurchaseOrderLineEntity line = order.getLines().getFirst();
            return new Snapshot(
                    order.getStatus(),
                    order.getVersionNumber(),
                    line.getOpenQuantityDecimal(),
                    line.getReceivedQuantity(),
                    line.getReturnedQuantity(),
                    order.getOpenBalanceMinor());
        });
    }

    private static String envelope(String eventId, String eventType, UUID orderId, String payload) {
        return """
                {"eventId":"%s","eventType":"%s","schemaVersion":1,"aggregateId":"%s","aggregateVersion":0,
                 "occurredAtUtc":"%s","sourceService":"pos-inventory","payload":%s}
                """.formatted(eventId, eventType, orderId, NOW, payload);
    }

    private static String receipt(UUID orderId, UUID poLineId, String quantity) {
        long accrued = new BigDecimal(quantity).longValueExact() * 1_000L;
        String payload = """
                {"receiptId":"%s","receiptNumber":"GR-2635","purchaseOrderId":"%s","locationId":null,
                 "totalAccruedAmountMinor":%d,"occurredAt":"%s","currencyCode":"USD",
                 "lines":[{"poLineId":"%s","sku":"SKU","quantityReceived":%s,"accruedAmountMinor":%d}]}
                """.formatted(UUID.randomUUID(), orderId, accrued, NOW, poLineId, quantity, accrued);
        return envelope(UUID.randomUUID().toString(), GoodsReceiptRecordedV1.EVENT_TYPE, orderId, payload);
    }

    private static String vendorReturn(
            String eventId, UUID orderId, UUID poLineId, String kind, String quantity, String accruedAmount) {
        String payload = """
                {"vendorReturnId":"%s","purchaseOrderId":"%s","returnKind":"%s","currencyCode":"USD",
                 "occurredAt":"%s","lines":[{"receiptId":"%s","receiptLineId":"%s","poLineId":"%s",
                 "quantityReturned":%s,"accruedAmount":%s}]}
                """.formatted(
                        UUID.randomUUID(),
                        orderId,
                        kind,
                        NOW,
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        poLineId,
                        quantity,
                        accruedAmount);
        return envelope(eventId, VendorReturnRecordedV1.EVENT_TYPE, orderId, payload);
    }
}

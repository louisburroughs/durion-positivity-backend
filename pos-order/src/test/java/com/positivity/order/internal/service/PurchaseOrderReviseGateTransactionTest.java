package com.positivity.order.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.positivity.domainevents.inventory.GoodsReceiptRecordedV1;
import com.positivity.order.internal.dto.purchaseorder.PurchaseOrderLineRequest;
import com.positivity.order.internal.dto.purchaseorder.PurchaseOrderResponse;
import com.positivity.order.internal.dto.purchaseorder.RevisePurchaseOrderRequest;
import com.positivity.order.internal.entity.PurchaseOrderEntity;
import com.positivity.order.internal.entity.PurchaseOrderLineEntity;
import com.positivity.order.internal.enums.PurchaseOrderStatus;
import com.positivity.order.internal.exception.PurchaseOrderStateConflictException;
import com.positivity.order.internal.repository.ExtInventoryAvailabilityRepository;
import com.positivity.order.internal.repository.ProcessedEventRepository;
import com.positivity.order.internal.repository.PurchaseOrderRepository;
import com.positivity.order.internal.repository.SalesOrderLineRepository;
import com.positivity.tenancy.TenantContext;
import com.positivity.tenancy.testing.TenantTestSupport;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
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
 * #2632 through the real service, repositories and transactions: a revision is refused once anything was received,
 * judged under the order's row lock, and leaves the order's line ids in place, so a receipt that names the original
 * line is still applied to it.
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("Purchase order revise gate — persisted order (#2632)")
class PurchaseOrderReviseGateTransactionTest {

    private static final UUID VENDOR_ID = UUID.fromString("019a0000-0000-7000-8000-0000000026c0");
    private static final UUID SKU_ID = UUID.fromString("019a0000-0000-7000-8000-0000000026c1");
    private static final Instant NOW = Instant.parse("2026-10-11T12:00:00Z");

    @Autowired
    private PurchaseOrderService purchaseOrderService;

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
        TenantContext.clear();
    }

    @Test
    @DisplayName("receive then revise: 409, nothing changes, and a later receipt is applied to the original line")
    void receiveThenRevise_isRefusedAndTheOriginalLineKeepsReceiving() {
        UUID orderId = approvedOrder();
        UUID lineId = onlyLine(orderId).lineId();
        listener.onInventoryEvent(receipt(orderId, lineId, "4"));
        assertThat(onlyLine(orderId).open()).isEqualByComparingTo("6");

        assertThatThrownBy(() -> purchaseOrderService.revisePurchaseOrder(orderId, revision(), "buyer"))
                .isInstanceOf(PurchaseOrderStateConflictException.class);

        Snapshot refused = snapshot(orderId);
        assertThat(refused.status()).isEqualTo(PurchaseOrderStatus.PARTIALLY_RECEIVED);
        assertThat(refused.version()).isEqualTo(2);
        assertThat(refused.line().lineId()).isEqualTo(lineId);
        assertThat(refused.line().open()).isEqualByComparingTo("6");

        listener.onInventoryEvent(receipt(orderId, lineId, "3"));

        assertThat(onlyLine(orderId).lineId()).isEqualTo(lineId);
        assertThat(onlyLine(orderId).open()).isEqualByComparingTo("3");
    }

    @Test
    @DisplayName("an approved order with nothing received is still revised")
    void approvedWithNothingReceived_isRevised() {
        UUID orderId = approvedOrder();

        PurchaseOrderResponse revised = purchaseOrderService.revisePurchaseOrder(orderId, revision(), "buyer");

        assertThat(revised.getStatus()).isEqualTo(PurchaseOrderStatus.APPROVED);
        assertThat(revised.getVersionNumber()).isEqualTo(2);
        Snapshot stored = snapshot(orderId);
        assertThat(stored.version()).isEqualTo(2);
        assertThat(stored.line().open()).isEqualByComparingTo("4");
    }

    private UUID approvedOrder() {
        PurchaseOrderEntity order = PurchaseOrderEntity.builder()
                .poNumber("RG" + UUID.randomUUID().toString().substring(0, 6))
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
        return transaction.execute(_ -> purchaseOrderRepository.save(order).getPurchaseOrderId());
    }

    private static RevisePurchaseOrderRequest revision() {
        PurchaseOrderLineRequest line = new PurchaseOrderLineRequest();
        line.setLineNumber(1);
        line.setSkuId(SKU_ID);
        line.setDescription("Front brake rotor");
        line.setQuantity(new BigDecimal("4"));
        line.setUnitCostMinor(1_200L);
        RevisePurchaseOrderRequest request = new RevisePurchaseOrderRequest();
        request.setExpectedVersionNumber(1);
        request.setRevisionReason("vendor changed the price");
        request.setPoDate(LocalDate.of(2026, 10, 11));
        request.setLines(List.of(line));
        return request;
    }

    private record LineSnapshot(UUID lineId, BigDecimal open) {}

    private record Snapshot(PurchaseOrderStatus status, int version, LineSnapshot line) {}

    private Snapshot snapshot(UUID orderId) {
        return transaction.execute(_ -> {
            PurchaseOrderEntity order =
                    purchaseOrderRepository.findById(orderId).orElseThrow();
            assertThat(order.getLines()).hasSize(1);
            PurchaseOrderLineEntity line = order.getLines().getFirst();
            return new Snapshot(
                    order.getStatus(),
                    order.getVersionNumber(),
                    new LineSnapshot(line.getLineId(), line.getOpenQuantityDecimal()));
        });
    }

    private LineSnapshot onlyLine(UUID orderId) {
        return snapshot(orderId).line();
    }

    private static String receipt(UUID orderId, UUID poLineId, String quantity) {
        String payload = """
                {"receiptId":"%s","receiptNumber":"GR-2632","purchaseOrderId":"%s","locationId":null,
                 "totalAccruedAmountMinor":%d,"occurredAt":"%s",
                 "lines":[{"poLineId":"%s","sku":"SKU","quantityReceived":%s,"accruedAmountMinor":%d}]}
                """.formatted(
                        UUID.randomUUID(),
                        orderId,
                        new BigDecimal(quantity).longValueExact() * 1_000L,
                        NOW,
                        poLineId,
                        quantity,
                        new BigDecimal(quantity).longValueExact() * 1_000L);
        return """
                {"eventId":"%s","eventType":"%s","schemaVersion":1,"aggregateId":"%s","aggregateVersion":0,
                 "occurredAtUtc":"%s","sourceService":"pos-inventory","payload":%s}
                """.formatted(UUID.randomUUID(), GoodsReceiptRecordedV1.EVENT_TYPE, orderId, NOW, payload);
    }
}

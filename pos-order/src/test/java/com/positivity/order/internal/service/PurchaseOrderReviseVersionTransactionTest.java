package com.positivity.order.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.positivity.order.internal.dto.purchaseorder.ApprovePurchaseOrderRequest;
import com.positivity.order.internal.dto.purchaseorder.PurchaseOrderLineRequest;
import com.positivity.order.internal.dto.purchaseorder.PurchaseOrderResponse;
import com.positivity.order.internal.dto.purchaseorder.RevisePurchaseOrderRequest;
import com.positivity.order.internal.entity.ExtSupplierVendor;
import com.positivity.order.internal.entity.PurchaseOrderEntity;
import com.positivity.order.internal.entity.PurchaseOrderLineEntity;
import com.positivity.order.internal.enums.PurchaseOrderStatus;
import com.positivity.order.internal.exception.PurchaseOrderVersionConflictException;
import com.positivity.order.internal.repository.ExtSupplierVendorRepository;
import com.positivity.order.internal.repository.PurchaseOrderRepository;
import com.positivity.tenancy.TenantContext;
import com.positivity.tenancy.testing.TenantTestSupport;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * #2681 through the real service, repositories and transactions: a revise built on a stale version is refused and
 * leaves the row, its lines and its version alone; two revisions built on the same version, started together, end
 * with exactly one committed; an approve that names a stale version is refused the same way.
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("Purchase order expected version — persisted order (#2681)")
class PurchaseOrderReviseVersionTransactionTest {

    private static final UUID VENDOR_ID = UUID.fromString("019a0000-0000-7000-8000-0000000026d0");
    private static final UUID SKU_ID = UUID.fromString("019a0000-0000-7000-8000-0000000026d1");

    @Autowired
    private PurchaseOrderService purchaseOrderService;

    @Autowired
    private PurchaseOrderRepository purchaseOrderRepository;

    @Autowired
    private ExtSupplierVendorRepository vendorRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate transaction;

    @BeforeEach
    void setUp() {
        TenantContext.bind(TenantTestSupport.TENANT_A);
        transaction = new TransactionTemplate(transactionManager);
        transaction.executeWithoutResult(_ -> {
            if (vendorRepository.findById(VENDOR_ID).isEmpty()) {
                vendorRepository.save(ExtSupplierVendor.builder()
                        .vendorId(VENDOR_ID)
                        .vendorNumber("V-002681")
                        .displayName("Version Test Supply")
                        .status(ExtSupplierVendor.Status.ACTIVE)
                        .aggregateVersion(1L)
                        .updatedAt(Instant.parse("2026-10-16T00:00:00Z"))
                        .build());
            }
        });
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("a stale expected version is refused and the row, its line and its version are untouched")
    void staleVersionChangesNothing() {
        UUID orderId = draftOrder();
        Snapshot before = snapshot(orderId);

        assertThatThrownBy(() -> purchaseOrderService.revisePurchaseOrder(orderId, revisionBuiltOn(5), "buyer"))
                .isInstanceOf(PurchaseOrderVersionConflictException.class);

        assertThat(snapshot(orderId)).isEqualTo(before);
    }

    @Test
    @DisplayName("two revisions built on the same version, started together: exactly one commits, the other is 409")
    void concurrentRevisionsOnOneVersion() throws Exception {
        UUID orderId = draftOrder();
        CyclicBarrier start = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Outcome> buyer = () -> TenantTestSupport.asTenant(TenantTestSupport.TENANT_A, () -> {
                start.await(10, TimeUnit.SECONDS);
                try {
                    PurchaseOrderResponse revised =
                            purchaseOrderService.revisePurchaseOrder(orderId, revisionBuiltOn(1), "buyer");
                    return new Outcome(true, revised.getVersionNumber());
                } catch (PurchaseOrderVersionConflictException refused) {
                    return new Outcome(false, refused.getCurrentVersionNumber());
                }
            });
            Future<Outcome> first = pool.submit(buyer);
            Future<Outcome> second = pool.submit(buyer);
            List<Outcome> outcomes = List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));

            assertThat(outcomes.stream().filter(Outcome::committed)).hasSize(1);
            assertThat(outcomes)
                    .as("the winner reaches version 2; the loser was refused against version 2")
                    .allSatisfy(outcome -> assertThat(outcome.version()).isEqualTo(2));
        } finally {
            pool.shutdownNow();
        }
        assertThat(snapshot(orderId).version()).isEqualTo(2);
    }

    @Test
    @DisplayName("an approve naming a stale version is refused; naming the current one approves")
    void approveHonoursExpectedVersion() {
        UUID orderId = draftOrder();
        purchaseOrderService.revisePurchaseOrder(orderId, revisionBuiltOn(1), "buyer");
        ApprovePurchaseOrderRequest stale = new ApprovePurchaseOrderRequest();
        stale.setExpectedVersionNumber(1);

        assertThatThrownBy(() -> purchaseOrderService.approvePurchaseOrder(orderId, stale, "approver"))
                .isInstanceOf(PurchaseOrderVersionConflictException.class);
        assertThat(snapshot(orderId).status()).isEqualTo(PurchaseOrderStatus.DRAFT);

        ApprovePurchaseOrderRequest current = new ApprovePurchaseOrderRequest();
        current.setExpectedVersionNumber(2);
        assertThat(purchaseOrderService.approvePurchaseOrder(orderId, current, "approver").getStatus())
                .isEqualTo(PurchaseOrderStatus.APPROVED);
    }

    private UUID draftOrder() {
        PurchaseOrderEntity order = PurchaseOrderEntity.builder()
                .poNumber("RV" + UUID.randomUUID().toString().substring(0, 6))
                .vendorId(VENDOR_ID)
                .status(PurchaseOrderStatus.DRAFT)
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

    private static RevisePurchaseOrderRequest revisionBuiltOn(int version) {
        PurchaseOrderLineRequest line = new PurchaseOrderLineRequest();
        line.setLineNumber(1);
        line.setSkuId(SKU_ID);
        line.setDescription("Front brake rotor");
        line.setQuantity(new BigDecimal("4"));
        line.setUnitCostMinor(1_200L);
        RevisePurchaseOrderRequest request = new RevisePurchaseOrderRequest();
        request.setExpectedVersionNumber(version);
        request.setRevisionReason("vendor changed the price");
        request.setPoDate(LocalDate.of(2026, 10, 16));
        request.setLines(List.of(line));
        return request;
    }

    private record Outcome(boolean committed, int version) {}

    private record Snapshot(PurchaseOrderStatus status, int version, UUID lineId, BigDecimal quantity) {}

    private Snapshot snapshot(UUID orderId) {
        return transaction.execute(_ -> {
            PurchaseOrderEntity order =
                    purchaseOrderRepository.findById(orderId).orElseThrow();
            PurchaseOrderLineEntity line = order.getLines().getFirst();
            return new Snapshot(
                    order.getStatus(), order.getVersionNumber(), line.getLineId(), line.getQuantityDecimal());
        });
    }
}

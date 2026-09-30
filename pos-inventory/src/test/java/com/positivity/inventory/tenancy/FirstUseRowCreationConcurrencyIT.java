package com.positivity.inventory.tenancy;

import static com.positivity.tenancy.testing.TenantTestSupport.TENANT_A;
import static com.positivity.tenancy.testing.TenantTestSupport.asTenant;
import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.inventory.internal.entity.ExtProductReplica;
import com.positivity.inventory.internal.entity.InventoryLedgerEntry;
import com.positivity.inventory.internal.enums.InventoryLedgerEventType;
import com.positivity.inventory.internal.repository.ExtProductReplicaRepository;
import com.positivity.inventory.internal.service.InventoryLotCaptureService;
import com.positivity.inventory.internal.service.LedgerPostingService;
import com.positivity.tenancy.TenantContext;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Issue #2344: first use of a new stock-summary / cost-state / lot row must need exactly one
 * connection per request thread. The pool is capped at 2 with a 2 s acquisition timeout while 6
 * threads race, so any return of a {@code REQUIRES_NEW} provisioner (a second connection held while
 * the caller's is open) or of a JVM-wide monitor held across a connection wait fails in seconds
 * with a connection timeout instead of passing. Postgres is the only place the lot-agnostic
 * (NULL {@code lot_id}) key race is provable: H2's schema cannot reject a NULL-key duplicate.
 *
 * <p>Requires Docker.
 */
@DisplayName("First-use row creation needs one connection (#2344, Postgres)")
class FirstUseRowCreationConcurrencyIT extends PostgresTenancyTestBase {

    private static final int THREADS = 6;

    @DynamicPropertySource
    static void smallPool(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "2");
        registry.add("spring.datasource.hikari.connection-timeout", () -> "2000");
    }

    @Autowired
    private LedgerPostingService ledgerPostingService;

    @Autowired
    private InventoryLotCaptureService lotCaptureService;

    @Autowired
    private ExtProductReplicaRepository productReplicas;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void concurrentFirstPostingsOfANewSkuAtANewLocationCreateOneSummaryAndOneCostState() throws Exception {
        String sku = "SKU-2344-" + UUID.randomUUID();
        UUID location = UUID.randomUUID();

        List<Integer> changes = List.of(1, 2, 3, 4, 5, 6);
        runConcurrently(changes.stream()
                .<Callable<UUID>>map(change -> () -> asTenant(TENANT_A, () -> {
                    ledgerPostingService.post(InventoryLedgerEntry.builder()
                            .stockItemId(sku)
                            .locationId(location)
                            .eventType(InventoryLedgerEventType.GOODS_RECEIPT)
                            .changeInQuantity(BigDecimal.valueOf(change))
                            .quantityAfter(BigDecimal.ZERO)
                            .transactionUserId("first-use-it")
                            .build());
                    return null;
                }))
                .toList());

        JdbcTemplate owner = new JdbcTemplate(ownerDataSource());
        assertThat(owner.queryForObject(
                        "SELECT count(*) FROM inventory_stock_summary WHERE stock_item_id = ? AND location_id = ?"
                                + " AND lot_id IS NULL",
                        Integer.class,
                        sku,
                        location))
                .as("exactly one lot-agnostic summary row for the key")
                .isEqualTo(1);
        assertThat(owner.queryForObject(
                        "SELECT count(*) FROM sku_cost_state WHERE stock_item_id = ?", Integer.class, sku))
                .as("exactly one cost-state row for the SKU")
                .isEqualTo(1);
        assertThat(owner.queryForObject(
                        "SELECT on_hand FROM inventory_stock_summary WHERE stock_item_id = ? AND location_id = ?",
                        BigDecimal.class,
                        sku,
                        location))
                .as("on-hand equals the sum of every posting")
                .isEqualByComparingTo(BigDecimal.valueOf(
                        changes.stream().mapToInt(Integer::intValue).sum()));
        assertThat(owner.queryForObject(
                        "SELECT count(*) FROM inventory_ledger_entry WHERE stock_item_id = ?", Integer.class, sku))
                .isEqualTo(THREADS);
    }

    @Test
    void concurrentFirstReceiptsOfANewLotCreateOneLotAndShareItsId() throws Exception {
        UUID product = UUID.randomUUID();
        asTenant(
                TENANT_A,
                () -> productReplicas.saveAndFlush(ExtProductReplica.builder()
                        .productId(product)
                        .trackingLevel("LOT")
                        .aggregateVersion(1L)
                        .build()));
        String lotNumber = "LOT-2344-" + UUID.randomUUID();
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        List<UUID> lotIds = runConcurrently(java.util.stream.IntStream.range(0, THREADS)
                .<Callable<UUID>>mapToObj(i -> () -> asTenant(
                        TENANT_A,
                        () -> tx.execute(status ->
                                lotCaptureService.resolveReceiptLot(product.toString(), lotNumber, null, null))))
                .toList());

        assertThat(Set.copyOf(lotIds)).as("every thread got the same lot id").hasSize(1);
        assertThat(lotIds.getFirst()).isNotNull();
        JdbcTemplate owner = new JdbcTemplate(ownerDataSource());
        assertThat(owner.queryForObject(
                        "SELECT count(*) FROM inventory_lot WHERE stock_item_id = ? AND lot_number = ?",
                        Integer.class,
                        product.toString(),
                        lotNumber))
                .as("exactly one lot row")
                .isEqualTo(1);
    }

    private static <T> List<T> runConcurrently(List<Callable<T>> work) throws Exception {
        CountDownLatch startGate = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(work.size());
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> task : work) {
                futures.add(executor.submit(() -> {
                    startGate.await();
                    return task.call();
                }));
            }
            startGate.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(60, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            executor.shutdownNow();
        }
    }
}
